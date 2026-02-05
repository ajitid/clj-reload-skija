(ns app.shell.control-panel
  "Control panel component - top-right overlay.

   Shows registered controls organized in collapsible groups when @state/panel-visible? is true.
   Examples register their controls via register-controls! in their init function."
  (:require [lib.color.core :as color]
            [lib.color.open-color :as oc]
            [app.shell.state :as state])
  (:import [io.github.humbleui.skija Canvas Color4f Paint]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Panel configuration
;; ============================================================

(def panel-right-offset 10)
(def panel-y 10)
(def panel-width 240)
(def panel-padding 10)
(def panel-bg-color (color/gray 0.153 0.87))
(def group-spacing 10)

;; ============================================================
;; Control registry
;; ============================================================

;; Global registry: example-key -> groups
;; groups: [{:id :display
;;           :label "Display"
;;           :controls [{:type :checkbox
;;                      :id :show-fps
;;                      :label "Show FPS"
;;                      :value-atom fps-display-visible?
;;                      :height 24}]}]
(defonce control-registry (atom {}))

;; Cached layout info for gesture registration
;; {:group-id {:header-bounds [x y w h]
;;             :controls {:control-id [x y w h]}}}
(defonce layout-cache (atom {}))

(defn register-controls!
  "Register controls for an example.

   Parameters:
   - example-key: keyword identifying the example (e.g., :playground/ball-spring)
   - groups: vector of group specs with :id, :label, :controls"
  [example-key groups]
  (swap! control-registry assoc example-key groups))

(defn unregister-controls!
  "Unregister controls for an example."
  [example-key]
  (swap! control-registry dissoc example-key))

(defn get-default-controls
  "Get default controls that all examples have (FPS display toggle)."
  []
  (when-let [fps-visible (requiring-resolve 'app.shell.state/fps-display-visible?)]
    [{:id :display
      :label "Display"
      :controls [{:type :checkbox
                  :id :show-fps
                  :label "Show FPS"
                  :value-atom fps-visible
                  :height 22}]}]))

(defn get-current-controls
  "Get controls for the currently active example, merged with defaults."
  []
  (let [defaults (get-default-controls)
        example-controls (when-let [example-key @state/current-example]
                          (get @control-registry example-key))]
    ;; Merge: defaults first, then example-specific controls
    (concat defaults example-controls)))

;; ============================================================
;; Bounds calculation helpers
;; ============================================================

(defn calc-panel-x
  "Calculate panel x position (right-aligned)"
  [window-width]
  (- window-width panel-width panel-right-offset))

(defn get-control-bounds
  "Get cached screen bounds for a specific control."
  [group-id control-id]
  (get-in @layout-cache [group-id :controls control-id]))

(defn get-group-header-bounds
  "Get cached screen bounds for a group header."
  [group-id]
  (get-in @layout-cache [group-id :header-bounds]))

;; ============================================================
;; Default control gestures
;; ============================================================

(defn register-default-control-gestures!
  "Register gesture handlers for default controls (FPS checkbox, group headers).
   Should be called after example init to ensure gesture system is ready.
   Targets tagged with :window :panel for multi-window routing."
  []
  (when-let [register! (requiring-resolve 'lib.gesture.api/register-target!)]
    (when-let [get-control-bounds (requiring-resolve 'app.shell.control-panel/get-control-bounds)]
      (when-let [get-group-header-bounds (requiring-resolve 'app.shell.control-panel/get-group-header-bounds)]
        (when-let [toggle-group! (requiring-resolve 'app.shell.state/toggle-group-collapse!)]
          (when-let [fps-visible (requiring-resolve 'app.shell.state/fps-display-visible?)]
            ;; FPS checkbox
            (register!
             {:id :default-fps-checkbox
              :layer :overlay
              :z-index 20
              :window (state/panel-gesture-window)
              :bounds-fn (fn [_ctx]
                           (get-control-bounds :display :show-fps))
              :gesture-recognizers [:tap]
              :handlers {:on-tap (fn [_]
                                   (let [fps-source @fps-visible]
                                     (fps-source (not @fps-source))))}})
            ;; Display group header
            (register!
             {:id :default-group-header-display
              :layer :overlay
              :z-index 20
              :window (state/panel-gesture-window)
              :bounds-fn (fn [_ctx]
                           (get-group-header-bounds :display))
              :gesture-recognizers [:tap]
              :handlers {:on-tap (fn [_] (toggle-group! :display))}})))))))

;; ============================================================
;; Value resolution helpers
;; ============================================================

(defn resolve-value
  "Resolve a value from a value-atom, handling both:
   - Var -> defsource -> value (from requiring-resolve)
   - Direct defsource -> value (passed directly)
   Returns nil if value-atom is nil."
  [value-atom]
  (when value-atom
    (let [v (deref value-atom)]
      (if (fn? v)
        ;; It's a defsource (which is a function), deref to get the value
        (deref v)
        ;; It's already the value (direct defsource was passed)
        v))))

;; ============================================================
;; Drawing
;; ============================================================

(defn draw-control
  "Draw a single control (checkbox or slider).
   Returns the height consumed."
  [canvas control cx cy cw]
  (case (:type control)
    :checkbox
    (let [checkbox-draw (requiring-resolve 'app.ui.checkbox/draw)
          checked? (resolve-value (:value-atom control))
          height (:height control)
          bounds [cx cy cw height]]
      ;; Cache bounds for gesture registration
      (swap! layout-cache assoc-in [(:group-id control) :controls (:id control)] bounds)
      (when checkbox-draw
        (checkbox-draw canvas (:label control) checked? bounds {}))
      height)

    :slider
    (let [slider-draw (requiring-resolve 'app.ui.slider/draw)
          value (resolve-value (:value-atom control))
          height (:height control)
          ;; Slider needs vertical space for label above track
          label-height 20
          track-height 16
          bounds [cx (+ cy label-height) cw track-height]]
      ;; Cache bounds for gesture registration (track area)
      (swap! layout-cache assoc-in [(:group-id control) :controls (:id control)] bounds)
      (when slider-draw
        (slider-draw canvas (:label control) value bounds
                     {:min (:min control 1)
                      :max (:max control 100)}))
      height)

    :text-field
    (let [text-field-draw (requiring-resolve 'app.ui.text-field/draw)
          register-field (requiring-resolve 'app.ui.text-field/register-field!)
          value-atom (:value-atom control)
          text-value (resolve-value value-atom)
          height (:height control)
          label-height 20
          box-height 24
          bounds [cx (+ cy label-height) cw box-height]
          field-id [(:group-id control) (:id control)]]
      ;; Register field for focus/editing
      (when register-field (register-field field-id value-atom))
      ;; Cache bounds for gesture
      (swap! layout-cache assoc-in [(:group-id control) :controls (:id control)] bounds)
      (when text-field-draw
        (text-field-draw canvas (:label control) (str text-value) field-id bounds {}))
      height)

    ;; Unknown control type
    0))

(defn draw-group
  "Draw a collapsible group with its controls.
   Returns the height consumed."
  [canvas group gx gy gw]
  (let [group-draw (requiring-resolve 'app.ui.group/draw)
        total-height-fn (requiring-resolve 'app.ui.group/total-height)
        header-bounds-fn (requiring-resolve 'app.ui.group/header-bounds)
        group-id (:id group)
        collapsed? (state/is-group-collapsed? group-id)
        controls (:controls group)
        ;; Add group-id to each control for layout caching
        controls-with-group (map #(assoc % :group-id group-id) controls)
        ;; Build children specs for the group widget
        children (mapv (fn [control]
                         {:height (:height control)
                          :draw-fn (fn [canvas cx cy cw]
                                     (draw-control canvas control cx cy cw))})
                       controls-with-group)
        opts {:indent 10 :vertical-spacing 6 :top-padding 6 :bottom-padding 6}
        height (when total-height-fn
                 (total-height-fn collapsed? children opts))
        bounds [gx gy gw height]]
    ;; Cache header bounds for gesture registration
    (when header-bounds-fn
      (let [header-bounds (header-bounds-fn gx gy gw 26)]
        (swap! layout-cache assoc-in [group-id :header-bounds] header-bounds)))
    ;; Draw the group
    (when group-draw
      (group-draw canvas (:label group) collapsed? bounds children opts))
    height))

(defn draw
  "Draw control panel at top-right when @state/panel-visible?"
  [^Canvas canvas window-width]
  (when @state/panel-visible?
    (let [groups (get-current-controls)]
      (when (seq groups)
        ;; Clear layout cache at start of frame
        (reset! layout-cache {})
        (let [px (calc-panel-x window-width)
              py panel-y
              pw panel-width
              pad panel-padding
              ;; Calculate total panel height
              total-height-fn (requiring-resolve 'app.ui.group/total-height)
              group-heights (when total-height-fn
                              (mapv (fn [group]
                                      (let [collapsed? (state/is-group-collapsed? (:id group))
                                            controls (:controls group)
                                            children (mapv #(select-keys % [:height]) controls)
                                            opts {:indent 10 :vertical-spacing 6 :top-padding 6 :bottom-padding 6}]
                                        (total-height-fn collapsed? children opts)))
                                    groups))
              total-content-height (+ (* pad 2)
                                      (reduce + 0 group-heights)
                                      (* group-spacing (max 0 (dec (count groups)))))
              ph total-content-height]
          ;; Draw panel background
          (let [[r g b a] panel-bg-color]
            (with-open [bg-paint (doto (Paint.)
                                   (.setColor4f (Color4f. (float r) (float g) (float b) (float a))))]
              (.drawRect canvas (Rect/makeXYWH (float px) (float py) (float pw) (float ph)) bg-paint)))
          ;; Draw groups
          (loop [groups groups
                 cy (+ py pad)]
            (when-let [group (first groups)]
              (let [height (draw-group canvas group (+ px pad) cy (- pw (* 2 pad)))]
                (recur (rest groups) (+ cy height group-spacing))))))))))

(defn draw-standalone
  "Draw control panel filling the entire canvas. For standalone panel window.
   Ignores panel-visible? check since the panel is always shown in its own window."
  [^Canvas canvas width height]
  (let [groups (get-current-controls)]
    (when (seq groups)
      ;; Clear layout cache at start of frame
      (reset! layout-cache {})
      (let [pad panel-padding
            content-width (- width (* 2 pad))]
        ;; Draw background filling entire canvas
        (let [[r g b a] panel-bg-color]
          (with-open [bg-paint (doto (Paint.)
                                 (.setColor4f (Color4f. (float r) (float g) (float b) (float a))))]
            (.drawRect canvas (Rect/makeXYWH 0.0 0.0 (float width) (float height)) bg-paint)))
        ;; Draw groups starting from padding offset
        (loop [groups groups
               cy (float pad)]
          (when-let [group (first groups)]
            (let [h (draw-group canvas group pad cy content-width)]
              (recur (rest groups) (+ cy h group-spacing)))))))))
