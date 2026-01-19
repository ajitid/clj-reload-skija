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
         :screenshot-path   nil    ; Output path for screenshot
         :screenshot-format nil    ; :png | :jpeg
         :ffmpeg-process    nil    ; FFmpeg Process object
         :ffmpeg-stdin      nil    ; FFmpeg stdin OutputStream
         :recording?        false}))

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
  [width height]
  (let [{:keys [width w height h initialized?]} @pbo-state]
    (when (and initialized?
               (or (not= width w) (not= height h)))
      (destroy-pbos!)
      (init-pbos! width height))))

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
;; Screenshot API
;; ============================================================

(defn- save-screenshot!
  "Save pixels as PNG or JPEG."
  [^ByteBuffer pixels width height path format]
  (try
    (let [image (pixels->image pixels width height)
          fmt (case format
                :png  EncodedImageFormat/PNG
                :jpeg EncodedImageFormat/JPEG
                EncodedImageFormat/PNG)
          quality (case format
                    :jpeg 90
                    100)
          data (.encodeToData image fmt quality)]
      (when data
        (with-open [os (FileOutputStream. (io/file path))]
          (.write os (.getBytes data)))
        (println "[capture] Screenshot saved:" path))
      (.close image))
    (catch Exception e
      (println "[capture] Screenshot error:" (.getMessage e)))
    (finally
      (MemoryUtil/memFree pixels))))

(defn screenshot!
  "Request a screenshot to be saved on the next frame.
   Format: :png or :jpeg"
  [path format]
  (swap! capture-state assoc
         :mode :screenshot
         :screenshot-path path
         :screenshot-format (or format :png))
  (println "[capture] Screenshot requested:" path))

;; ============================================================
;; Video Recording API
;; ============================================================

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

(defn start-recording!
  "Start video recording to the given path.
   Options:
     :fps    - Frame rate (default 60)
     :width  - Override width (default: current framebuffer)
     :height - Override height (default: current framebuffer)"
  [path {:keys [fps] :or {fps 60}}]
  (check-ffmpeg!)
  (let [{:keys [width height]} @pbo-state
        cmd ["ffmpeg" "-y"
             "-f" "rawvideo"
             "-pix_fmt" "rgba"
             "-s" (str width "x" height)
             "-r" (str fps)
             "-i" "-"
             "-vf" "vflip"
             "-c:v" "libx264"
             "-preset" "fast"
             "-pix_fmt" "yuv420p"
             path]
        pb (ProcessBuilder. ^java.util.List cmd)
        _ (.redirectErrorStream pb true)
        process (.start pb)
        stdin (.getOutputStream process)]
    (swap! capture-state assoc
           :mode :video
           :ffmpeg-process process
           :ffmpeg-stdin stdin
           :recording? true)
    (println "[capture] Recording started:" path "at" fps "fps")))

(defn stop-recording!
  "Stop video recording."
  []
  (let [{:keys [ffmpeg-process ffmpeg-stdin recording?]} @capture-state]
    (when recording?
      (try
        (when ffmpeg-stdin
          (.close ^OutputStream ffmpeg-stdin))
        (when ffmpeg-process
          (.waitFor ^Process ffmpeg-process))
        (println "[capture] Recording stopped")
        (catch Exception e
          (println "[capture] Error stopping recording:" (.getMessage e))))
      (swap! capture-state assoc
             :mode nil
             :ffmpeg-process nil
             :ffmpeg-stdin nil
             :recording? false))))

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
            (let [{:keys [screenshot-path screenshot-format]} @capture-state]
              (save-screenshot! pixels width height screenshot-path screenshot-format)
              (swap! capture-state assoc :mode nil))

            :video
            (write-frame-to-ffmpeg! pixels width height)

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
