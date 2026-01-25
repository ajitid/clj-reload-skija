(ns lib.graphics.atlas
  "Sprite atlas and image drawing - Love2D-style API.

   Provides efficient sprite sheet handling with quad-based drawing.
   Follows Love2D conventions: load images, define quads (sprite regions),
   and draw with position/rotation/scale/origin.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern).

   ## Usage

   ```clojure
   ;; Load a sprite sheet
   (def sheet (atlas/load-image \"assets/sprites.png\"))

   ;; Define quads (sprite regions within the sheet)
   (def player-idle (atlas/quad 0 0 32 32))
   (def player-walk (atlas/quad 32 0 32 32))

   ;; Draw sprites
   (atlas/draw canvas sheet player-idle 100 100)
   (atlas/draw canvas sheet player-idle 100 100
     {:rotation (/ Math/PI 4)
      :scale 2.0
      :origin [16 16]})

   ;; Draw entire image (no quad)
   (atlas/draw-image canvas sheet 0 0)
   (atlas/draw-image canvas sheet 0 0 {:alpha 0.5 :scale [2 1]})
   ```"
  (:require [lib.graphics.state :as gfx]
            [clojure.java.io :as io])
  (:import [io.github.humbleui.skija Canvas Image Data SamplingMode Paint Matrix33
            BlendMode FilterTileMode]
           [io.github.humbleui.types Rect Point]))

;; ============================================================
;; RSXform - Rotation/Scale Transform
;; ============================================================
;; Skia's RSXform is a compressed rotation+scale matrix:
;;   [ scos  -ssin  tx ]
;;   [ ssin   scos  ty ]
;;   [    0      0   1 ]
;;
;; This is useful for efficiently transforming sprites in batch operations.
;; Currently Skija doesn't expose drawAtlas, but we include these helpers
;; for future compatibility and for manual transform calculations.

(defrecord RSXform [^float scos ^float ssin ^float tx ^float ty])

(defn rsxform
  "Create an RSXform from scale*cos, scale*sin, and translation.

   The matrix form is:
     [ scos  -ssin  tx ]
     [ ssin   scos  ty ]

   Args:
     scos - scale * cos(rotation)
     ssin - scale * sin(rotation)
     tx   - x translation
     ty   - y translation

   Example:
     ;; Identity transform at position (100, 200)
     (rsxform 1 0 100 200)

     ;; Scale 2x, no rotation, at (50, 50)
     (rsxform 2 0 50 50)"
  [scos ssin tx ty]
  (->RSXform (float scos) (float ssin) (float tx) (float ty)))

(defn rsxform-from-radians
  "Create an RSXform from rotation angle, scale, position, and anchor point.

   This is the high-level way to create transforms - specify the rotation
   in radians, a uniform scale, the destination position, and an anchor
   point within the sprite that should be placed at that position.

   Args:
     scale   - uniform scale factor
     radians - rotation angle in radians
     tx, ty  - destination position
     ax, ay  - anchor point in sprite coordinates (pivot point)

   Example:
     ;; Place sprite at (100, 100), rotated 45 degrees, centered on a 32x32 sprite
     (rsxform-from-radians 1.0 (/ Math/PI 4) 100 100 16 16)

     ;; Scale 2x, no rotation, anchored at top-left
     (rsxform-from-radians 2.0 0.0 50 50 0 0)"
  [scale radians tx ty ax ay]
  (let [s (* (Math/sin radians) scale)
        c (* (Math/cos radians) scale)]
    (->RSXform (float c)
               (float s)
               (float (+ tx (- (* c ax)) (* s ay)))
               (float (+ ty (- (* s ax)) (- (* c ay)))))))

(defn rsxform->matrix
  "Convert RSXform to a 3x3 matrix (row-major, 9 floats).

   Useful for debugging or when you need the full matrix form."
  [{:keys [scos ssin tx ty]}]
  (float-array [scos (- ssin) tx
                ssin scos ty
                0 0 1]))

;; ============================================================
;; Quad - Sprite Region
;; ============================================================
;; A quad defines a rectangular region within a sprite sheet.
;; Inspired by Love2D's Quad object.

(defrecord Quad [^float x ^float y ^float w ^float h])

(defn quad
  "Define a sprite region (quad) within a sprite sheet.

   A quad specifies which portion of an image to draw, enabling
   sprite sheet workflows where many sprites are packed into one image.

   Args:
     x, y - top-left corner of the sprite in the sheet (pixels)
     w, h - width and height of the sprite (pixels)

   Returns:
     A Quad record that can be passed to `draw`

   Example:
     ;; First sprite in a 32x32 grid
     (def idle (quad 0 0 32 32))

     ;; Second sprite (next column)
     (def walk (quad 32 0 32 32))

     ;; Sprite on second row
     (def jump (quad 0 32 32 32))"
  [x y w h]
  (->Quad (float x) (float y) (float w) (float h)))

(defn quad-grid
  "Create a vector of quads for a regular grid sprite sheet.

   Useful for sprite sheets with uniformly-sized sprites arranged in a grid.

   Args:
     sprite-w    - width of each sprite
     sprite-h    - height of each sprite
     cols        - number of columns in the grid
     rows        - number of rows in the grid (default 1)
     start-x     - x offset for first sprite (default 0)
     start-y     - y offset for first sprite (default 0)

   Returns:
     Vector of Quads in row-major order (left-to-right, top-to-bottom)

   Example:
     ;; 4x2 grid of 32x32 sprites
     (def sprites (quad-grid 32 32 4 2))
     (nth sprites 0)  ; top-left sprite
     (nth sprites 4)  ; first sprite of second row"
  ([sprite-w sprite-h cols]
   (quad-grid sprite-w sprite-h cols 1 0 0))
  ([sprite-w sprite-h cols rows]
   (quad-grid sprite-w sprite-h cols rows 0 0))
  ([sprite-w sprite-h cols rows start-x start-y]
   (vec (for [row (range rows)
              col (range cols)]
          (quad (+ start-x (* col sprite-w))
                (+ start-y (* row sprite-h))
                sprite-w
                sprite-h)))))

;; ============================================================
;; Image Loading
;; ============================================================

(defn load-image
  "Load an image from a file path or resource.

   Supports PNG, JPEG, WebP, and other formats supported by Skia.
   The returned Image object can be used with `draw` and `draw-image`.

   Args:
     path - file path string, java.io.File, or resource path

   Returns:
     Skija Image object

   Throws:
     Exception if file not found or invalid image format

   Example:
     (def sprites (load-image \"assets/sprites.png\"))
     (def logo (load-image \"resources/logo.png\"))"
  [path]
  (let [file (io/file path)
        bytes (if (.exists file)
                (with-open [in (io/input-stream file)]
                  (let [baos (java.io.ByteArrayOutputStream.)]
                    (io/copy in baos)
                    (.toByteArray baos)))
                ;; Try as resource
                (if-let [resource (io/resource path)]
                  (with-open [in (io/input-stream resource)]
                    (let [baos (java.io.ByteArrayOutputStream.)]
                      (io/copy in baos)
                      (.toByteArray baos)))
                  (throw (ex-info (str "Image not found: " path) {:path path}))))]
    (Image/makeDeferredFromEncodedBytes bytes)))

(defn image-size
  "Get the dimensions of an image.

   Args:
     image - Skija Image object

   Returns:
     Vector [width height]"
  [^Image image]
  (let [info (.getImageInfo image)]
    [(.getWidth info) (.getHeight info)]))

;; ============================================================
;; Drawing
;; ============================================================

(defn- get-sampling-mode
  "Get SamplingMode from opts."
  [{:keys [filter sampling] :or {filter :linear}}]
  (cond
    sampling sampling
    (= filter :nearest) SamplingMode/DEFAULT
    (= filter :linear) SamplingMode/LINEAR
    (= filter :mitchell) SamplingMode/MITCHELL
    (= filter :catmull-rom) SamplingMode/CATMULL_ROM
    :else SamplingMode/LINEAR))

(defn draw
  "Draw a sprite from a sprite sheet.

   Uses a quad to select a region of the source image and draws it
   with optional transformations (rotation, scale, origin).

   Args:
     canvas - drawing canvas
     image  - source Image (sprite sheet)
     quad   - Quad defining the sprite region, or nil for entire image
     x, y   - destination position
     opts   - optional map with transform and style options:
              :rotation - rotation in radians (default 0)
              :scale    - uniform scale, or [sx sy] (default 1)
              :origin   - [ox oy] anchor point in sprite coords (default [0 0])
              :alpha    - opacity 0.0-1.0 (default 1.0)
              :color    - tint color (multiplied with image)
              :filter   - :linear or :nearest (default :linear)
              :flip-x   - flip horizontally (default false)
              :flip-y   - flip vertically (default false)

   Example:
     ;; Simple draw
     (draw canvas sheet player-quad 100 100)

     ;; With rotation around center
     (draw canvas sheet player-quad 100 100
       {:rotation (/ Math/PI 4)
        :origin [16 16]})

     ;; Scaled and semi-transparent
     (draw canvas sheet player-quad 100 100
       {:scale 2.0
        :alpha 0.5})

     ;; Flip horizontally (for facing direction)
     (draw canvas sheet player-quad 100 100
       {:flip-x true
        :origin [16 16]})"
  ([^Canvas canvas ^Image image quad dest-x dest-y]
   (draw canvas image quad dest-x dest-y {}))
  ([^Canvas canvas ^Image image quad dest-x dest-y opts]
   (let [;; Source rect from quad (or entire image)
         q (or quad
               (let [[iw ih] (image-size image)]
                 (->Quad 0 0 iw ih)))
         {:keys [^float x ^float y ^float w ^float h]} q
         src-rect (Rect/makeXYWH x y w h)

         ;; Parse transform options
         {:keys [rotation scale origin flip-x flip-y alpha]
          :or {rotation 0.0 scale 1.0 origin [0 0] alpha 1.0}} opts
         [ox oy] origin
         [sx sy] (if (vector? scale) scale [scale scale])
         sx (if flip-x (- sx) sx)
         sy (if flip-y (- sy) sy)

         ;; Sampling mode
         sampling (get-sampling-mode opts)

         ;; Calculate destination rect dimensions
         dst-w (* w (Math/abs (float sx)))
         dst-h (* h (Math/abs (float sy)))]

     ;; Apply transforms if needed
     (if (or (not= rotation 0.0)
             (not= sx 1.0)
             (not= sy 1.0)
             (not= ox 0)
             (not= oy 0))
       ;; With transforms: save/restore canvas state
       (do
         (.save canvas)
         ;; Translate to destination position
         (.translate canvas (float dest-x) (float dest-y))
         ;; Rotate around origin
         (when (not= rotation 0.0)
           (.rotate canvas (float (Math/toDegrees rotation))))
         ;; Scale
         (when (or (not= sx 1.0) (not= sy 1.0))
           (.scale canvas (float sx) (float sy)))
         ;; Draw at negative origin offset
         (let [dst-rect (Rect/makeXYWH (- (float ox)) (- (float oy)) w h)]
           (if (< alpha 1.0)
             (gfx/with-paint [paint {:alpha (int (* 255 alpha))}]
               (.drawImageRect canvas image src-rect dst-rect sampling paint true))
             (.drawImageRect canvas image src-rect dst-rect sampling nil true)))
         (.restore canvas))

       ;; No transforms: direct draw
       (let [dst-rect (Rect/makeXYWH (float dest-x) (float dest-y) dst-w dst-h)]
         (if (< alpha 1.0)
           (gfx/with-paint [paint {:alpha (int (* 255 alpha))}]
             (.drawImageRect canvas image src-rect dst-rect sampling paint true))
           (.drawImageRect canvas image src-rect dst-rect sampling nil true)))))))

(defn draw-image
  "Draw an entire image (convenience wrapper for draw without quad).

   Args:
     canvas - drawing canvas
     image  - source Image
     x, y   - destination position
     opts   - same options as `draw`

   Example:
     (draw-image canvas logo 10 10)
     (draw-image canvas logo 10 10 {:scale 0.5 :alpha 0.8})"
  ([^Canvas canvas ^Image image x y]
   (draw canvas image nil x y {}))
  ([^Canvas canvas ^Image image x y opts]
   (draw canvas image nil x y opts)))

;; ============================================================
;; Batch Drawing (drawTriangles - single draw call)
;; ============================================================
;; High-performance batch rendering using drawTriangles.
;; Renders N sprites in a SINGLE draw call with one JNI crossing.

(defn- fill-index-buffer!
  "Pre-fill index buffer for N quads.
   Pattern per quad: [0,1,2, 2,3,0] (two CCW triangles)."
  [^shorts indices n]
  (dotimes [i n]
    (let [vi (* i 4)   ; vertex base (4 vertices per quad)
          ii (* i 6)]  ; index base (6 indices per quad)
      (aset indices ii       (short vi))
      (aset indices (+ ii 1) (short (+ vi 1)))
      (aset indices (+ ii 2) (short (+ vi 2)))
      (aset indices (+ ii 3) (short (+ vi 2)))
      (aset indices (+ ii 4) (short (+ vi 3)))
      (aset indices (+ ii 5) (short vi)))))

(defn- transform-vertex
  "Transform a corner point by rotation, scale, and origin.
   Returns Point at world position."
  ^Point [cx cy ox oy sx sy cos-r sin-r x y]
  (let [;; Relative to origin
        lx (- cx ox)
        ly (- cy oy)
        ;; Apply scale
        slx (* lx sx)
        sly (* ly sy)
        ;; Apply rotation
        rx (- (* slx cos-r) (* sly sin-r))
        ry (+ (* slx sin-r) (* sly cos-r))]
    ;; Final position
    (Point. (float (+ x rx)) (float (+ y ry)))))

(defn- build-sprite-geometry
  "Build position, texcoord, color arrays for all sprites.

   Vertex order per quad (CCW):
   0=top-left, 1=top-right, 2=bottom-right, 3=bottom-left

   Returns {:positions Point[] :texcoords Point[] :colors int[] :indices short[]}"
  [sprites shared-alpha]
  (let [n (count sprites)
        positions (make-array Point (* n 4))
        texcoords (make-array Point (* n 4))
        colors    (int-array (* n 4))
        indices   (short-array (* n 6))]
    ;; Fill indices once
    (fill-index-buffer! indices n)

    ;; Process each sprite
    (dotimes [sprite-idx n]
      (let [sprite (nth sprites sprite-idx)
            [quad dest-x dest-y opts] (if (= 3 (count sprite))
                                        [(sprite 0) (sprite 1) (sprite 2) {}]
                                        sprite)
            ;; Extract quad dimensions
            qx (float (:x quad))
            qy (float (:y quad))
            w  (float (:w quad))
            h  (float (:h quad))
            x  (float dest-x)
            y  (float dest-y)

            ;; Parse transform options
            {:keys [rotation scale origin flip-x flip-y alpha color]
             :or {rotation 0.0 scale 1.0 origin [0 0] alpha 1.0 color 0xFFFFFFFF}} (or opts {})
            [ox oy] origin
            [sx sy] (if (vector? scale) scale [scale scale])
            sx (float (if flip-x (- sx) sx))
            sy (float (if flip-y (- sy) sy))
            rotation (float rotation)

            ;; Precompute trig
            cos-r (Math/cos rotation)
            sin-r (Math/sin rotation)

            ;; Combined alpha
            final-alpha (float (* shared-alpha alpha))

            ;; Build ARGB color with alpha
            ;; Note: color can be 0xFFFFFFFF which exceeds Integer.MAX_VALUE, use unchecked-int
            color-int (unchecked-int color)
            a (int (* 255.0 final-alpha))
            r (bit-and (bit-shift-right color-int 16) 0xFF)
            g (bit-and (bit-shift-right color-int 8) 0xFF)
            b (bit-and color-int 0xFF)
            argb (unchecked-int (bit-or (bit-shift-left a 24)
                                         (bit-shift-left r 16)
                                         (bit-shift-left g 8)
                                         b))

            ;; Texture coords (apply flip)
            tx0 (float (if flip-x (+ qx w) qx))
            tx1 (float (if flip-x qx (+ qx w)))
            ty0 (float (if flip-y (+ qy h) qy))
            ty1 (float (if flip-y qy (+ qy h)))

            ;; Vertex base index
            vi (* sprite-idx 4)

            ;; Transform 4 corners: [0,0], [w,0], [w,h], [0,h]
            p0 (transform-vertex 0 0 ox oy sx sy cos-r sin-r x y)
            p1 (transform-vertex w 0 ox oy sx sy cos-r sin-r x y)
            p2 (transform-vertex w h ox oy sx sy cos-r sin-r x y)
            p3 (transform-vertex 0 h ox oy sx sy cos-r sin-r x y)]

        ;; Write positions
        (aset positions vi       p0)
        (aset positions (+ vi 1) p1)
        (aset positions (+ vi 2) p2)
        (aset positions (+ vi 3) p3)

        ;; Write texcoords (pixel coordinates for shader)
        (aset texcoords vi       (Point. tx0 ty0))
        (aset texcoords (+ vi 1) (Point. tx1 ty0))
        (aset texcoords (+ vi 2) (Point. tx1 ty1))
        (aset texcoords (+ vi 3) (Point. tx0 ty1))

        ;; Write colors (same for all 4 vertices)
        (aset colors vi       argb)
        (aset colors (+ vi 1) argb)
        (aset colors (+ vi 2) argb)
        (aset colors (+ vi 3) argb)))

    {:positions positions
     :texcoords texcoords
     :colors    colors
     :indices   indices}))

(defn draw-batch
  "Draw N sprites in ONE draw call using drawTriangles.

   This is dramatically more efficient than drawing sprites individually,
   especially for particle systems, tilemaps, and bullet hell games.

   | Approach             | Draw Calls | JNI Crossings | GPU State Changes |
   |---------------------|------------|---------------|-------------------|
   | Individual draw     | N          | N             | N                 |
   | draw-batch          | 1          | 1             | 1                 |

   Args:
     canvas  - drawing canvas
     image   - source Image (sprite sheet)
     sprites - sequence of sprite specs, each is:
               [quad x y] or [quad x y opts]
               where opts can include:
               {:rotation 0.0     ; radians
                :scale 1.0        ; uniform or [sx sy]
                :origin [0 0]     ; anchor in sprite coords
                :flip-x false     ; flip horizontally
                :flip-y false     ; flip vertically
                :alpha 1.0        ; per-sprite opacity
                :color 0xFFFFFFFF ; tint ARGB}
     opts    - optional shared options:
               :alpha 1.0         ; shared opacity multiplier
               :filter :linear    ; :linear or :nearest

   Example:
     (draw-batch canvas sheet
       [[idle-quad 100 100]
        [walk-quad 200 100 {:flip-x true}]
        [jump-quad 300 100 {:rotation 0.1}]])"
  ([^Canvas canvas ^Image image sprites]
   (draw-batch canvas image sprites {}))
  ([^Canvas canvas ^Image image sprites opts]
   (when (seq sprites)
     (let [sprites-vec (vec sprites)
           {:keys [alpha filter] :or {alpha 1.0 filter :linear}} opts

           ;; Build geometry
           {:keys [positions texcoords colors indices]}
           (build-sprite-geometry sprites-vec (float alpha))

           ;; Create image shader with sampling mode
           sampling (case filter
                      :nearest SamplingMode/DEFAULT
                      :linear SamplingMode/LINEAR
                      SamplingMode/LINEAR)
           shader (.makeShader image FilterTileMode/CLAMP FilterTileMode/CLAMP
                               sampling nil)]

       (gfx/with-paint [paint {:shader shader}]
         (.drawTriangles canvas
                         positions
                         colors
                         texcoords
                         indices
                         BlendMode/MODULATE
                         paint))))))

;; ============================================================
;; Zero-Allocation Batch Mode
;; ============================================================

(defrecord BatchBuffers [positions texcoords ^ints colors ^shorts indices ^int max-sprites])

(defn make-batch-buffers
  "Pre-allocate reusable buffers for zero-allocation batch drawing.

   Use this for particle systems and other scenarios where you need
   maximum performance with no GC pressure.

   Args:
     max-sprites - maximum number of sprites these buffers can hold

   Returns:
     BatchBuffers record with pre-allocated arrays

   Example:
     (def buffers (make-batch-buffers 1000))

     ;; In render loop:
     (draw-batch! canvas sheet buffers sprites)"
  [max-sprites]
  (let [n max-sprites
        ;; Pre-allocate Point arrays (typed arrays for JNI)
        positions (make-array Point (* n 4))
        texcoords (make-array Point (* n 4))
        colors    (int-array (* n 4))
        indices   (short-array (* n 6))]
    ;; Initialize all Point slots (so we can reuse them)
    (dotimes [i (* n 4)]
      (aset ^"[Lio.github.humbleui.types.Point;" positions i (Point. 0 0))
      (aset ^"[Lio.github.humbleui.types.Point;" texcoords i (Point. 0 0)))
    ;; Pre-fill index buffer (never changes)
    (fill-index-buffer! indices n)
    (->BatchBuffers positions texcoords colors indices n)))

(defn- update-sprite-geometry!
  "Update pre-allocated buffers with sprite geometry.
   Mutates Point objects in-place for zero allocation.
   Returns number of sprites processed."
  [^"[Lio.github.humbleui.types.Point;" positions
   ^"[Lio.github.humbleui.types.Point;" texcoords
   ^ints colors
   sprites shared-alpha]
  (let [n (count sprites)]
    (dotimes [sprite-idx n]
      (let [sprite (nth sprites sprite-idx)
            [quad x y opts] (if (= 3 (count sprite))
                              [(sprite 0) (sprite 1) (sprite 2) {}]
                              sprite)
            qx (float (:x quad))
            qy (float (:y quad))
            w  (float (:w quad))
            h  (float (:h quad))

            ;; Parse transform options
            {:keys [rotation scale origin flip-x flip-y alpha color]
             :or {rotation 0.0 scale 1.0 origin [0 0] alpha 1.0 color 0xFFFFFFFF}} (or opts {})
            [ox oy] origin
            [sx sy] (if (vector? scale) scale [scale scale])
            sx (float (if flip-x (- sx) sx))
            sy (float (if flip-y (- sy) sy))
            rotation (float rotation)

            ;; Precompute trig
            cos-r (Math/cos rotation)
            sin-r (Math/sin rotation)

            ;; Combined alpha
            final-alpha (float (* shared-alpha alpha))

            ;; Build ARGB color with alpha
            ;; Note: color can be 0xFFFFFFFF which exceeds Integer.MAX_VALUE, use unchecked-int
            color-int (unchecked-int color)
            a (int (* 255.0 final-alpha))
            r (bit-and (bit-shift-right color-int 16) 0xFF)
            g (bit-and (bit-shift-right color-int 8) 0xFF)
            b (bit-and color-int 0xFF)
            argb (unchecked-int (bit-or (bit-shift-left a 24)
                                         (bit-shift-left r 16)
                                         (bit-shift-left g 8)
                                         b))

            ;; Texture coords (apply flip)
            tx0 (float (if flip-x (+ qx w) qx))
            tx1 (float (if flip-x qx (+ qx w)))
            ty0 (float (if flip-y (+ qy h) qy))
            ty1 (float (if flip-y qy (+ qy h)))

            ;; Vertex base index
            vi (* sprite-idx 4)]

        ;; Transform 4 corners and update Point objects in-place
        ;; Corner 0: top-left [0,0]
        (let [lx (- 0 ox) ly (- 0 oy)
              slx (* lx sx) sly (* ly sy)
              rx (- (* slx cos-r) (* sly sin-r))
              ry (+ (* slx sin-r) (* sly cos-r))]
          (aset positions vi (Point. (float (+ x rx)) (float (+ y ry)))))

        ;; Corner 1: top-right [w,0]
        (let [lx (- w ox) ly (- 0 oy)
              slx (* lx sx) sly (* ly sy)
              rx (- (* slx cos-r) (* sly sin-r))
              ry (+ (* slx sin-r) (* sly cos-r))]
          (aset positions (+ vi 1) (Point. (float (+ x rx)) (float (+ y ry)))))

        ;; Corner 2: bottom-right [w,h]
        (let [lx (- w ox) ly (- h oy)
              slx (* lx sx) sly (* ly sy)
              rx (- (* slx cos-r) (* sly sin-r))
              ry (+ (* slx sin-r) (* sly cos-r))]
          (aset positions (+ vi 2) (Point. (float (+ x rx)) (float (+ y ry)))))

        ;; Corner 3: bottom-left [0,h]
        (let [lx (- 0 ox) ly (- h oy)
              slx (* lx sx) sly (* ly sy)
              rx (- (* slx cos-r) (* sly sin-r))
              ry (+ (* slx sin-r) (* sly cos-r))]
          (aset positions (+ vi 3) (Point. (float (+ x rx)) (float (+ y ry)))))

        ;; Update texcoords
        (aset texcoords vi       (Point. tx0 ty0))
        (aset texcoords (+ vi 1) (Point. tx1 ty0))
        (aset texcoords (+ vi 2) (Point. tx1 ty1))
        (aset texcoords (+ vi 3) (Point. tx0 ty1))

        ;; Update colors
        (aset colors vi       argb)
        (aset colors (+ vi 1) argb)
        (aset colors (+ vi 2) argb)
        (aset colors (+ vi 3) argb)))
    n))

(defn draw-batch!
  "Render using pre-allocated buffers for zero GC pressure.

   This is the fastest path for high-frequency rendering like particle
   systems. The buffers are reused each frame, eliminating allocations.

   Args:
     canvas  - drawing canvas
     image   - source Image (sprite sheet)
     buffers - BatchBuffers from make-batch-buffers
     sprites - sequence of sprite specs (max buffers.max-sprites)
     opts    - optional shared options (same as draw-batch)

   Example:
     (def buffers (make-batch-buffers 1000))

     (defn render [canvas]
       (draw-batch! canvas sheet buffers particles))"
  ([^Canvas canvas ^Image image ^BatchBuffers buffers sprites]
   (draw-batch! canvas image buffers sprites {}))
  ([^Canvas canvas ^Image image ^BatchBuffers buffers sprites opts]
   (when (seq sprites)
     (let [sprites-vec (vec sprites)
           n (min (count sprites-vec) (:max-sprites buffers))
           {:keys [alpha filter] :or {alpha 1.0 filter :linear}} opts

           ;; Reuse pre-allocated buffers
           {:keys [positions texcoords ^ints colors ^shorts indices]} buffers

           ;; Build geometry into existing buffers
           actual-count (update-sprite-geometry! positions texcoords colors
                                                  (take n sprites-vec) (float alpha))]

       (when (> actual-count 0)
         ;; Create trimmed arrays for the actual count
         ;; (drawTriangles needs exact-size arrays)
         (let [used-positions (make-array Point (* actual-count 4))
               used-texcoords (make-array Point (* actual-count 4))
               used-colors    (int-array (* actual-count 4))
               used-indices   (short-array (* actual-count 6))
               ;; Create shader
               sampling (case filter
                          :nearest SamplingMode/DEFAULT
                          :linear SamplingMode/LINEAR
                          SamplingMode/LINEAR)
               shader (.makeShader image FilterTileMode/CLAMP FilterTileMode/CLAMP
                                   sampling nil)]
           ;; Copy only the used portion
           (System/arraycopy positions 0 used-positions 0 (* actual-count 4))
           (System/arraycopy texcoords 0 used-texcoords 0 (* actual-count 4))
           (System/arraycopy colors 0 used-colors 0 (* actual-count 4))
           (System/arraycopy indices 0 used-indices 0 (* actual-count 6))

           (gfx/with-paint [paint {:shader shader}]
             (.drawTriangles canvas
                             used-positions
                             used-colors
                             used-texcoords
                             used-indices
                             BlendMode/MODULATE
                             paint))))))))

;; ============================================================
;; Animation Helpers
;; ============================================================

(defn animation-frame
  "Get the current frame from an animation sequence.

   Useful for sprite animation based on time.

   Args:
     frames       - vector of quads (animation frames)
     time         - current time in seconds
     fps          - frames per second
     loop?        - whether to loop (default true)

   Returns:
     The appropriate Quad for the current time

   Example:
     (def walk-frames (quad-grid 32 32 4))  ; 4-frame walk cycle

     ;; In draw:
     (let [frame (animation-frame walk-frames @game-time 8)]
       (draw canvas sheet frame x y))"
  ([frames time fps]
   (animation-frame frames time fps true))
  ([frames time fps loop?]
   (let [frame-count (count frames)
         frame-idx (int (* time fps))]
     (if loop?
       (nth frames (mod frame-idx frame-count))
       (nth frames (min frame-idx (dec frame-count)))))))
