(ns app.projects.howto.layout-decay
  "Layout decay demo - layout system with draggable ball and momentum/decay animation.

   Demonstrates:
   - Touch/drag gesture handling
   - Momentum-based decay animation
   - Layout system with virtual scroll
   - Slider controls for grid configuration

   Previously the default example (ball-spring). Renamed to reflect actual content."
  (:require [app.state.system :as sys]
            [app.ui.slider :as slider]
            [lib.flex.core :as flex]
            [lib.graphics.batch :as batch]
            [lib.graphics.shapes :as shapes]
            [lib.layout.core :as layout]
            [lib.layout.mixins :as mixins]
            [lib.layout.render :as layout-render]
            [lib.layout.scroll :as scroll])
  (:import [io.github.humbleui.skija Canvas Color4f Paint PaintMode Font FontMgr FontStyle]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Example Configuration
;; ============================================================

(def circle-color [1.0 0.41 0.71 1.0])  ;; pink
(def grid-circle-radius 100)
(def min-circles 1)
(def max-circles 250)
(def demo-circle-radius 25)
(def demo-circle-color [0.29 0.56 0.85 1.0])  ;; blue
(def demo-anchor-color [1.0 1.0 1.0 0.27])

;; ============================================================
;; Example State (persists across hot-reloads)
;; ============================================================

;; Window dimensions (updated by app.core event handler)
(flex/defsource window-width 800)
(flex/defsource window-height 600)

;; Grid configuration
(flex/defsource circles-x 2)
(flex/defsource circles-y 2)

;; Demo circle interaction
(flex/defsource demo-dragging? false)

;; Demo circle animation state
(defonce demo-circle-x (atom 400.0))
(defonce demo-circle-y (atom 300.0))
(defonce demo-anchor-x (atom 400.0))
(defonce demo-anchor-y (atom 300.0))
(defonce demo-velocity-x (atom 0.0))
(defonce demo-velocity-y (atom 0.0))
(defonce demo-drag-offset-x (atom 0.0))
(defonce demo-position-history (atom []))
(defonce demo-decay-x (atom nil))

;; ============================================================
;; Computed Grid Positions (auto-recompute on deps change)
;; ============================================================

(def grid-positions
  (flex/signal
    (let [nx @circles-x
          ny @circles-y
          w @window-width
          h @window-height
          cell-w (/ w nx)
          cell-h (/ h ny)
          radius (min (/ cell-w 2.2) (/ cell-h 2.2) 100)]
      (vec (for [row (range ny)
                 col (range nx)]
             {:cx (+ (* col cell-w) (/ cell-w 2))
              :cy (+ (* row cell-h) (/ cell-h 2))
              :radius radius})))))

;; ============================================================
;; ============================================================
;; Control panel integration
;; ============================================================

(defn register-controls! []
  "Register example-specific controls with the shell control panel."
  (when-let [register! (requiring-resolve 'app.shell.control-panel/register-controls!)]
    (register! :howto/layout-decay
               [{:id :grid
                 :label "Grid Configuration"
                 :controls [{:type :slider
                             :id :grid-x
                             :label "X"
                             :value-atom circles-x
                             :min min-circles
                             :max max-circles
                             :height 44}
                            {:type :slider
                             :id :grid-y
                             :label "Y"
                             :value-atom circles-y
                             :min min-circles
                             :max max-circles
                             :height 44}]}])))

(defn unregister-controls! []
  "Unregister controls from the shell control panel."
  (when-let [unregister! (requiring-resolve 'app.shell.control-panel/unregister-controls!)]
    (unregister! :howto/layout-decay)))

;; ============================================================
;; Gesture handlers
;; ============================================================

(defn demo-circle-bounds-fn [_ctx]
  (let [cx @demo-circle-x
        cy @demo-circle-y
        r demo-circle-radius]
    [(- cx r) (- cy r) (* 2 r) (* 2 r)]))

(def demo-circle-handlers
  {:on-drag-start
   (fn [event]
     (let [mx (get-in event [:pointer :x])]
       (demo-dragging? true)
       ;; Cancel any running decay animation
       (when-let [cancel! (requiring-resolve 'lib.anim.registry/cancel!)]
         (cancel! :demo-circle-x))
       (reset! demo-drag-offset-x (- @demo-circle-x mx))
       (reset! demo-position-history
               [{:x @demo-circle-x :t @sys/game-time}])
       (reset! demo-velocity-x 0.0)))

   :on-drag
   (fn [event]
     (let [mx (get-in event [:pointer :x])]
       (reset! demo-circle-x (+ mx @demo-drag-offset-x))))

   :on-drag-end
   (fn [_]
     (demo-dragging? false)
     ;; Use animation registry for decay
     (when-let [decay-fn (requiring-resolve 'lib.anim.decay/decay)]
       (when-let [animate! (requiring-resolve 'lib.anim.registry/animate!)]
         (animate! :demo-circle-x
                   (decay-fn {:from @demo-circle-x
                              :velocity @demo-velocity-x
                              :rate :normal})
                   {:target demo-circle-x}))))})

(defn register-control-panel-gestures! []
  "Register gesture handlers for example-specific control panel elements."
  (when-let [register! (requiring-resolve 'lib.gesture.api/register-target!)]
    (when-let [get-control-bounds (requiring-resolve 'app.shell.control-panel/get-control-bounds)]
      (when-let [get-group-header-bounds (requiring-resolve 'app.shell.control-panel/get-group-header-bounds)]
        (when-let [toggle-group! (requiring-resolve 'app.shell.state/toggle-group-collapse!)]
          (when-let [slider-value-fn (requiring-resolve 'app.ui.slider/value-from-x)]
            (when-let [panel-visible (requiring-resolve 'app.shell.state/panel-visible?)]
              ;; Grid configuration group header
                (register!
                 {:id :group-header-grid
                  :layer :overlay
                  :z-index 20
                  :bounds-fn (fn [_ctx]
                               (when @panel-visible
                                 (get-group-header-bounds :grid)))
                  :gesture-recognizers [:tap]
                  :handlers {:on-tap (fn [_] (toggle-group! :grid))}})
                ;; Grid X slider
                (register!
                 {:id :grid-x-slider
                  :layer :overlay
                  :z-index 20
                  :bounds-fn (fn [_ctx]
                               (when @panel-visible
                                 (get-control-bounds :grid :grid-x)))
                  :gesture-recognizers [:drag :tap]
                  :handlers {:on-drag (fn [event]
                                        (when-let [bounds (get-control-bounds :grid :grid-x)]
                                          (let [mx (get-in event [:pointer :x])
                                                new-val (slider-value-fn mx bounds min-circles max-circles)]
                                            (circles-x new-val))))
                             :on-tap (fn [event]
                                       (when-let [bounds (get-control-bounds :grid :grid-x)]
                                         (let [mx (get-in event [:pointer :x])
                                               new-val (slider-value-fn mx bounds min-circles max-circles)]
                                           (circles-x new-val))))}})
                ;; Grid Y slider
                (register!
                 {:id :grid-y-slider
                  :layer :overlay
                  :z-index 20
                  :bounds-fn (fn [_ctx]
                               (when @panel-visible
                                 (get-control-bounds :grid :grid-y)))
                  :gesture-recognizers [:drag :tap]
                  :handlers {:on-drag (fn [event]
                                        (when-let [bounds (get-control-bounds :grid :grid-y)]
                                          (let [mx (get-in event [:pointer :x])
                                                new-val (slider-value-fn mx bounds min-circles max-circles)]
                                            (circles-y new-val))))
                             :on-tap (fn [event]
                                       (when-let [bounds (get-control-bounds :grid :grid-y)]
                                         (let [mx (get-in event [:pointer :x])
                                               new-val (slider-value-fn mx bounds min-circles max-circles)]
                                           (circles-y new-val))))}}))))))))

(defn register-gestures! []
  (when-let [register! (requiring-resolve 'lib.gesture.api/register-target!)]
    (when-let [clear! (requiring-resolve 'lib.gesture.api/clear-targets!)]
      (clear!))

    ;; Demo Circle
    (register!
     {:id :demo-circle
      :layer :content
      :z-index 10
      :bounds-fn demo-circle-bounds-fn
      :gesture-recognizers [:drag]
      :handlers demo-circle-handlers})

    ;; Control panel gestures
    (register-control-panel-gestures!)))

;; ============================================================
;; Layout demo
;; ============================================================

;; Pre-computed fill colors for virtual scroll items (5 variations)
(def ^:private item-fills
  [[0.19 0.19 0.31 1.0]   ;; 0xFF303050
   [0.25 0.25 0.38 1.0]   ;; 0xFF404060
   [0.31 0.31 0.44 1.0]   ;; 0xFF505070
   [0.38 0.38 0.5 1.0]    ;; 0xFF606080
   [0.44 0.44 0.56 1.0]]) ;; 0xFF707090

(def virtual-scroll-mixin
  (mixins/virtual-scroll
    (vec (range 10000))
    40
    (fn [item idx]
      {:fill (nth item-fills (mod idx 5))
       :label (str "Item " idx)})))

(defn demo-ui [viewport-height]
  {:layout {:x {:size "100%"} :y {:size "100%"}}
   :children-layout {:mode :stack-x
                     :x {:before 20 :between 20 :after 20}
                     :y {:before 20 :after 20}}
   :children
   [;; Left column: Original layout demo
    {:layout {:x {:size "1s"} :y {:size "100%"}}
     :children-layout {:mode :stack-y
                       :y {:between 12}}
     :children
     [{:layout {:y {:size 50}}
       :children-layout {:mode :stack-x :x {:between 10}}
       :children
       [{:layout {:x {:size 100}} :fill [0.29 0.56 0.85 1.0] :label "100px"}
        {:layout {:x {:size "1s"}} :fill [1.0 1.0 1.0 0.13] :label "spacer (1s)"}
        {:layout {:x {:size 100}} :fill [0.29 0.56 0.85 1.0] :label "100px"}]}

      {:layout {:y {:size 60}}
       :children-layout {:mode :stack-x :x {:between 10}}
       :children
       [{:layout {:x {:size "1s"}} :fill [0.27 0.67 0.4 1.0] :label "1s"}
        {:layout {:x {:size "2s"}} :fill [0.4 0.8 0.53 1.0] :label "2s"}
        {:layout {:x {:size "1s"}} :fill [0.27 0.67 0.4 1.0] :label "1s"}]}

      {:layout {:y {:size 50}}
       :children-layout {:mode :stack-x :x {:between 10}}
       :children
       [{:layout {:x {:size "30%"}} :fill [0.85 0.29 0.29 1.0] :label "30%"}
        {:layout {:x {:size "70%"}} :fill [0.85 0.48 0.29 1.0] :label "70%"}]}

      {:layout {:y {:size "1s"}} :fill [1.0 1.0 1.0 0.08] :label "stretch (1s)"}]}

    ;; Middle column: Scrollable list demo
    {:id :scroll-demo
     :layout {:x {:size 180} :y {:size "100%"}}
     :fill [1.0 1.0 1.0 0.13]
     :children-layout {:mode :stack-y
                       :overflow {:y :scroll}
                       :y {:before 10 :between 8 :after 10}
                       :x {:before 10 :after 10}}
     :children
     (vec (for [i (range 30)]
            {:layout {:y {:size 40}}
             :fill (nth item-fills (mod i 5))
             :label (str "Item " (inc i))}))}

    ;; Right column: Virtual scroll demo
    (let [y-padding {:before 10 :after 10}]
      {:id :virtual-list
       :layout {:x {:size 180} :y {:size "100%"}}
       :fill [1.0 1.0 1.0 0.13]
       :children-layout {:mode :stack-y
                         :overflow {:y :scroll}
                         :y y-padding
                         :x {:before 10 :after 10}}
       :children (mixins/compute-visible-children virtual-scroll-mixin :virtual-list (- viewport-height 40) y-padding)})]})

(defn find-node-by-id [tree id]
  (when tree
    (if (= (:id tree) id)
      tree
      (some #(find-node-by-id % id) (:children tree)))))

(defn calculate-content-height [node]
  (if-let [children (:children node)]
    (let [bounds (map :bounds children)
          max-bottom (reduce max 0 (map #(+ (:y %) (:h %)) bounds))
          parent-top (get-in node [:bounds :y] 0)
          after-padding (get-in node [:children-layout :y :after] 0)]
      (+ (- max-bottom parent-top) after-padding))
    0))

;; ============================================================
;; Drawing
;; ============================================================

(defn draw-circle-grid [^Canvas canvas]
  (let [positions @grid-positions]
    (when (seq positions)
      (let [radius (:radius (first positions))
            points (mapv (fn [{:keys [cx cy]}] {:x cx :y cy}) positions)]
        (batch/points canvas points radius {:color circle-color})))))

(defn draw-demo-anchor [^Canvas canvas]
  (let [x @demo-anchor-x
        y @demo-anchor-y]
    (shapes/circle canvas x y demo-circle-radius
                  {:color demo-anchor-color
                   :mode :stroke
                   :stroke-width 2.0})))

(defn draw-demo-circle [^Canvas canvas]
  (let [x @demo-circle-x
        y @demo-circle-y]
    (shapes/circle canvas x y demo-circle-radius
                  {:color demo-circle-color})))

(defn draw-layout-demo [^Canvas canvas width height offset-y]
  (let [tree (demo-ui height)
        parent-bounds {:x 0 :y offset-y :w width :h height}
        laid-out (layout/layout tree parent-bounds)]
    (layout/reconcile! laid-out)
    (when-let [scroll-node (find-node-by-id laid-out :scroll-demo)]
      (let [bounds (:bounds scroll-node)
            viewport {:w (:w bounds) :h (:h bounds)}
            content-h (calculate-content-height scroll-node)
            content {:w (:w bounds) :h content-h}]
        (scroll/set-dimensions! :scroll-demo viewport content)))
    (reset! sys/current-tree laid-out)
    (with-open [fill-paint (Paint.)
                text-paint (doto (Paint.) (.setColor4f (io.github.humbleui.skija.Color4f. 1.0 1.0 1.0 1.0)))
                font (Font. (.matchFamilyStyle (FontMgr/getDefault) nil FontStyle/NORMAL) (float 10))]
      (layout-render/walk-layout laid-out canvas
        (fn [node {:keys [x y w h]} _canvas]
          (when-let [[r g b a] (:fill node)]
            (.setColor4f fill-paint (io.github.humbleui.skija.Color4f. (float r) (float g) (float b) (float a)))
            (.setMode fill-paint PaintMode/FILL)
            (.drawRect canvas (Rect/makeXYWH x y w h) fill-paint))
          (when (and (:label node) (not (:children node)))
            (.drawString canvas (:label node) (float (+ x 4)) (float (+ y 12)) font text-paint)))))))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  "Called once when example starts."
  (println "Layout decay demo loaded!")
  ;; Set initial demo position
  (let [demo-y 50]
    (reset! demo-anchor-x (/ @window-width 2))
    (reset! demo-anchor-y demo-y)
    (reset! demo-circle-x (/ @window-width 2))
    (reset! demo-circle-y demo-y))
  ;; Initialize scroll state
  (scroll/init! :scroll-demo)
  (scroll/init! :virtual-list)
  ;; Register controls with control panel
  (register-controls!)
  ;; Register gesture targets
  (register-gestures!))

(defn tick [dt]
  "Called every frame with delta time."
  ;; Track velocity during drag
  (when @demo-dragging?
    (let [history @demo-position-history
          current-x @demo-circle-x
          current-t @sys/game-time
          new-history (-> history
                          (conj {:x current-x :t current-t})
                          (->> (take-last 3))
                          vec)]
      (reset! demo-position-history new-history)
      (when (>= (count new-history) 2)
        (let [oldest (first new-history)
              newest (last new-history)
              dt-hist (- (:t newest) (:t oldest))]
          (when (pos? dt-hist)
            (reset! demo-velocity-x
                    (/ (- (:x newest) (:x oldest)) dt-hist))))))))

(defn draw [^Canvas canvas width height]
  "Called every frame for rendering."
  ;; Update window dimensions (shell passes these)
  (window-width width)
  (window-height height)

  ;; Demo ball area at top (100px reserved)
  (let [demo-area-height 100]
    (draw-demo-anchor canvas)
    (draw-demo-circle canvas)
    ;; Draw layout demo below the demo area
    (draw-layout-demo canvas width (- height demo-area-height) demo-area-height)))

(defn cleanup []
  "Called when switching away from this example."
  (println "Layout decay demo cleanup")
  ;; Unregister controls from control panel
  (unregister-controls!)
  ;; Clear shared tree so stale data doesn't leak to next example
  (reset! sys/current-tree nil))
