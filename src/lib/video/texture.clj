(ns lib.video.texture
  "OpenGL texture management for video frames.
   Handles CPU→GPU upload and Skia Image wrapping.

   Supports both GL_TEXTURE_2D (standard) and GL_TEXTURE_RECTANGLE
   (used for macOS IOSurface zero-copy path)."
  (:import [org.lwjgl.opengl GL11 GL12]
           [io.github.humbleui.skija ColorType Image SurfaceOrigin]
           [java.nio ByteBuffer]))

;; GL format constants
(def ^:private GL_RGBA8 0x8058)
(def ^:private GL_UNPACK_ROW_LENGTH 0x0CF2)
(def ^:private GL_TEXTURE_RECTANGLE 0x84F5)
(def ^:private GL_BGRA 0x80E1)
(def ^:private GL_UNSIGNED_INT_8_8_8_8_REV 0x8367)

(defn create-texture
  "Create an OpenGL texture suitable for video frames.
   Initializes with black pixels to avoid 'texture unloadable' errors.
   Returns texture ID (int)."
  [width height]
  (let [tex-id (GL11/glGenTextures)
        ;; Create a black buffer for initialization
        init-size (* width height 4)
        init-buf (doto (ByteBuffer/allocateDirect init-size)
                   (.limit init-size))]
    ;; Fill with black (already zero-initialized by allocateDirect)
    (GL11/glBindTexture GL11/GL_TEXTURE_2D tex-id)
    ;; Set texture parameters
    (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
    (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
    (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_WRAP_S GL12/GL_CLAMP_TO_EDGE)
    (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_WRAP_T GL12/GL_CLAMP_TO_EDGE)
    ;; Allocate and initialize texture with black data
    (GL11/glTexImage2D GL11/GL_TEXTURE_2D 0 GL_RGBA8 width height 0
                       GL11/GL_RGBA GL11/GL_UNSIGNED_BYTE init-buf)
    (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)
    tex-id))

(defn update-texture-with-stride!
  "Upload new frame data to existing texture with proper stride handling.

   buffer: ByteBuffer containing RGBA pixel data
   stride: Row stride in bytes (from JavaCV frame.imageStride)
   channels: Number of channels (should be 4 for RGBA)

   Uses GL_UNPACK_ROW_LENGTH to handle FFmpeg's memory alignment padding."
  [texture-id width height ^ByteBuffer buffer stride channels]
  ;; Save current GL state that we'll modify
  (let [prev-texture (GL11/glGetInteger GL11/GL_TEXTURE_BINDING_2D)
        prev-unpack-row-length (GL11/glGetInteger GL_UNPACK_ROW_LENGTH)]
    (try
      (GL11/glBindTexture GL11/GL_TEXTURE_2D texture-id)
      (.rewind buffer)
      ;; Calculate row length in pixels
      ;; FFmpeg stride is in bytes, GL_UNPACK_ROW_LENGTH is in pixels
      (let [row-length (int (/ stride channels))]
        ;; Always set row length to handle any stride
        (GL11/glPixelStorei GL_UNPACK_ROW_LENGTH row-length)
        ;; Upload texture data - use GL_RGBA since we're outputting RGBA from FFmpeg
        (GL11/glTexSubImage2D GL11/GL_TEXTURE_2D 0 0 0 width height
                              GL11/GL_RGBA GL11/GL_UNSIGNED_BYTE buffer))
      (finally
        ;; Always restore GL state to avoid polluting other rendering
        (GL11/glPixelStorei GL_UNPACK_ROW_LENGTH prev-unpack-row-length)
        (GL11/glBindTexture GL11/GL_TEXTURE_2D prev-texture)))))

(defn update-texture!
  "Upload new frame data to existing texture (legacy, assumes tight packing).
   Use update-texture-with-stride! for proper stride handling."
  ([texture-id width height ^ByteBuffer buffer]
   (update-texture-with-stride! texture-id width height buffer (* width 4) 4))
  ([texture-id width height ^ByteBuffer buffer format]
   ;; Ignore format parameter, always use RGBA now
   (update-texture-with-stride! texture-id width height buffer (* width 4) 4)))

(defn delete-texture!
  "Delete an OpenGL texture."
  [texture-id]
  (when (and texture-id (pos? texture-id))
    (GL11/glDeleteTextures texture-id)))

(defn wrap-as-skia-image
  "Wrap an OpenGL texture as a Skia Image.

   direct-context: Skia DirectContext (from lib.window.layer/context)
   texture-id: OpenGL texture ID
   width, height: Texture dimensions

   Returns a Skia Image that can be drawn on canvas.
   Note: The returned Image does NOT own the texture - caller manages lifecycle.

   Uses Image/adoptGLTextureFrom which takes:
   (context, textureId, glTarget, width, height, internalFormat, surfaceOrigin, colorType)"
  [direct-context texture-id width height]
  ;; Flush any pending GL commands before Skia adopts the texture
  (GL11/glFlush)
  (Image/adoptGLTextureFrom
    direct-context
    (int texture-id)
    GL11/GL_TEXTURE_2D   ; GL target
    (int width)
    (int height)
    GL_RGBA8             ; internal format
    SurfaceOrigin/TOP_LEFT  ; Video frames are top-down
    ColorType/RGBA_8888))

;; ============================================================
;; Rectangle Texture Support (for macOS IOSurface zero-copy)
;; ============================================================

(defn create-rectangle-texture
  "Create an OpenGL rectangle texture (GL_TEXTURE_RECTANGLE).

   Used for macOS IOSurface zero-copy path where CGLTexImageIOSurface2D
   binds the IOSurface directly to a rectangle texture.

   Rectangle textures use pixel coordinates (0..width, 0..height)
   instead of normalized coordinates (0..1).

   Returns texture ID (int)."
  [width height]
  (let [tex-id (GL11/glGenTextures)]
    (GL11/glBindTexture GL_TEXTURE_RECTANGLE tex-id)
    ;; Set texture parameters
    (GL11/glTexParameteri GL_TEXTURE_RECTANGLE GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
    (GL11/glTexParameteri GL_TEXTURE_RECTANGLE GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
    (GL11/glTexParameteri GL_TEXTURE_RECTANGLE GL11/GL_TEXTURE_WRAP_S GL11/GL_CLAMP)
    (GL11/glTexParameteri GL_TEXTURE_RECTANGLE GL11/GL_TEXTURE_WRAP_T GL11/GL_CLAMP)
    (GL11/glBindTexture GL_TEXTURE_RECTANGLE 0)
    tex-id))

(defn delete-rectangle-texture!
  "Delete a rectangle texture."
  [texture-id]
  (when (and texture-id (pos? texture-id))
    (GL11/glDeleteTextures texture-id)))

(defn wrap-rectangle-texture-as-skia-image
  "Wrap a GL_TEXTURE_RECTANGLE as a Skia Image.

   direct-context: Skia DirectContext (from lib.window.layer/context)
   texture-id: OpenGL texture ID (GL_TEXTURE_RECTANGLE)
   width, height: Texture dimensions

   Returns a Skia Image that can be drawn on canvas.
   Note: The returned Image does NOT own the texture - caller manages lifecycle."
  [direct-context texture-id width height]
  (GL11/glFlush)
  (Image/adoptGLTextureFrom
    direct-context
    (int texture-id)
    GL_TEXTURE_RECTANGLE
    (int width)
    (int height)
    GL_RGBA8
    SurfaceOrigin/TOP_LEFT
    ColorType/RGBA_8888))

(defn wrap-texture-as-skia-image-generic
  "Wrap any GL texture as a Skia Image, supporting both 2D and rectangle.

   direct-context: Skia DirectContext
   texture-id: OpenGL texture ID
   texture-type: :texture-2d or :texture-rectangle
   width, height: Texture dimensions

   Returns a Skia Image."
  [direct-context texture-id texture-type width height]
  (GL11/glFlush)
  (let [gl-target (if (= texture-type :texture-rectangle)
                    GL_TEXTURE_RECTANGLE
                    GL11/GL_TEXTURE_2D)]
    (Image/adoptGLTextureFrom
      direct-context
      (int texture-id)
      gl-target
      (int width)
      (int height)
      GL_RGBA8
      SurfaceOrigin/TOP_LEFT
      ColorType/RGBA_8888)))

;; Texture pool for reusing textures across frames
(defonce ^:private texture-pool (atom {}))

(defn acquire-texture!
  "Get or create a texture of the given size.
   Returns texture-id."
  [width height]
  (let [key [width height]]
    (if-let [tex-id (get @texture-pool key)]
      tex-id
      (let [tex-id (create-texture width height)]
        (swap! texture-pool assoc key tex-id)
        tex-id))))

(defn release-all-textures!
  "Delete all pooled textures."
  []
  (doseq [[_ tex-id] @texture-pool]
    (delete-texture! tex-id))
  (reset! texture-pool {}))
