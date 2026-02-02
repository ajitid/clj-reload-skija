(ns lib.window.capture-metal
  "Metal frame capture using double-buffered texture reads.

   Captures frames from Metal drawable textures for screenshots and video
   recording. Uses double-buffering to minimize pipeline stalls:
   - Frame N: Read previous frame's pixels while issuing new capture
   - Frame N+1: Process captured pixels from frame N

   Unlike the OpenGL PBO path which uses async DMA, Metal capture is
   semi-synchronous: we wait for GPU completion before reading texture,
   but the processing of captured pixels happens in parallel with the
   next frame's rendering.

   IMPORTANT: Metal capture must happen BEFORE present, as the drawable's
   texture becomes invalid after presentation."
  (:require [lib.window.metal :as metal]
            [lib.window.layer-metal :as layer-metal])
  (:import [org.lwjgl.system MemoryUtil]
           [java.nio ByteBuffer]))

;; ============================================================
;; State (persists across hot-reloads)
;; ============================================================

(defonce capture-state
  (atom {:buffers       [nil nil]    ; Two native ByteBuffer pointers
         :buffer-index  0            ; Current write target (ping-pong)
         :width         0            ; Captured frame width
         :height        0            ; Captured frame height
         :bytes-per-row 0            ; Row stride
         :initialized?  false
         :primed?       false        ; At least one frame captured?
         :ready-flags   [false false] ; Which buffers have valid data?
         :cmd-buffers   [nil nil]})) ; Command buffers for completion tracking

;; ============================================================
;; Buffer Management
;; ============================================================

(defn- buffer-size
  "Calculate buffer size for BGRA pixels (Metal uses BGRA by default)."
  [width height]
  (* width height 4))

(defn init-buffers!
  "Initialize double-buffered native memory for capture."
  [width height]
  (when (and (pos? width) (pos? height))
    (let [size (buffer-size width height)
          bytes-per-row (* width 4)
          ;; Allocate two native buffers
          buf-a (MemoryUtil/memAlloc size)
          buf-b (MemoryUtil/memAlloc size)]
      (swap! capture-state assoc
             :buffers [buf-a buf-b]
             :buffer-index 0
             :width width
             :height height
             :bytes-per-row bytes-per-row
             :initialized? true
             :primed? false
             :ready-flags [false false]
             :cmd-buffers [nil nil])
      (println "[capture-metal] Buffers initialized:" width "x" height))))

(defn destroy-buffers!
  "Free native buffers and reset state."
  []
  (let [{:keys [buffers initialized?]} @capture-state]
    (when initialized?
      ;; Free native memory
      (doseq [buf buffers]
        (when buf
          (MemoryUtil/memFree buf)))
      (swap! capture-state assoc
             :buffers [nil nil]
             :initialized? false
             :primed? false
             :ready-flags [false false]
             :cmd-buffers [nil nil])
      (println "[capture-metal] Buffers destroyed"))))

(defn- resize-buffers!
  "Recreate buffers if dimensions changed."
  [new-width new-height]
  (let [{:keys [width height initialized?]} @capture-state]
    (when (and initialized?
               (or (not= new-width width) (not= new-height height)))
      (destroy-buffers!)
      (init-buffers! new-width new-height))))

(defn- ensure-buffers!
  "Ensure buffers exist and match dimensions."
  [width height]
  (let [{:keys [initialized?]} @capture-state]
    (if initialized?
      (resize-buffers! width height)
      (init-buffers! width height))))

;; ============================================================
;; Frame Capture
;; ============================================================

(defn capture-texture!
  "Capture texture contents to current buffer.
   Called AFTER Skia flush but BEFORE present (texture still valid).

   Uses synchronous texture read via Metal's getBytes method.
   GPU must be idle for this to return correct data, so we
   wait for the render command buffer to complete first.

   Parameters:
   - texture: MTLTexture pointer from drawable
   - cmd-buffer: Command buffer that was used for rendering (to wait on)
   - width, height: Frame dimensions

   Returns true if capture succeeded."
  [texture cmd-buffer width height]
  (when (and texture (pos? texture)
             (pos? width) (pos? height))
    (ensure-buffers! width height)
    (let [{:keys [buffers buffer-index bytes-per-row]} @capture-state
          current-buf (nth buffers buffer-index)]
      (when current-buf
        ;; Wait for GPU to finish rendering
        (when cmd-buffer
          (metal/wait-until-completed! cmd-buffer))
        ;; Read texture pixels into buffer
        ;; Metal textures are BGRA (we'll convert later if needed)
        (let [dest-ptr (MemoryUtil/memAddress current-buf)]
          (when (metal/get-texture-bytes! texture dest-ptr bytes-per-row width height)
            ;; Mark buffer as having valid data
            (swap! capture-state assoc-in [:ready-flags buffer-index] true)
            true))))))

(defn get-previous-frame
  "Get pixels from previous buffer if ready.
   Returns ByteBuffer with BGRA pixels or nil. Non-blocking.

   The returned buffer is a COPY that the caller owns and must free
   with MemoryUtil/memFree when done."
  [width height]
  (let [{:keys [buffers buffer-index ready-flags primed?]} @capture-state]
    (when primed?
      (let [prev-index (mod (inc buffer-index) 2)
            prev-ready? (nth ready-flags prev-index)]
        (when prev-ready?
          (let [prev-buf (nth buffers prev-index)
                size (buffer-size width height)]
            (when prev-buf
              ;; Copy to new buffer so caller can free independently
              (.rewind ^ByteBuffer prev-buf)
              (let [result (MemoryUtil/memAlloc size)]
                (.put ^ByteBuffer result ^ByteBuffer prev-buf)
                (.flip ^ByteBuffer result)
                (.rewind ^ByteBuffer prev-buf)
                result))))))))

(defn flip-buffer!
  "Switch to other buffer for next frame."
  []
  (swap! capture-state update :buffer-index #(mod (inc %) 2)))

(defn mark-primed!
  "Mark that we've issued at least one capture."
  []
  (swap! capture-state assoc :primed? true))

(defn primed?
  "Check if at least one frame has been captured."
  []
  (:primed? @capture-state))

;; ============================================================
;; Pixel Format Conversion
;; ============================================================

(defn bgra->rgba!
  "Convert BGRA pixels to RGBA in-place.
   Metal uses BGRA by default, FFmpeg expects RGBA."
  [^ByteBuffer pixels width height]
  (let [num-pixels (* width height)]
    (dotimes [i num-pixels]
      (let [offset (* i 4)
            b (.get pixels (+ offset 0))
            g (.get pixels (+ offset 1))
            r (.get pixels (+ offset 2))
            a (.get pixels (+ offset 3))]
        ;; Swap B and R
        (.put pixels (+ offset 0) r)
        (.put pixels (+ offset 2) b)))
    (.rewind pixels)
    pixels))

;; ============================================================
;; Cleanup
;; ============================================================

(defn cleanup!
  "Release all capture resources."
  []
  (destroy-buffers!))
