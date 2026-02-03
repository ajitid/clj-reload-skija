(ns lib.graphics.shadows
  "Material Design / iOS-style 3D shadows using ShadowUtils.

   Unlike ImageFilter-based shadows, these draw directly to the canvas
   and produce realistic ambient + spot shadow pairs based on a light
   source position and surface elevation.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:import [io.github.humbleui.skija Canvas Path ShadowUtils ShadowUtilsFlag]
           [io.github.humbleui.types Point3]))

(defn- color->argb-int
  "Convert [r g b a] float color (0.0-1.0) to 32-bit ARGB int."
  [[r g b a]]
  (let [ai (int (* 255 (float (or a 1.0))))
        ri (int (* 255 (float r)))
        gi (int (* 255 (float g)))
        bi (int (* 255 (float b)))]
    (unchecked-int (bit-or (bit-shift-left ai 24)
                           (bit-shift-left ri 16)
                           (bit-shift-left gi 8)
                           bi))))

(defn- resolve-flags
  "Convert flag keywords to ShadowUtilsFlag array for varargs."
  [flags]
  (let [flag-set (set flags)
        result (cond-> []
                 (flag-set :transparent) (conj ShadowUtilsFlag/TRANSPARENT_OCCLUDER)
                 (flag-set :geometric-only) (conj ShadowUtilsFlag/GEOMETRIC_ONLY)
                 (flag-set :directional) (conj ShadowUtilsFlag/DIRECTIONAL_LIGHT))]
    (into-array ShadowUtilsFlag result)))

(defn draw-shadow
  "Draw a Material Design shadow for a path.

   Args:
     canvas  - Canvas to draw on
     path    - Path shape to shadow
     opts    - map:
       :z-height      float - elevation of the surface (default 4.0)
       :light-pos     [x y z] - light position (default [0 0 600])
       :light-radius  float - light size (default 800)
       :ambient       [r g b a] - ambient shadow color (default [0 0 0 0.1])
       :spot          [r g b a] - spot shadow color (default [0 0 0 0.25])
       :flags         #{:transparent :geometric-only :directional}

   Example:
     (draw-shadow canvas my-path {:z-height 8
                                  :spot [0 0 0 0.4]})"
  [^Canvas canvas ^Path path opts]
  (let [{:keys [z-height light-pos light-radius ambient spot flags]
         :or {z-height 4.0
              light-pos [0 0 600]
              light-radius 800
              ambient [0 0 0 0.1]
              spot [0 0 0 0.25]
              flags #{}}} opts
        [lx ly lz] light-pos
        z-plane (Point3. (float 0) (float 0) (float z-height))
        light-pt (Point3. (float lx) (float ly) (float lz))
        ambient-color (color->argb-int ambient)
        spot-color (color->argb-int spot)
        flag-arr (resolve-flags flags)]
    (ShadowUtils/drawShadow
      canvas path z-plane light-pt
      (float light-radius)
      ambient-color spot-color
      flag-arr)))
