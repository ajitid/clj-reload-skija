(ns lib.video.core
  "Love2D-style video API for Clojure.

   Provides GPU-accelerated video playback with Skia effects:
   - (from-file \"video.mp4\") -> Source
   - (from-file \"video.mp4\" {:hw-accel? true}) -> Try hardware decode
   - (play! source), (stop! source), (pause! source)
   - (seek! source time-seconds)
   - (current-frame source direct-context) -> Skia Image
   - (close! source)

   Designed for real-time rendering:
   - Call (advance-frame! source dt) each frame to update playback
   - Call (current-frame source ctx) to get GPU-backed Skia Image
   - Draw the image with any Skia effects (rounded corners, blur, etc.)

   Note: The Skia Image shares GPU memory with the video decoder.
   Each frame update invalidates the previous image."
  (:require [lib.video.protocol :as proto]
            [lib.video.state :as state]
            [lib.video.decoder :as decoder])
  (:import [java.lang.ref Cleaner]))

;; ============================================================
;; Automatic cleanup via JVM Cleaner (Java 9+)
;; ============================================================

(defonce ^:private cleaner (Cleaner/create))

;; ============================================================
;; Source record
;; ============================================================

(defrecord Source [id])

(defn- get-source-data
  "Get the internal data for a source."
  [source]
  (when source
    (get @state/sources (:id source))))

;; ============================================================
;; Loading
;; ============================================================

(defn from-file
  "Load video source from file (MP4/WebM/MOV/etc).

   Options:
     :hw-accel? - Try hardware acceleration (VideoToolbox/VAAPI/NVDEC)

   Returns a Source handle. Resources are automatically released when
   the Source is garbage collected.

   Example:
     (def video (video/from-file \"movie.mp4\"))
     (video/play! video)
     ;; In your draw loop:
     (when-let [frame (video/current-frame video ctx)]
       (.drawImage canvas frame 0 0))
     (video/advance-frame! video dt)"
  ([path]
   (from-file path {}))
  ([path opts]
   (let [id (swap! state/source-counter inc)
         source-impl (decoder/create-software-decoder path opts)
         source (->Source id)]
     (swap! state/sources assoc id source-impl)
     ;; Register automatic cleanup when Source is GC'd
     (.register cleaner source
                (fn []
                  (when-let [impl (get @state/sources id)]
                    (try
                      (proto/close* impl)
                      (catch Exception _)))
                  (swap! state/sources dissoc id)))
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
   The image is valid until the next frame is decoded.

   Returns nil if no frame has been decoded yet."
  [source direct-context]
  (when-let [impl (get-source-data source)]
    (proto/current-frame* impl direct-context)))

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
;; Resource management
;; ============================================================

(defn close!
  "Immediately close a source's resources.
   Optional - resources are auto-closed when Source is garbage collected."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/close* impl)
    (swap! state/sources dissoc (:id source)))
  nil)

(defn cleanup!
  "Dispose of all video sources. Called on window close."
  []
  (doseq [[_id impl] @state/sources]
    (try
      (proto/close* impl)
      (catch Exception _)))
  (reset! state/sources {}))
