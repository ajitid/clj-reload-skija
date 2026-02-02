(ns lib.window.capture-metal
  "Metal frame capture using double-buffered async GPU blit."
  (:require [lib.window.metal :as metal]
            [lib.window.layer-metal :as layer-metal])
  (:import [org.lwjgl.system MemoryUtil]
           [java.nio ByteBuffer]))

;; Try to load FFM module - it may not be available on older JDKs
(def ^:private ffm-ns
  (try
    (require 'lib.window.metal-ffm)
    (find-ns 'lib.window.metal-ffm)
    (catch Exception e
      (println "[capture-metal] FFM module not available:" (.getMessage e))
      nil)))

(defn- ffm-available? []
  (and ffm-ns
       (when-let [avail-fn (ns-resolve ffm-ns 'available?)]
         (avail-fn))))

(defn- ffm-call [fn-name & args]
  "Call a function in metal-ffm namespace if available."
  (when ffm-ns
    (when-let [f (ns-resolve ffm-ns fn-name)]
      (apply f args))))

(defonce capture-state
  (atom {:mtl-buffers    [nil nil]
         :cpu-buffers    [nil nil]
         :cmd-buffers    [nil nil]
         :buffer-index   0
         :width          0
         :height         0
         :bytes-per-row  0
         :initialized?   false
         :primed?        false
         :ready-flags    [false false]
         :use-ffm?       nil}))

(defn- buffer-size [width height]
  (* width height 4))

(defn- init-fallback-buffers! [size width height bytes-per-row]
  (let [cpu-buf-a (MemoryUtil/memAlloc size)
        cpu-buf-b (MemoryUtil/memAlloc size)]
    (swap! capture-state assoc
           :mtl-buffers [nil nil]
           :cpu-buffers [cpu-buf-a cpu-buf-b]
           :buffer-index 0
           :width width
           :height height
           :bytes-per-row bytes-per-row
           :initialized? true
           :primed? false
           :ready-flags [false false]
           :cmd-buffers [nil nil]
           :use-ffm? false)
    (println "[capture-metal] Fallback buffers initialized:" width "x" height)))

(defn init-buffers! [width height]
  (when (and (pos? width) (pos? height))
    (let [size (buffer-size width height)
          bytes-per-row (* width 4)
          device (layer-metal/device)
          ffm-available? (and device (pos? device) (ffm-available?))]
      (if ffm-available?
        (let [mtl-buf-a (metal/create-buffer device size)
              mtl-buf-b (metal/create-buffer device size)]
          (if (and mtl-buf-a (pos? mtl-buf-a) mtl-buf-b (pos? mtl-buf-b))
            (do
              (swap! capture-state assoc
                     :mtl-buffers [mtl-buf-a mtl-buf-b]
                     :cpu-buffers [nil nil]
                     :buffer-index 0
                     :width width
                     :height height
                     :bytes-per-row bytes-per-row
                     :initialized? true
                     :primed? false
                     :ready-flags [false false]
                     :cmd-buffers [nil nil]
                     :use-ffm? true)
              (println "[capture-metal] FFM async buffers initialized:" width "x" height))
            (do
              (when (and mtl-buf-a (pos? mtl-buf-a)) (metal/release! mtl-buf-a))
              (when (and mtl-buf-b (pos? mtl-buf-b)) (metal/release! mtl-buf-b))
              (println "[capture-metal] MTLBuffer creation failed, using fallback")
              (init-fallback-buffers! size width height bytes-per-row))))
        (init-fallback-buffers! size width height bytes-per-row)))))

(defn destroy-buffers! []
  (let [{:keys [mtl-buffers cpu-buffers initialized? use-ffm?]} @capture-state]
    (when initialized?
      (if use-ffm?
        (doseq [buf mtl-buffers] (when (and buf (pos? buf)) (metal/release! buf)))
        (doseq [buf cpu-buffers] (when buf (MemoryUtil/memFree buf))))
      (swap! capture-state assoc
             :mtl-buffers [nil nil] :cpu-buffers [nil nil]
             :initialized? false :primed? false
             :ready-flags [false false] :cmd-buffers [nil nil] :use-ffm? nil)
      (println "[capture-metal] Buffers destroyed"))))

(defn- resize-buffers! [new-width new-height]
  (let [{:keys [width height initialized?]} @capture-state]
    (when (and initialized? (or (not= new-width width) (not= new-height height)))
      (destroy-buffers!)
      (init-buffers! new-width new-height))))

(defn- ensure-buffers! [width height]
  (if (:initialized? @capture-state)
    (resize-buffers! width height)
    (init-buffers! width height)))

(defn issue-async-blit! [texture width height]
  (when (and texture (pos? texture) (pos? width) (pos? height))
    (ensure-buffers! width height)
    (let [{:keys [mtl-buffers buffer-index bytes-per-row use-ffm? initialized?]} @capture-state]
      (when (and initialized? use-ffm?)
        (let [current-buf (nth mtl-buffers buffer-index)
              queue (layer-metal/queue)]
          (when (and current-buf (pos? current-buf) queue (pos? queue))
            (let [cmd-buffer (metal/create-command-buffer queue)]
              (when cmd-buffer
                (let [blit-encoder (metal/create-blit-command-encoder cmd-buffer)]
                  (when blit-encoder
                    (when (ffm-call 'copy-texture-to-buffer!
                            blit-encoder texture current-buf width height bytes-per-row)
                      (metal/end-encoding! blit-encoder)
                      (metal/commit-command-buffer! cmd-buffer)
                      (swap! capture-state assoc-in [:cmd-buffers buffer-index] cmd-buffer)
                      (swap! capture-state assoc-in [:ready-flags buffer-index] true)
                      true)))))))))))

(defn capture-texture! [texture cmd-buffer width height]
  (when (and texture (pos? texture) (pos? width) (pos? height))
    (ensure-buffers! width height)
    (let [{:keys [cpu-buffers buffer-index bytes-per-row use-ffm? initialized?]} @capture-state]
      (when (and initialized? (not use-ffm?))
        (let [current-buf (nth cpu-buffers buffer-index)]
          (when current-buf
            (when cmd-buffer (metal/wait-until-completed! cmd-buffer))
            (let [dest-ptr (MemoryUtil/memAddress current-buf)]
              (when (metal/get-texture-bytes! texture dest-ptr bytes-per-row width height)
                (swap! capture-state assoc-in [:ready-flags buffer-index] true)
                true))))))))

(defn get-previous-frame [width height]
  (let [{:keys [mtl-buffers cpu-buffers buffer-index ready-flags cmd-buffers primed? use-ffm?]} @capture-state]
    (when primed?
      (let [prev-index (mod (inc buffer-index) 2)
            prev-ready? (nth ready-flags prev-index)]
        (when prev-ready?
          (let [result
                (if use-ffm?
                  ;; FFM path
                  (let [prev-cmd (nth cmd-buffers prev-index)
                        prev-buf (nth mtl-buffers prev-index)]
                    (when (and prev-cmd prev-buf (metal/command-buffer-completed? prev-cmd))
                      (let [contents-ptr (metal/get-buffer-contents prev-buf)
                            size (buffer-size width height)]
                        (when (and contents-ptr (pos? contents-ptr))
                          (let [result (MemoryUtil/memAlloc size)]
                            (MemoryUtil/memCopy contents-ptr (MemoryUtil/memAddress result) size)
                            result)))))
                  ;; Fallback path
                  (let [prev-buf (nth cpu-buffers prev-index)
                        size (buffer-size width height)]
                    (when prev-buf
                      (.rewind ^ByteBuffer prev-buf)
                      (let [result (MemoryUtil/memAlloc size)]
                        (.put ^ByteBuffer result ^ByteBuffer prev-buf)
                        (.flip ^ByteBuffer result)
                        (.rewind ^ByteBuffer prev-buf)
                        result))))]
            ;; Reset ready flag after consuming the frame to prevent re-reads
            ;; and allow the buffer to be reused for new captures
            (when result
              (swap! capture-state assoc-in [:ready-flags prev-index] false))
            result))))))

(defn flip-buffer! []
  (swap! capture-state update :buffer-index #(mod (inc %) 2)))

(defn mark-primed! []
  (swap! capture-state assoc :primed? true))

(defn primed? []
  (:primed? @capture-state))

(defn using-ffm? []
  (:use-ffm? @capture-state))

(defn bgra->rgba! [^ByteBuffer pixels width height]
  (let [num-pixels (* width height)]
    (dotimes [i num-pixels]
      (let [offset (* i 4)
            b (.get pixels (+ offset 0))
            r (.get pixels (+ offset 2))]
        (.put pixels (+ offset 0) r)
        (.put pixels (+ offset 2) b)))
    (.rewind pixels)
    pixels))

(defn cleanup! []
  (destroy-buffers!)
  (try (ffm-call 'cleanup!) (catch Exception _)))
