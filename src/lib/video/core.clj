(ns lib.video.core
  "Love2D-style video API for Clojure.

   Provides GPU-accelerated video playback with Skia effects:
   - (file->video \"video.mp4\") -> Source
   - (file->video \"video.mp4\" {:hw-accel? true}) -> Try hardware decode
   - (file->video \"video.mp4\" {:zero-copy? true}) -> Try zero-copy decode
   - (play! source), (stop! source), (pause! source)
   - (seek! source time-seconds)
   - (current-frame source direct-context) -> Skia Image
   - (close! source)

   Decoder Modes (in order of efficiency):
   1. Zero-copy HW decode: GPU -> GL texture (no CPU involvement)
      - macOS: VideoToolbox -> IOSurface -> CGLTexImageIOSurface2D
      - Linux Intel/AMD: VAAPI -> DMA-BUF -> EGLImage
      - Linux NVIDIA: NVDEC -> CUDA -> GL interop (GPU-to-GPU copy)
      - Windows: D3D11VA -> WGL_NV_DX_interop
   2. HW decode + CPU copy: GPU decode -> CPU -> GL texture
      (still faster than software for decode-heavy workloads)
   3. Software decode: CPU decode -> CPU -> GL texture
      (fallback when no hardware available)

   Designed for real-time rendering:
   - Call (advance-frame! source dt) each frame to update playback
   - Call (current-frame source ctx) to get GPU-backed Skia Image
   - Draw the image with any Skia effects (rounded corners, blur, etc.)

   Note: The Skia Image shares GPU memory with the video decoder.
   Each frame update invalidates the previous image."
  (:require [lib.video.protocol :as proto]
            [lib.video.state :as state]
            [lib.video.decoder :as decoder]
            [lib.video.hwaccel.decoder :as hwdecoder]
            [lib.video.hwaccel.detect :as detect])
  (:import [java.lang.ref Cleaner]
           [io.github.humbleui.skija Canvas]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Automatic cleanup via JVM Cleaner (Java 9+)
;; ============================================================

(defonce ^:private cleaner (Cleaner/create))

;; ============================================================
;; Source record
;; ============================================================

(defrecord Source [id hwaccel-type])

(defn- get-source-data
  "Get the internal data for a source."
  [source]
  (when source
    (get @state/sources (:id source))))

(defn- get-source-meta
  "Get metadata for a source."
  [source]
  (when source
    (get @state/source-meta (:id source))))

;; ============================================================
;; Loading
;; ============================================================

(defn- try-create-zero-copy-decoder
  "Try to create a zero-copy decoder. Returns result map or nil on failure."
  [path opts]
  (try
    (let [create-fn (requiring-resolve 'lib.video.hwaccel.zero-copy/create-zero-copy-decoder)]
      (create-fn path opts))
    (catch Exception e
      (when (:debug? opts)
        (println "[video] Zero-copy decoder failed:" (.getMessage e)))
      nil)))

(defn file->video
  "Load video source from file (MP4/WebM/MOV/etc).

   Options:
     :zero-copy? - Try zero-copy decoding (default true)
                   Decoded frames stay GPU-resident, no CPU involvement.
                   Falls back to hw-accel if zero-copy unavailable.
     :hw-accel?  - Try hardware acceleration (default true)
                   GPU decodes but transfers to CPU before texture upload.
                   Falls back to software decode if unavailable.
     :decoder    - Force specific decoder (:videotoolbox, :vaapi, :nvdec-cuda, :d3d11va, :software)
     :debug?     - Enable debug logging for decoder
     :audio?     - Enable audio playback (default true)
     :audio-sync? - Sync video to audio clock (default true)

   Decoder selection cascade:
   1. If :zero-copy? true (default): Try zero-copy HW decode
   2. If zero-copy fails or :zero-copy? false: Try HW decode with CPU copy
   3. If HW decode fails or :hw-accel? false: Fall back to software decode

   Returns a Source handle. Resources are automatically released when
   the Source is garbage collected.

   Example:
     (def video (video/file->video \"movie.mp4\"))
     (video/play! video)
     ;; In your draw loop:
     (when-let [frame (video/current-frame video ctx)]
       (.drawImage canvas frame 0 0))
     (video/advance-frame! video dt)"
  ([path]
   (file->video path {}))
  ([path opts]
   (let [id (swap! state/source-counter inc)
         ;; Options with defaults
         try-zero-copy? (get opts :zero-copy? true)
         use-hwaccel? (get opts :hw-accel? true)

         ;; Try decoder cascade: zero-copy -> hwaccel -> software
         {:keys [decoder hwaccel-type fallback? zero-copy?]}
         (or
           ;; 1. Try zero-copy if enabled
           (when try-zero-copy?
             (try-create-zero-copy-decoder path opts))

           ;; 2. Try hwaccel with CPU copy
           (when use-hwaccel?
             (try
               (hwdecoder/create-hwaccel-decoder path opts)
               (catch Exception e
                 (when (:debug? opts)
                   (println "[video] HW decoder failed:" (.getMessage e)))
                 nil)))

           ;; 3. Software fallback
           {:decoder (decoder/create-software-decoder path opts)
            :hwaccel-type nil
            :fallback? true
            :zero-copy? false})

         source (->Source id hwaccel-type)]
     ;; Store decoder implementation
     (swap! state/sources assoc id decoder)
     ;; Store metadata
     (swap! state/source-meta assoc id {:hwaccel-type hwaccel-type
                                         :fallback? fallback?
                                         :zero-copy? (boolean zero-copy?)
                                         :path path})
     ;; Register automatic cleanup when Source is GC'd
     (.register cleaner source
                (fn []
                  (when-let [impl (get @state/sources id)]
                    (try
                      (proto/close* impl)
                      (catch Exception _)))
                  (swap! state/sources dissoc id)
                  (swap! state/source-meta dissoc id)))
     source)))

;; ============================================================
;; Playback control
;; ============================================================

(defn play!
  "Start or resume video playback.
   If stopped, starts from beginning.
   If paused, resumes from current position."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/play* impl))
  source)

(defn stop!
  "Stop playback and reset to beginning."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/stop* impl))
  source)

(defn pause!
  "Pause playback at current position."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/pause* impl))
  source)

(defn seek!
  "Seek to a position in seconds."
  [source seconds]
  (when-let [impl (get-source-data source)]
    (proto/seek* impl seconds))
  source)

;; ============================================================
;; Frame advancement (call each frame)
;; ============================================================

(defn advance-frame!
  "Advance playback by dt seconds and decode new frame if needed.
   Call this once per render frame.

   Returns the source."
  [source dt]
  (when-let [impl (get-source-data source)]
    (proto/advance-frame!* impl dt))
  source)

;; ============================================================
;; Frame access for rendering
;; ============================================================

(defn ensure-first-frame!
  "Ensure the first frame is decoded and ready for display.
   Call this before current-frame to show a preview.
   Must be called from the GL thread (e.g., in draw function)."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/ensure-first-frame!* impl))
  source)

(defn current-frame
  "Get the current video frame as a Skia Image.

   direct-context: The Skia DirectContext (from lib.window.layer/context)

   Returns a Skia Image backed by GPU texture, ready for drawing.
   Works with both Metal and OpenGL backends via adoptMetalTextureFrom
   and adoptGLTextureFrom respectively.

   The image supports full Skia compositing (clips, effects, masks, etc).
   The image is valid until the next frame is decoded.

   Returns nil if no frame has been decoded yet."
  [source direct-context]
  (when-let [impl (get-source-data source)]
    (proto/current-frame* impl direct-context)))

(defn draw-frame!
  "Draw a video frame to the canvas.

   Uses the unified Skia Image path for both Metal and OpenGL backends.
   Metal textures are wrapped via Image.adoptMetalTextureFrom for full
   Skia compositing support (clips, effects, etc).

   Parameters:
   - source: Video source
   - canvas: Skia Canvas to draw on
   - direct-context: Skia DirectContext
   - x, y: Position to draw
   - width, height: Target size (video is scaled to fit)

   Returns true if frame was drawn, false otherwise."
  [source canvas direct-context x y width height]
  (when-let [impl (get-source-data source)]
    ;; Ensure first frame is decoded
    (proto/ensure-first-frame!* impl)
    (when-let [frame (proto/current-frame* impl direct-context)]
      ;; Frame is always a Skia Image (Metal or OpenGL)
      (let [video-w (proto/width* impl)
            video-h (proto/height* impl)
            src-rect (Rect/makeXYWH 0 0 video-w video-h)
            dst-rect (Rect/makeXYWH x y width height)]
        (.drawImageRect ^Canvas canvas frame src-rect dst-rect)
        true))))

;; ============================================================
;; Query
;; ============================================================

(defn playing?
  "Check if the source is currently playing."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/playing?* impl)))

(defn paused?
  "Check if the source is paused."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/paused?* impl)))

(defn tell
  "Get current playback position in seconds."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/tell* impl)))

(defn duration
  "Get total duration in seconds."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/duration* impl)))

(defn width
  "Get video width in pixels."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/width* impl)))

(defn height
  "Get video height in pixels."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/height* impl)))

(defn fps
  "Get video frame rate."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/fps* impl)))

;; ============================================================
;; Hardware Acceleration Info
;; ============================================================

(defn hwaccel-type
  "Get the hardware acceleration type used by this source.
   Returns :videotoolbox, :vaapi, :nvdec-cuda, :d3d11va, or nil (software)."
  [source]
  (:hwaccel-type source))

(defn hwaccel?
  "Check if this source is using hardware acceleration."
  [source]
  (some? (hwaccel-type source)))

(defn decoder-info
  "Get detailed decoder information for a source.
   Returns {:hwaccel-type :fallback? :zero-copy? :path}."
  [source]
  (get-source-meta source))

(defn zero-copy?
  "Check if this source is using zero-copy decoding.
   Zero-copy means decoded frames stay GPU-resident."
  [source]
  (:zero-copy? (get-source-meta source)))

(defn system-info
  "Get system hardware acceleration capabilities.
   Returns {:platform :arch :gpu-vendor :decoder :available}."
  []
  (detect/decoder-info))

(defn zero-copy-info
  "Get detailed zero-copy capability information for current system.
   Returns {:platform :gpu-vendor :decoder :zero-copy-available :requirements :checks}."
  []
  (detect/zero-copy-info))

;; ============================================================
;; Audio Control
;; ============================================================

(defn has-audio?
  "Check if the video source has an audio track."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/has-audio?* impl)))

(defn set-volume!
  "Set audio volume (0.0 = silent, 1.0 = normal)."
  [source volume]
  (when-let [impl (get-source-data source)]
    (proto/set-volume!* impl volume))
  source)

(defn get-volume
  "Get current audio volume."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/get-volume* impl)))

(defn audio-position
  "Get the current audio playback position in seconds.
   This is the master clock when audio sync is enabled.
   Returns nil if no audio track."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/audio-position* impl)))

;; ============================================================
;; Resource management
;; ============================================================

(defn close!
  "Immediately close a source's resources.
   Optional - resources are auto-closed when Source is garbage collected."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/close* impl)
    (swap! state/sources dissoc (:id source))
    (swap! state/source-meta dissoc (:id source)))
  nil)

(defn cleanup!
  "Dispose of all video sources. Called on window close."
  []
  (doseq [[_id impl] @state/sources]
    (try
      (proto/close* impl)
      (catch Exception _)))
  (reset! state/sources {})
  (reset! state/source-meta {}))
