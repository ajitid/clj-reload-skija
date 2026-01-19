(ns lib.window.capture
  "Frame capture using OpenGL PBO double-buffering.
   Supports screenshots (PNG/JPEG) and video recording (FFmpeg).

   PBO double-buffering eliminates GPU stalls by reading the previous
   frame's data while the current frame is being captured asynchronously."
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io])
  (:import [org.lwjgl.opengl GL11 GL15 GL21]
           [org.lwjgl.system MemoryUtil]
           [java.nio ByteBuffer ByteOrder]
           [java.io OutputStream FileOutputStream]
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
         :primed?       false}))   ; First async read issued?

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
  (let [{:keys [pbo-ids initialized?]} @pbo-state]
    (when initialized?
      (doseq [pbo pbo-ids]
        (when (pos? pbo)
          (GL15/glDeleteBuffers pbo)))
      (swap! pbo-state assoc
             :pbo-ids [0 0]
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
  "Issue async glReadPixels into the current PBO."
  [width height]
  (let [{:keys [pbo-ids pbo-index]} @pbo-state
        current-pbo (nth pbo-ids pbo-index)]
    ;; Bind PBO and issue async read
    (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER current-pbo)
    ;; Read framebuffer into PBO (returns immediately - async DMA)
    (GL11/glReadPixels 0 0 width height
                       GL11/GL_RGBA GL11/GL_UNSIGNED_BYTE
                       0)  ; offset 0 into bound PBO
    (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER 0)))

(defn- get-previous-frame
  "Map the previous PBO and copy pixels to a ByteBuffer.
   Returns the pixel data or nil if not ready."
  [width height]
  (let [{:keys [pbo-ids pbo-index primed?]} @pbo-state]
    (when primed?
      (let [prev-index (mod (inc pbo-index) 2)
            prev-pbo (nth pbo-ids prev-index)
            size (buffer-size width height)]
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
              result)))))))

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

(defn- spawn-ffmpeg-recording!
  "Spawn FFmpeg process for video recording. Called on first frame.
   Returns true if successful, false otherwise."
  [src-width src-height]
  (let [{:keys [recording-path recording-fps recording-format recording-opts]} @capture-state
        {:keys [width height]} recording-opts
        vf (build-vf-filter {:width width :height height})
        cmd (case recording-format
              :png
              ["ffmpeg" "-y"
               "-f" "rawvideo"
               "-pix_fmt" "rgba"
               "-s" (str src-width "x" src-height)
               "-r" (str recording-fps)
               "-i" "-"
               "-vf" vf
               recording-path]

              ;; Default: MP4 video
              ["ffmpeg" "-y"
               "-f" "rawvideo"
               "-pix_fmt" "rgba"
               "-s" (str src-width "x" src-height)
               "-r" (str recording-fps)
               "-i" "-"
               "-vf" vf
               "-c:v" "libx264"
               "-preset" "fast"
               "-pix_fmt" "yuv420p"
               recording-path])]
    (try
      (let [pb (ProcessBuilder. ^java.util.List cmd)
            _ (.redirectErrorStream pb true)
            process (.start pb)
            stdin (.getOutputStream process)]
        (swap! capture-state assoc
               :ffmpeg-process process
               :ffmpeg-stdin stdin
               :recording-dims [src-width src-height]
               :recording? true)
        (println "[capture] Recording started:" recording-path
                 (if (= recording-format :png) "(PNG sequence)" "(MP4)")
                 "at" recording-fps "fps," src-width "x" src-height)
        true)
      (catch Exception e
        (println "[capture] Failed to start FFmpeg:" (.getMessage e))
        (swap! capture-state assoc :mode nil)
        false))))

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
  "Write raw RGBA pixels to FFmpeg stdin."
  [^ByteBuffer pixels width height]
  (let [{:keys [ffmpeg-stdin]} @capture-state]
    (when ffmpeg-stdin
      (try
        ;; Note: Don't flip - let FFmpeg do it with -vf vflip
        (let [bytes (byte-array (* width height 4))]
          (.get pixels bytes)
          (.write ^OutputStream ffmpeg-stdin bytes))
        (catch Exception e
          (println "[capture] FFmpeg write error:" (.getMessage e))
          (stop-recording!))
        (finally
          (MemoryUtil/memFree pixels))))))

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
  (destroy-pbos!))
