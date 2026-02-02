(ns lib.video.hwaccel.hw-protocol
  "Protocols for zero-copy hardware-accelerated video decoding.

   These protocols define the interface for platform-specific zero-copy
   implementations where decoded frames stay GPU-resident, eliminating
   the GPU->CPU->GPU copy path.

   Platform implementations:
   - macOS: VideoToolbox -> CVPixelBuffer -> IOSurface -> GL texture
   - Linux Intel/AMD: VAAPI -> VASurface -> DMA-BUF -> EGLImage -> GL texture
   - Linux NVIDIA: NVDEC/CUDA -> CUDA buffer -> GL texture (via interop)
   - Windows: D3D11VA -> D3D11 texture -> GL texture (via WGL_NV_DX_interop)")

;; ============================================================
;; Hardware Frame Protocol
;; ============================================================

(defprotocol HardwareFrame
  "Protocol for GPU-resident decoded video frames.

   Implementations hold platform-specific handles to GPU memory:
   - macOS: CVPixelBufferRef -> IOSurfaceRef
   - Linux VAAPI: VASurfaceID -> DMA-BUF fd -> EGLImage
   - Linux CUDA: CUDA device pointer
   - Windows: ID3D11Texture2D -> WGL handle

   The frame data never touches CPU memory - it's bound directly
   to an OpenGL texture for Skia rendering."

  (bind-to-texture! [frame texture-info]
    "Bind this hardware frame to an OpenGL texture (zero-copy).

     texture-info is a map containing:
       :texture-id    - OpenGL texture ID (int)
       :texture-type  - :texture-2d or :texture-rectangle
       :width, :height - Texture dimensions
       :cuda-resource - (CUDA only) Registered CUDA graphics resource
       :wgl-handle    - (D3D11 only) WGL interop handle

     Returns true on success, false on failure.

     After binding, the GL texture contains the frame data and can
     be wrapped as a Skia Image for rendering.")

  (get-dimensions [frame]
    "Get frame dimensions.
     Returns [width height] vector.")

  (get-pixel-format [frame]
    "Get the native pixel format of the frame.
     Returns a keyword like :nv12, :bgra, :rgba, etc.

     This is important because:
     - VideoToolbox typically outputs NV12 or BGRA
     - VAAPI outputs NV12
     - CUDA can output various formats
     - D3D11 outputs NV12 or BGRA")

  (release! [frame]
    "Release native resources associated with this frame.

     Called when the frame is no longer needed. Some platforms
     need explicit cleanup (e.g., EGLImage, DMA-BUF fd) while
     others are managed by the decoder (e.g., CUDA buffers).

     Safe to call multiple times."))

;; ============================================================
;; Hardware Decoder Protocol
;; ============================================================

(defprotocol HardwareDecoder
  "Protocol for hardware-accelerated video decoders that provide
   direct access to GPU-resident frames.

   Unlike JavaCV's FFmpegFrameGrabber which copies hardware frames
   to CPU memory, implementations of this protocol expose the raw
   hardware frame handles for true zero-copy rendering.

   Implementations use JavaCPP's raw FFmpeg bindings to:
   1. Create hardware device context (AVHWDeviceContext)
   2. Configure codec to output hardware frames
   3. Access AVFrame.data[3] which contains the hardware frame pointer"

  (decode-next-hw-frame! [decoder]
    "Decode the next video frame and return as HardwareFrame.

     Returns a HardwareFrame implementation, or nil at EOF.

     The returned frame holds a reference to GPU memory. Call
     release! on the frame when done to free resources.

     Note: Some platforms reuse frame buffers, so the previous
     frame may become invalid after calling this.")

  (seek-hw! [decoder seconds]
    "Seek to position in seconds.

     After seeking, the next call to decode-next-hw-frame! will
     return the frame at or after the requested position.

     Returns the decoder for chaining.")

  (tell-hw [decoder]
    "Get current decoder position in seconds.

     This is the PTS of the last decoded frame.")

  (get-hw-decoder-info [decoder]
    "Get information about the hardware decoder.

     Returns a map with:
       :decoder-type  - :videotoolbox, :vaapi, :cuda, :d3d11
       :width, :height - Video dimensions
       :fps           - Frame rate
       :duration      - Total duration in seconds
       :pixel-format  - Native output format")

  (close-hw! [decoder]
    "Close the decoder and release all resources.

     Closes the FFmpeg contexts and frees GPU memory.
     Safe to call multiple times."))

;; ============================================================
;; Texture Info Helper
;; ============================================================

(defn make-texture-info
  "Create a texture-info map for bind-to-texture!

   Required keys:
     :texture-id   - OpenGL texture ID
     :texture-type - :texture-2d or :texture-rectangle
     :width        - Texture width in pixels
     :height       - Texture height in pixels

   Platform-specific optional keys:
     :cuda-resource - CUDA graphics resource (for NVIDIA)
     :wgl-handle    - WGL interop handle (for Windows D3D11)
     :egl-display   - EGL display (for Linux VAAPI)"
  [texture-id texture-type width height & {:as opts}]
  (merge {:texture-id   texture-id
          :texture-type texture-type
          :width        width
          :height       height}
         opts))

;; ============================================================
;; Frame Pool Protocol (Optional)
;; ============================================================

(defprotocol HardwareFramePool
  "Optional protocol for decoders that manage a pool of frame buffers.

   Hardware decoders often use a fixed pool of GPU buffers for
   decoded frames. This protocol provides visibility into pool
   state for debugging and performance tuning."

  (pool-size [pool]
    "Get the total number of frames in the pool.")

  (available-frames [pool]
    "Get the number of frames currently available for decoding.")

  (wait-for-frame! [pool timeout-ms]
    "Wait for a frame buffer to become available.
     Returns true if a frame is available, false on timeout."))

;; ============================================================
;; Zero-Copy Capability Detection
;; ============================================================

(defprotocol ZeroCopyCapability
  "Protocol for checking zero-copy capability at runtime.

   Not all hardware combinations support zero-copy. For example:
   - macOS needs CGL context (not EGL)
   - Linux VAAPI needs EGL context (not GLX)
   - Windows D3D11 needs WGL_NV_DX_interop extension"

  (zero-copy-available? [detector]
    "Check if zero-copy is available on current system.
     Returns true if zero-copy can be used.")

  (zero-copy-requirements [detector]
    "Get requirements for zero-copy on current platform.

     Returns a map with:
       :gl-context-type - Required GL context (:cgl, :egl, :wgl)
       :extensions      - Required GL/platform extensions
       :driver-version  - Minimum driver version (if applicable)
       :notes           - Human-readable notes"))
