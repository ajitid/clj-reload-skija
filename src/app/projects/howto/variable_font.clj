(ns app.projects.howto.variable-font
  "Variable Font Animation - Demonstrates animated weight axis.

   Shows how to:
   - Load a variable font (Inter Variable)
   - Query variation axes (min/max weight)
   - Animate weight continuously using sine wave
   - Edit display text via control panel"
  (:require [lib.color.core :as color]
            [lib.color.open-color :as oc]
            [app.shell.state :as state]
            [app.state.system :as sys]
            [lib.flex.core :as flex]
            [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

;; (def window-config {:panel-inline? false})

(def font-family "Inter Variable")
(def font-size 72)
(def animation-speed 0.5)  ;; cycles per second

;; ============================================================
;; State (persists across hot-reloads)
;; ============================================================

(defonce typeface-info (atom nil))

;; Editable display text (persists across hot-reloads)
(flex/defsource display-text "Typography")

;; ============================================================
;; Font Setup
;; ============================================================

(defn load-font-info! []
  "Load typeface and query its variation axes."
  (let [typeface (text/get-typeface font-family)
        axes (text/variation-axes typeface)
        weight-axis (first (filter #(= (:tag %) "wght") axes))]
    (reset! typeface-info
            {:typeface typeface
             :axes axes
             :weight-axis weight-axis
             :min-weight (or (:min weight-axis) 100)
             :max-weight (or (:max weight-axis) 900)
             :default-weight (or (:default weight-axis) 400)})))

;; ============================================================
;; Animation
;; ============================================================

(defn calculate-weight [time]
  "Calculate current weight from sine wave animation."
  (let [{:keys [min-weight max-weight]} @typeface-info
        ;; Sine wave oscillates between -1 and 1
        t (* time animation-speed 2 Math/PI)
        normalized (/ (+ 1 (Math/sin t)) 2)  ;; 0 to 1
        weight (+ min-weight (* normalized (- max-weight min-weight)))]
    weight))

;; ============================================================
;; Drawing
;; ============================================================

(defn draw-info [^Canvas canvas width _height]
  "Draw font information at the top."
  (let [{:keys [min-weight max-weight default-weight]} @typeface-info]
    ;; Title
    (text/text canvas "Inter Variable Weight Animation"
               (/ width 2) 50
               {:size 24 :weight :medium :align :center :color color/white})
    ;; Axis info
    (text/text canvas (format "Weight Axis: min %.0f | default %.0f | max %.0f"
                              (double min-weight) (double default-weight) (double max-weight))
               (/ width 2) 85
               {:size 16 :align :center :color [0.53 0.53 0.53 1.0]})))

(defn draw-animated-text [^Canvas canvas width height time]
  "Draw the main animated text."
  (let [weight (calculate-weight time)
        y-center (/ height 2)]
    ;; Main animated text
    (text/text canvas @display-text
               (/ width 2) y-center
               {:size font-size
                :family font-family
                :variations {:wght weight}
                :align :center
                :color color/white
                :animated true})
    ;; Current weight indicator
    ;; Current weight indicator (tabular numbers for stable width)
    (text/text canvas (format "wght: %.0f" weight)
               (/ width 2) (+ y-center 60)
               {:size 20
                :family font-family
                :features "tnum"  ;; tabular numbers - fixed width digits
                :align :center
                :color oc/blue-6})))

(defn draw-weight-samples [^Canvas canvas width height]
  "Draw static weight samples at bottom for comparison."
  (let [{:keys [min-weight max-weight]} @typeface-info
        weights [100 200 300 400 500 600 700 800 900]
        valid-weights (filter #(and (>= % min-weight) (<= % max-weight)) weights)
        y-base (- height 120)
        spacing (/ width (+ 1 (count valid-weights)))]
    ;; Label
    (text/text canvas "Weight Samples"
               (/ width 2) (- y-base 45)
               {:size 14 :align :center :color [0.4 0.4 0.4 1.0]})
    ;; Weight samples
    (doseq [[i w] (map-indexed vector valid-weights)]
      (let [x (* spacing (+ i 1))]
        (text/text canvas "Aa"
                   x y-base
                   {:size 32
                    :family font-family
                    :variations {:wght w}
                    :align :center
                    :color oc/gray-4})
        (text/text canvas (str (int w))
                   x (+ y-base 25)
                   {:size 11
                    :align :center
                    :color [0.4 0.4 0.4 1.0]})))))

;; ============================================================
;; Control Panel Registration
;; ============================================================

(defn- register-controls! []
  (when-let [register! (requiring-resolve 'app.shell.control-panel/register-controls!)]
    (register! :howto/variable-font
               [{:id :text
                 :label "Text"
                 :controls [{:type :text-field
                             :id :display-text
                             :label "Display Text"
                             :value-atom display-text
                             :height 44}]}])))

(defn- register-gestures! []
  (when-let [register! (requiring-resolve 'lib.gesture.api/register-target!)]
    (when-let [get-bounds (requiring-resolve 'app.shell.control-panel/get-control-bounds)]
      (when-let [focus! (requiring-resolve 'app.ui.text-field/focus!)]
        ;; Text field tap -> focus
        (register!
         {:id :variable-font-text-field
          :layer :overlay
          :z-index 20
          :window (state/panel-gesture-window)
          :bounds-fn (fn [_ctx]
                       (get-bounds :text :display-text))
          :gesture-recognizers [:tap]
          :handlers {:on-tap (fn [_] (focus! [:text :display-text]))}})))
    ;; Group header tap -> toggle collapse
    (when-let [toggle-group! (requiring-resolve 'app.shell.state/toggle-group-collapse!)]
      (when-let [get-header (requiring-resolve 'app.shell.control-panel/get-group-header-bounds)]
        (register!
         {:id :variable-font-group-header-text
          :layer :overlay
          :z-index 20
          :window (state/panel-gesture-window)
          :bounds-fn (fn [_ctx] (get-header :text))
          :gesture-recognizers [:tap]
          :handlers {:on-tap (fn [_] (toggle-group! :text))}})))))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  "Called once when example starts."
  (load-font-info!)
  (register-controls!)
  (register-gestures!)
  (let [{:keys [weight-axis]} @typeface-info]
    (println "Variable Font loaded:" font-family)
    (println "Weight axis:" weight-axis)))

(defn tick [_dt]
  "Called every frame with delta time."
  nil)

(defn draw [^Canvas canvas width height]
  "Called every frame for rendering."
  (when @typeface-info
    (let [time @sys/game-time]
      (draw-info canvas width height)
      (draw-animated-text canvas width height time)
      (draw-weight-samples canvas width height))))

(defn cleanup []
  "Called when switching away from this example."
  (when-let [unregister! (requiring-resolve 'app.shell.control-panel/unregister-controls!)]
    (unregister! :howto/variable-font))
  (when-let [unregister-target! (requiring-resolve 'lib.gesture.api/unregister-target!)]
    (unregister-target! :variable-font-text-field)
    (unregister-target! :variable-font-group-header-text))
  (when-let [unfocus! (requiring-resolve 'app.ui.text-field/unfocus!)]
    (unfocus!))
  (println "Variable Font cleanup"))
