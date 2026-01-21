(ns lib.window.capture
  "Frame capture using OpenGL PBO double-buffering.
   Supports screenshots (PNG/JPEG) and video recording (FFmpeg).

   PBO double-buffering eliminates GPU stalls by reading the previous
   frame's data while the current frame is being captured asynchronously.
   
   Performance optimizations:
   - Hardware encoder selection (VideoToolbox/VAAPI/NVENC)
   - glFenceSync for non-blocking PBO reads
   - Worker thread with bounded queue for FFmpeg writes"
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [org.lwjgl.opengl GL11 GL15 GL21 GL32]
           [org.lwjgl.system MemoryUtil]
           [java.nio ByteBuffer ByteOrder]
           [java.io OutputStream FileOutputStream]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]
           [io.github.humbleui.skija Image ImageInfo ColorAlphaType ColorType
            EncodedImageFormat Data Bitmap]))

;; ============================================================
;; PBO State (persists across hot-reloads)
;; ============================================================

(defonce pbo-state
  (atom {:pbo-ids       [0 0]      ; Double-buffered PBO handles
         :pbo-index     0          ; Current PBO index (ping-pong)
         :width         0          ; Current framebuffer width
         :height        0          ; Current framebuffer height
         :initialized?  false      ; PBOs created?
         :primed?       false      ; First async read issued?
         :fences        [nil nil]})) ; Fence sync objects per PBO

(defonce capture-state
  (atom {:mode              nil    ; :screenshot | :video | nil
         ;; Screenshot state
         :screenshot-path   nil    ; Output path for screenshot
         :screenshot-format nil    ; :png | :jpeg
         :screenshot-opts   nil    ; Scaling options
         ;; Video recording state
         :recording-path    nil    ; Output path for video
         :recording-fps     nil    ; Frame rate
         :recording-format  nil    ; :mp4 | :png
         :recording-opts    nil    ; Scaling options {:width :height}
         :recording-dims    nil    ; [width height] FFmpeg was started with
         ;; FFmpeg process (shared)
         :ffmpeg-process    nil    ; FFmpeg Process object
         :ffmpeg-stdin      nil    ; FFmpeg stdin OutputStream
         :recording?        false})) ; FFmpeg actually running?

;; Worker thread state for async FFmpeg writes
(defonce worker-state
  (atom {:queue    nil       ; LinkedBlockingQueue for frame data
         :thread   nil       ; Worker Thread
         :running? false}))  ; Worker thread active?

;; ============================================================
;; Core Integration (zero-overhead when inactive)
;; ============================================================

(defn- set-capture-active!
  "Set the capture-active? flag in core.clj.
   This enables the capture hook in the render loop.
   Once enabled, stays enabled (namespace is loaded anyway)."
  []
  (when-let [flag (resolve 'lib.window.core/capture-active?)]
    (reset! @flag true)))

;; ============================================================
;; PBO Management
;; ============================================================

(defn- buffer-size
  "Calculate buffer size for RGBA pixels."
  [width height]
  (* width height 4))

(defn init-pbos!
  "Initialize double-buffered PBOs for the given dimensions."
  [width height]
  (when (and (pos? width) (pos? height))
    (let [size (buffer-size width height)
          pbo-a (GL15/glGenBuffers)
          pbo-b (GL15/glGenBuffers)]
      ;; Initialize PBO-A
      (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER pbo-a)
      (GL15/glBufferData GL21/GL_PIXEL_PACK_BUFFER size GL15/GL_STREAM_READ)
      ;; Initialize PBO-B
      (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER pbo-b)
      (GL15/glBufferData GL21/GL_PIXEL_PACK_BUFFER size GL15/GL_STREAM_READ)
      ;; Unbind
      (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER 0)

      (swap! pbo-state assoc
             :pbo-ids [pbo-a pbo-b]
             :width width
             :height height
             :initialized? true
             :primed? false)
      (println "[capture] PBOs initialized:" width "x" height))))

(defn destroy-pbos!
  "Delete PBOs and reset state."
  []
  (let [{:keys [pbo-ids fences initialized?]} @pbo-state]
    (when initialized?
      ;; Delete fences
      (doseq [fence fences]
        (when fence
          (GL32/glDeleteSync fence)))
      ;; Delete PBOs
      (doseq [pbo pbo-ids]
        (when (pos? pbo)
          (GL15/glDeleteBuffers pbo)))
      (swap! pbo-state assoc
             :pbo-ids [0 0]
             :fences [nil nil]
             :initialized? false
             :primed? false)
      (println "[capture] PBOs destroyed"))))

(defn- resize-pbos!
  "Recreate PBOs if dimensions changed."
  [new-width new-height]
  (let [{:keys [width height initialized?]} @pbo-state]
    (when (and initialized?
               (or (not= new-width width) (not= new-height height)))
      (destroy-pbos!)
      (init-pbos! new-width new-height))))

(defn- ensure-pbos!
  "Ensure PBOs exist and match dimensions."
  [width height]
  (let [{:keys [initialized?]} @pbo-state]
    (if initialized?
      (resize-pbos! width height)
      (init-pbos! width height))))

(defn- start-async-read!
  "Issue async glReadPixels into the current PBO and create a fence."
  [width height]
  (let [{:keys [pbo-ids pbo-index fences]} @pbo-state
        current-pbo (nth pbo-ids pbo-index)
        old-fence (nth fences pbo-index)]
    ;; Delete old fence if exists
    (when old-fence
      (GL32/glDeleteSync old-fence))
    ;; Bind PBO and issue async read
    (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER current-pbo)
    ;; Read framebuffer into PBO (returns immediately - async DMA)
    (GL11/glReadPixels 0 0 width height
                       GL11/GL_RGBA GL11/GL_UNSIGNED_BYTE
                       0)  ; offset 0 into bound PBO
    (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER 0)
    ;; Create fence to track when GPU transfer completes
    (let [new-fence (GL32/glFenceSync GL32/GL_SYNC_GPU_COMMANDS_COMPLETE 0)]
      (swap! pbo-state assoc-in [:fences pbo-index] new-fence))))

(defn- get-previous-frame
  "Map the previous PBO and copy pixels to a ByteBuffer.
   Returns the pixel data or nil if not ready (fence not signaled)."
  [width height]
  (let [{:keys [pbo-ids pbo-index fences primed?]} @pbo-state]
    (when primed?
      (let [prev-index (mod (inc pbo-index) 2)
            prev-pbo (nth pbo-ids prev-index)
            prev-fence (nth fences prev-index)
            size (buffer-size width height)]
        ;; Check fence before mapping (non-blocking)
        ;; If fence isn't signaled yet, GPU transfer is still in progress
        (when (or (nil? prev-fence)
                  (let [status (GL32/glClientWaitSync prev-fence 0 0)]
                    (or (= status GL32/GL_ALREADY_SIGNALED)
                        (= status GL32/GL_CONDITION_SATISFIED))))
          ;; Bind previous PBO for reading
          (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER prev-pbo)
          ;; Map buffer to CPU memory
          (let [mapped (GL15/glMapBuffer GL21/GL_PIXEL_PACK_BUFFER GL15/GL_READ_ONLY)]
            (when mapped
              ;; Copy data to our own buffer (so we can unmap immediately)
              (let [result (MemoryUtil/memAlloc size)]
                (.put result (.asReadOnlyBuffer mapped))
                (.flip result)
                ;; Unmap PBO
                (GL15/glUnmapBuffer GL21/GL_PIXEL_PACK_BUFFER)
                (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER 0)
                result))))))))

(defn- flip-pbo!
  "Switch to the other PBO for the next frame."
  []
  (swap! pbo-state update :pbo-index #(mod (inc %) 2)))

(defn- mark-primed!
  "Mark that we've issued at least one async read."
  []
  (swap! pbo-state assoc :primed? true))

;; ============================================================
;; Pixel Processing
;; ============================================================

(defn- flip-vertical!
  "Flip pixel buffer vertically in-place (OpenGL is bottom-up).
   Returns the same buffer, flipped."
  [^ByteBuffer pixels width height]
  (let [row-bytes (* width 4)
        temp (byte-array row-bytes)]
    (dotimes [y (quot height 2)]
      (let [top-offset (* y row-bytes)
            bot-offset (* (- height 1 y) row-bytes)]
        ;; Save top row
        (.position pixels top-offset)
        (.get pixels temp)
        ;; Copy bottom to top
        (.position pixels top-offset)
        (let [bot-slice (.slice (.position pixels bot-offset))]
          (.limit bot-slice row-bytes)
          (.put pixels bot-slice))
        ;; Copy saved top to bottom
        (.position pixels bot-offset)
        (.put pixels temp)))
    (.rewind pixels)
    pixels))

(defn- pixels->image
  "Convert raw RGBA pixels to Skija Image."
  [^ByteBuffer pixels width height]
  ;; Flip vertically (OpenGL is bottom-up)
  (flip-vertical! pixels width height)
  ;; Create Skija Image via Bitmap
  (let [row-bytes (* width 4)
        bytes (byte-array (* width height 4))
        _ (.get pixels bytes)
        _ (.rewind pixels)
        info (ImageInfo. width height ColorType/RGBA_8888 ColorAlphaType/UNPREMUL)
        bitmap (Bitmap.)]
    (.allocPixels bitmap info)
    (.installPixels bitmap info bytes row-bytes)
    (Image/makeFromBitmap (.setImmutable bitmap))))

;; ============================================================
;; Hardware Encoder Detection
;; ============================================================

(defn- get-hw-encoder
  "Detect platform and return appropriate hardware encoder.
   Falls back to libx264 if platform is unknown."
  []
  (let [os (System/getProperty "os.name")]
    (cond
      (str/includes? os "Mac")   "h264_videotoolbox"
      (str/includes? os "Linux") "h264_vaapi"
      (str/includes? os "Win")   "h264_nvenc"
      :else                      "libx264")))

(defn- get-encoder-args
  "Get platform-specific FFmpeg encoder arguments.
   Returns [encoder-args vf-extra] where vf-extra is prepended to vf filter."
  [encoder]
  (case encoder
    "h264_videotoolbox" [["-c:v" "h264_videotoolbox"] nil]
    "h264_vaapi"        [["-vaapi_device" "/dev/dri/renderD128"
                          "-c:v" "h264_vaapi"] "format=nv12,hwupload,"]
    "h264_nvenc"        [["-c:v" "h264_nvenc" "-preset" "p4"] nil]
    "h264_amf"          [["-c:v" "h264_amf" "-quality" "balanced"] nil]
    ;; Fallback: libx264
    [["-c:v" "libx264" "-preset" "fast"] nil]))

;; ============================================================
;; Worker Thread for Async FFmpeg Writes
;; ============================================================

(def ^:private queue-capacity 4)  ; ~32MB at 1080p RGBA

(defn- write-frame-data!
  "Write frame data to FFmpeg stdin. Called from worker thread."
  [{:keys [pixels width height]}]
  (let [{:keys [ffmpeg-stdin]} @capture-state]
    (when ffmpeg-stdin
      (try
        (let [bytes (byte-array (* width height 4))]
          (.get ^ByteBuffer pixels bytes)
          (.write ^OutputStream ffmpeg-stdin bytes))
        (catch Exception e
          (println "[capture] FFmpeg write error:" (.getMessage e)))
        (finally
          (MemoryUtil/memFree pixels))))))

(defn- worker-loop
  "Worker thread main loop. Polls queue and writes frames to FFmpeg."
  []
  (let [{:keys [queue]} @worker-state]
    (while (:running? @worker-state)
      (try
        (when-let [frame (.poll ^LinkedBlockingQueue queue 100 TimeUnit/MILLISECONDS)]
          (write-frame-data! frame))
        (catch InterruptedException _
          ;; Thread interrupted, exit gracefully
          nil)
        (catch Exception e
          (println "[capture] Worker error:" (.getMessage e)))))
    ;; Drain remaining frames on shutdown
    (let [remaining (java.util.ArrayList.)]
      (.drainTo ^LinkedBlockingQueue queue remaining)
      (doseq [frame remaining]
        (write-frame-data! frame)))))

(defn- start-worker!
  "Start the worker thread for async FFmpeg writes."
  []
  (when-not (:running? @worker-state)
    (let [queue (LinkedBlockingQueue. ^int queue-capacity)
          thread (Thread. ^Runnable worker-loop "capture-worker")]
      (swap! worker-state assoc
             :queue queue
             :thread thread
             :running? true)
      (.start thread)
      (println "[capture] Worker thread started"))))

(defn- stop-worker!
  "Stop the worker thread and wait for it to finish."
  []
  (when (:running? @worker-state)
    (swap! worker-state assoc :running? false)
    (when-let [thread (:thread @worker-state)]
      (try
        (.join ^Thread thread 5000)  ; Wait up to 5 seconds
        (when (.isAlive ^Thread thread)
          (.interrupt ^Thread thread)
          (println "[capture] Worker thread interrupted"))
        (catch Exception e
          (println "[capture] Error stopping worker:" (.getMessage e)))))
    (swap! worker-state assoc :queue nil :thread nil)
    (println "[capture] Worker thread stopped")))

(defn- queue-frame!
  "Queue a frame for async writing. Returns :queued or :dropped."
  [pixels width height]
  (let [{:keys [queue running?]} @worker-state]
    (if (and queue running?)
      (if (.offer ^LinkedBlockingQueue queue {:pixels pixels :width width :height height})
        :queued
        (do
          ;; Queue full, drop frame (backpressure)
          (MemoryUtil/memFree pixels)
          :dropped))
      (do
        ;; Worker not running, free pixels
        (MemoryUtil/memFree pixels)
        :dropped))))

;; ============================================================
;; FFmpeg Helpers
;; ============================================================

(defn- build-vf-filter
  "Build FFmpeg -vf filter string with optional scaling.
   Preserves aspect ratio in all cases."
  [{:keys [width height]}]
  (cond
    ;; Both: fit within bounds, pad to exact size with black bars
    (and width height)
    (str "vflip,scale=" width ":" height
         ":force_original_aspect_ratio=decrease,"
         "pad=" width ":" height ":(ow-iw)/2:(oh-ih)/2")

    ;; Width only: scale to width, auto height (preserve aspect ratio)
    width
    (str "vflip,scale=" width ":-2")

    ;; Height only: scale to height, auto width (preserve aspect ratio)
    height
    (str "vflip,scale=-2:" height)

    ;; Neither: just flip
    :else
    "vflip"))

(defn- check-ffmpeg!
  "Verify FFmpeg is available in PATH."
  []
  (let [cmd (if (= (System/getProperty "os.name") "Mac OS X")
              ["which" "ffmpeg"]
              ["where" "ffmpeg"])
        result (apply shell/sh cmd)]
    (when (not= 0 (:exit result))
      (throw (ex-info "FFmpeg not found in PATH. Install FFmpeg to enable video recording."
                      {:exit (:exit result)
                       :err (:err result)})))))

;; ============================================================
;; Screenshot API
;; ============================================================

(defn- save-screenshot!
  "Save pixels as PNG or JPEG using FFmpeg (supports scaling)."
  [^ByteBuffer pixels src-width src-height path format opts]
  (try
    (let [{:keys [width height]} opts
          vf (build-vf-filter {:width width :height height})
          fmt-args (case format
                     :jpeg ["-q:v" "2"]  ;; High quality JPEG
                     [])                  ;; PNG needs no extra args
          cmd (into ["ffmpeg" "-y"
                     "-f" "rawvideo"
                     "-pix_fmt" "rgba"
                     "-s" (str src-width "x" src-height)
                     "-i" "-"
                     "-vf" vf
                     "-frames:v" "1"]
                    (concat fmt-args [path]))
          pb (ProcessBuilder. ^java.util.List cmd)
          _ (.redirectErrorStream pb true)
          process (.start pb)
          stdin (.getOutputStream process)
          bytes (byte-array (* src-width src-height 4))]
      (.get pixels bytes)
      (.write stdin bytes)
      (.close stdin)
      (.waitFor process)
      (if (zero? (.exitValue process))
        (println "[capture] Screenshot saved:" path)
        (println "[capture] Screenshot failed, FFmpeg exit code:" (.exitValue process))))
    (catch Exception e
      (println "[capture] Screenshot error:" (.getMessage e)))
    (finally
      (MemoryUtil/memFree pixels))))

(defn screenshot!
  "Request a screenshot to be saved on the next frame.
   Format: :png or :jpeg
   Options:
     :width  - Scale to this width, preserve aspect ratio (auto height)
     :height - Scale to this height, preserve aspect ratio (auto width)
               If both specified, fits within bounds with black bars.

   Examples:
     (screenshot! \"out.png\" :png)                            ; native size
     (screenshot! \"out.png\" :png {:width 1920})              ; scale to 1920 wide
     (screenshot! \"out.jpg\" :jpeg {:height 1080})            ; scale to 1080 tall
     (screenshot! \"out.png\" :png {:width 1920 :height 1080}) ; fit in 1920x1080"
  ([path format] (screenshot! path format {}))
  ([path format opts]
   (if (= :video (:mode @capture-state))
     (println "[capture] WARNING: Cannot take screenshot while recording is in progress")
     (do
       (check-ffmpeg!)
       (swap! capture-state assoc
              :mode :screenshot
              :screenshot-path path
              :screenshot-format (or format :png)
              :screenshot-opts opts)
       (set-capture-active!)
       (println "[capture] Screenshot requested:" path)))))

;; ============================================================
;; Video Recording API
;; ============================================================

(defn- try-encoder!
  "Helper function to try starting FFmpeg with a specific encoder.
   Returns process and stdin on success, nil on failure."
  [src-width src-height base-vf encoder recording-path recording-fps recording-format]
  (try
    (let [[encoder-args vf-extra] (get-encoder-args encoder)
          vf (if vf-extra (str vf-extra base-vf) base-vf)
          cmd (case recording-format
                :png
                ["ffmpeg" "-y"
                 "-f" "rawvideo"
                 "-pix_fmt" "rgba"
                 "-s" (str src-width "x" src-height)
                 "-r" (str recording-fps)
                 "-i" "-"
                 "-vf" base-vf
                 recording-path]

                ;; Default: MP4 video with hardware encoder
                (vec (concat
                       ["ffmpeg" "-y"
                        "-f" "rawvideo"
                        "-pix_fmt" "rgba"
                        "-s" (str src-width "x" src-height)
                        "-r" (str recording-fps)
                        "-i" "-"]
                       (when (= encoder "h264_vaapi")
                         ["-vaapi_device" "/dev/dri/renderD128"])
                       ["-vf" vf]
                       (if (= encoder "h264_vaapi")
                         ["-c:v" "h264_vaapi"]
                         encoder-args)
                       ["-pix_fmt" "yuv420p"
                        recording-path])))
          pb (ProcessBuilder. ^java.util.List cmd)
          _ (.redirectErrorStream pb true)
          process (.start pb)
          stdin (.getOutputStream process)]
      {:process process :stdin stdin})
    (catch Exception e
      (println "[capture] Encoder" encoder "failed:" (.getMessage e))
      nil)))

(defn- spawn-ffmpeg-recording!
  "Spawn FFmpeg process for video recording. Called on first frame.
   Uses hardware encoder when available, falls back to libx264.
   On Windows: tries NVENC → AMF → libx264.
   Returns true if successful, false otherwise."
  [src-width src-height]
  (let [{:keys [recording-path recording-fps recording-format recording-opts]} @capture-state
        {:keys [width height]} recording-opts
        base-vf (build-vf-filter {:width width :height height})
        os (System/getProperty "os.name")
        is-windows? (str/includes? os "Win")
        ;; Define encoder cascade based on platform
        encoders (if is-windows?
                   ["h264_nvenc" "h264_amf" "libx264"]  ; Windows: NVENC → AMF → libx264
                   [(get-hw-encoder) "libx264"])]       ; Other platforms: default → libx264
    ;; Start worker thread before FFmpeg
    (start-worker!)
    ;; Try encoders in order until one succeeds
    (loop [encoders-to-try encoders]
      (if (empty? encoders-to-try)
        ;; All encoders failed
        (do
          (println "[capture] All encoders failed!")
          (stop-worker!)
          (swap! capture-state assoc :mode nil)
          false)
        ;; Try next encoder
        (let [encoder (first encoders-to-try)
              result (try-encoder! src-width src-height base-vf encoder
                                   recording-path recording-fps recording-format)]
          (if result
            ;; Success!
            (let [{:keys [process stdin]} result]
              (swap! capture-state assoc
                     :ffmpeg-process process
                     :ffmpeg-stdin stdin
                     :recording-dims [src-width src-height]
                     :recording? true)
              (println "[capture] Recording started:" recording-path
                       (if (= recording-format :png) "(PNG sequence)" "(MP4)")
                       "at" recording-fps "fps," src-width "x" src-height
                       "encoder:" encoder)
              true)
            ;; Failed, try next encoder
            (recur (rest encoders-to-try))))))))

(defn start-recording!
  "Start recording frames to the given path.
   Recording begins on the next rendered frame (dimensions determined automatically).

   Options:
     :fps    - Frame rate (default 60)
     :format - Output format: :mp4 (default) or :png (lossless image sequence)
     :width  - Scale to this width, preserve aspect ratio (auto height)
     :height - Scale to this height, preserve aspect ratio (auto width)
               If both width and height specified, fits within bounds with black bars.

   For :png format, path should include %d or %04d for frame numbers:
     (start-recording! \"frames/frame-%04d.png\" {:format :png})

   Examples:
     (start-recording! \"out.mp4\" {:fps 60})                      ; native size
     (start-recording! \"out.mp4\" {:fps 60 :width 1920})          ; scale to 1920 wide
     (start-recording! \"out.mp4\" {:fps 60 :height 1080})         ; scale to 1080 tall
     (start-recording! \"out.mp4\" {:fps 60 :width 1920 :height 1080}) ; fit in 1920x1080"
  [path {:keys [fps format width height] :or {fps 60 format :mp4} :as opts}]
  (let [{:keys [mode]} @capture-state]
    (cond
      (= mode :video)
      (println "[capture] WARNING: Recording already in progress")

      (= mode :screenshot)
      (println "[capture] WARNING: Cannot start recording while screenshot is pending")

      :else
      (do
        (check-ffmpeg!)
        ;; Just set state - FFmpeg will be spawned on first frame in process-frame!
        (swap! capture-state assoc
               :mode :video
               :recording-path path
               :recording-fps fps
               :recording-format format
               :recording-opts {:width width :height height}
               :recording-dims nil
               :recording? false)  ; Not actually recording until FFmpeg starts
        (set-capture-active!)
        (println "[capture] Recording requested:" path
                 "(will start on next frame)"))))))

(defn stop-recording!
  "Stop video recording."
  ([] (stop-recording! nil))
  ([reason]
   (let [{:keys [ffmpeg-process ffmpeg-stdin recording? mode]} @capture-state]
     ;; Stop if actually recording OR if mode is :video (requested but not started)
     (if (or recording? (= mode :video))
       (do
         ;; Stop worker thread first (drains remaining frames)
         (stop-worker!)
         (try
           (when ffmpeg-stdin
             (.close ^OutputStream ffmpeg-stdin))
           (when ffmpeg-process
             (.waitFor ^Process ffmpeg-process))
           (if reason
             (println "[capture] Recording stopped:" reason)
             (println "[capture] Recording stopped"))
           (catch Exception e
             (println "[capture] Error stopping recording:" (.getMessage e))))
         (swap! capture-state assoc
                :mode nil
                :recording-path nil
                :recording-fps nil
                :recording-format nil
                :recording-opts nil
                :recording-dims nil
                :ffmpeg-process nil
                :ffmpeg-stdin nil
                :recording? false))
       (println "[capture] Nothing to stop (no recording in progress)")))))

(defn recording?
  "Check if currently recording."
  []
  (:recording? @capture-state))

(defn- write-frame-to-ffmpeg!
  "Queue raw RGBA pixels for async writing to FFmpeg.
   Frame ownership: render thread allocates, worker thread frees."
  [^ByteBuffer pixels width height]
  (let [result (queue-frame! pixels width height)]
    (when (= result :dropped)
      (println "[capture] Frame dropped (queue full)"))))

;; ============================================================
;; Main Frame Processing Hook
;; ============================================================

(defn process-frame!
  "Process frame capture. Call after flush, before swap.
   This is the main hook called from the render loop."
  [width height]
  (let [{:keys [mode]} @capture-state]
    (when mode
      ;; Ensure PBOs are ready
      (ensure-pbos! width height)

      (let [{:keys [primed?]} @pbo-state]
        ;; Get previous frame's pixels (if available)
        (when-let [pixels (get-previous-frame width height)]
          (case mode
            :screenshot
            (let [{:keys [screenshot-path screenshot-format screenshot-opts]} @capture-state]
              (save-screenshot! pixels width height screenshot-path screenshot-format screenshot-opts)
              (swap! capture-state assoc :mode nil))

            :video
            (let [{:keys [recording? recording-dims]} @capture-state]
              (cond
                ;; First frame: spawn FFmpeg with actual dimensions
                (not recording?)
                (if (spawn-ffmpeg-recording! width height)
                  (write-frame-to-ffmpeg! pixels width height)
                  ;; FFmpeg failed to start, free pixels
                  (MemoryUtil/memFree pixels))

                ;; Resize detected: stop recording with warning
                (not= recording-dims [width height])
                (do
                  (println "[capture] WARNING: Window resized during recording"
                           recording-dims "->" [width height])
                  (MemoryUtil/memFree pixels)
                  (stop-recording! "window resized"))

                ;; Normal frame: write to FFmpeg
                :else
                (write-frame-to-ffmpeg! pixels width height)))

            nil))

        ;; Start async read for current frame
        (start-async-read! width height)

        ;; Mark as primed after first read
        (when-not primed?
          (mark-primed!))

        ;; Flip to other PBO for next frame
        (flip-pbo!)))))

;; ============================================================
;; Cleanup
;; ============================================================

(defn cleanup!
  "Clean up all capture resources."
  []
  (stop-recording!)
  (stop-worker!)
  (destroy-pbos!))
