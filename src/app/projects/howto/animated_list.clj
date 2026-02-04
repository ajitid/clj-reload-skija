(ns app.projects.howto.animated-list
  "Animated list demo - items animate when added, removed, or reordered.

   Demonstrates:
   - lib.projection.core for FLIP-style layout animations
   - Enter/exit transitions with spring physics
   - Smooth reordering animations

   Controls:
   - Click 'Add' to add item (fades in from top)
   - Click 'Remove' to remove random item (fades out)
   - Click 'Shuffle' to reorder (smooth position animation)"
  (:require [app.shell.state :as state]
            [lib.color.open-color :as oc]
            [lib.flex.core :as flex]
            [lib.gesture.api :as gesture]
            [lib.graphics.shapes :as shapes]
            [lib.layout.core :as layout]
            [lib.layout.render :as layout-render]
            [lib.magic-move :as magic])
  (:import [io.github.humbleui.skija Canvas Color4f Paint PaintMode Font FontMgr FontStyle]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; State
;; ============================================================

(flex/defsource items
  [{:id 1 :text "Apple" :color oc/red-5}
   {:id 2 :text "Banana" :color oc/yellow-4}
   {:id 3 :text "Cherry" :color oc/pink-7}
   {:id 4 :text "Date" :color [0.73 0.41 0.78 1.0]}
   {:id 5 :text "Elderberry" :color [0.47 0.53 0.8 1.0]}])

(defonce next-id (atom 6))

;; Button state
(flex/defsource hovered-button nil)

;; Button bounds (updated each frame after layout)
(defonce button-bounds (atom {}))

;; ============================================================
;; Item Operations
;; ============================================================

(defn add-item! []
  (let [id (swap! next-id inc)
        fruits ["Fig" "Grape" "Honeydew" "Kiwi" "Lemon" "Mango" "Nectarine" "Orange" "Papaya" "Quince"]
        colors [[0.51 0.78 0.52 1.0] [0.31 0.76 0.97 1.0] oc/yellow-6
                oc/pink-5 [0.58 0.46 0.8 1.0] [0.3 0.71 0.67 1.0]]]
    (items (conj @items {:id id
                         :text (rand-nth fruits)
                         :color (rand-nth colors)}))))

(defn remove-item! []
  (when (seq @items)
    (let [idx (rand-int (count @items))
          current @items]
      (items (vec (concat (take idx current) (drop (inc idx) current)))))))

(defn shuffle-items! []
  (items (shuffle @items)))

;; ============================================================
;; UI Tree
;; ============================================================

(defn build-ui []
  {:layout {:x {:size "100%"} :y {:size "100%"}}
   :children-layout {:mode :stack-y
                     :y {:before 20 :between 0}
                     :x {:before 20 :after 20}}
   :children
   [;; Button row
    {:layout {:y {:size 50}}
     :children-layout {:mode :stack-x :x {:between 10}}
     :children
     [{:id :btn-add :layout {:x {:size 80}} :label "Add" :action add-item!}
      {:id :btn-remove :layout {:x {:size 80}} :label "Remove" :action remove-item!}
      {:id :btn-shuffle :layout {:x {:size 80}} :label "Shuffle" :action shuffle-items!}]}

    ;; Item list
    {:layout {:y {:size "1s"}}
     :children-layout {:mode :stack-y
                       :y {:before 10 :between 8}}
     :children
     (mapv (fn [{:keys [id text color]}]
             {:id id
              :layout {:y {:size 50}}
              :text text
              :fill color})
           @items)}]})

;; ============================================================
;; Gesture Registration
;; ============================================================

(defn- make-button-bounds-fn [btn-id]
  (fn [_ctx]
    (when-let [{:keys [x y w h]} (get @button-bounds btn-id)]
      [x y w h])))

(defn- make-button-handler [action-fn]
  {:on-tap (fn [_event] (action-fn))})

(defn register-gestures! []
  (gesture/clear-targets!)
  (doseq [[btn-id action-fn] [[:btn-add add-item!]
                               [:btn-remove remove-item!]
                               [:btn-shuffle shuffle-items!]]]
    (gesture/register-target!
      {:id btn-id
       :layer :overlay
       :window (state/panel-gesture-window)
       :z-index 10
       :bounds-fn (make-button-bounds-fn btn-id)
       :gesture-recognizers [:tap]
       :handlers (make-button-handler action-fn)})))

;; ============================================================
;; Drawing
;; ============================================================

(defn draw-button [^Canvas canvas {:keys [x y w h]} label hovered?]
  (let [color (if hovered? [0.4 0.4 0.4 1.0] [0.27 0.27 0.27 1.0])]
    (shapes/rounded-rect canvas x y w h 6 {:color color})
    (with-open [paint (doto (Paint.) (.setColor (unchecked-int 0xFFFFFFFF)))
                font (Font. (.matchFamilyStyle (FontMgr/getDefault) nil FontStyle/NORMAL) (float 14))]
      (let [text-width (.measureTextWidth font label)
            tx (+ x (/ (- w text-width) 2))
            ty (+ y (/ h 2) 5)]
        (.drawString canvas label (float tx) (float ty) font paint)))))

(defn draw-item [^Canvas canvas {:keys [x y w h]} text color opacity]
  (when (pos? opacity)
    (.save canvas)
    ;; Apply opacity - color is now [r g b a] format
    (let [[r g b a] color]
      (with-open [paint (doto (Paint.)
                          (.setColor4f (io.github.humbleui.skija.Color4f.
                                        (float r) (float g) (float b) (float (* a opacity)))))]
        (.drawRRect canvas (io.github.humbleui.types.RRect/makeXYWH x y w h 8 8 8 8) paint)))
    ;; Text
    (with-open [text-paint (doto (Paint.)
                             (.setColor (unchecked-int 0xFF000000))
                             (.setAlphaf (float opacity)))
                font (Font. (.matchFamilyStyle (FontMgr/getDefault) nil FontStyle/NORMAL) (float 16))]
      (.drawString canvas text (float (+ x 15)) (float (+ y 30)) font text-paint))
    (.restore canvas)))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Animated list demo loaded!")
  (magic/reset-all!)
  (register-gestures!))

(defn tick [_dt]
  ;; Nothing needed per-frame
  )

(defn draw [^Canvas canvas width height]
  (let [tree (build-ui)
        laid-out (layout/layout tree {:x 0 :y 0 :w width :h height})]

    ;; Update projection - this detects changes and creates animations
    (magic/update! laid-out
      {:spring {:duration 0.5 :bounce 0.2}
       :enter {:from {:opacity 0 :y -30}}
       :exit {:to {:opacity 0 :y 30}}})

    (layout/reconcile! laid-out)

    ;; Draw elements with animated bounds
    (layout-render/walk-layout laid-out canvas
      (fn [node raw-bounds _canvas]
        (cond
          ;; Buttons - store bounds for gesture hit testing
          (:label node)
          (do
            (swap! button-bounds assoc (:id node) raw-bounds)
            (draw-button canvas raw-bounds (:label node) (= (:id node) @hovered-button)))

          ;; List items (have :text and :fill)
          (and (:text node) (:fill node))
          (let [{:keys [x y w h opacity]} (magic/bounds (:id node) raw-bounds)]
            (draw-item canvas {:x x :y y :w w :h h} (:text node) (:fill node) opacity)))))

    ;; Draw exiting elements (still animating out)
    (doseq [{:keys [id x y w h opacity]} (magic/exiting-elements)]
      ;; Find original item data (may need to cache this)
      (draw-item canvas {:x x :y y :w w :h h} "..." [0.53 0.53 0.53 1.0] opacity))))

(defn cleanup []
  (println "Animated list cleanup")
  (magic/reset-all!)
  (gesture/clear-targets!))
