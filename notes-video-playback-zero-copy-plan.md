# Plan: Zero-Copy Video Decoding Across All Platforms

## Goal

Implement true zero-copy video decoding where decoded frames stay GPU-resident, eliminating the current GPU→CPU→GPU copy path. This provides significant benefits for 4K content and multiple simultaneous videos.

---

## Current vs Target Architecture

**Current (hwaccel enabled but frames still copy through CPU):**
```
FFmpeg HW Decode → CPU Buffer (JavaCV) → glTexSubImage2D → GL Texture → Skia
                        ↑ Extra copy
```

**Target (true zero-copy):**
```
FFmpeg HW Decode → Platform GPU Memory → GL Texture (interop) → Skia
                   No CPU involvement
```

---

## Platform-Specific Zero-Copy Paths

| Platform | Decoder | Zero-Copy Path |
|----------|---------|----------------|
| **macOS** | VideoToolbox | CVPixelBuffer → IOSurface → `CGLTexImageIOSurface2D` → GL_TEXTURE_RECTANGLE |
| **Linux Intel/AMD** | VAAPI | VASurface → DMA-BUF fd → EGLImage → `glEGLImageTargetTexture2DOES` → GL_TEXTURE_2D |
| **Linux NVIDIA** | NVDEC/CUDA | CUDA buffer → `cuGraphicsGLRegisterImage` + `cuMemcpy2D` → GL_TEXTURE_2D |
| **Windows** | D3D11VA | D3D11 Texture → `WGL_NV_DX_interop` → GL_TEXTURE_2D |

---

## Key Technical Challenge

JavaCV's `FFmpegFrameGrabber.grabImage()` internally calls `av_hwframe_transfer_data()` which copies hardware frames to CPU memory. To get zero-copy, we must:

1. Use **JavaCPP raw FFmpeg bindings** to access `AVFrame.data[3]` (hardware frame pointer)
2. Bypass JavaCV's Frame abstraction entirely for the zero-copy path

---

## Implementation Plan

### Phase 1: Foundation

**New Files:**

1. **`src/lib/video/hwaccel/hw_protocol.clj`** - New protocols
   ```clojure
   (defprotocol HardwareFrame
     (bind-to-texture! [frame texture-info] "Bind hw frame to GL texture, returns true on success")
     (get-dimensions [frame] "Returns [width height]")
     (release! [frame] "Release native resources"))

   (defprotocol HardwareDecoder
     (decode-next-hw-frame! [decoder] "Returns HardwareFrame or nil at EOF")
     (seek-hw! [decoder seconds])
     (close-hw! [decoder]))
   ```

2. **`src/lib/video/hwaccel/raw_decoder.clj`** - Raw FFmpeg decoder using JavaCPP
   - Direct AVFormatContext, AVCodecContext management
   - Hardware device context creation per platform
   - Access to `AVFrame.data[3]` for hardware frame pointer

3. **`src/lib/video/hwaccel/zero_copy.clj`** - Unified zero-copy decoder
   - Implements existing `VideoSource` protocol
   - Platform dispatch based on `detect.clj`
   - Fallback to current CPU-copy path on failure

**Modify:**

4. **`src/lib/video/texture.clj`** - Add rectangle texture support
   - `create-rectangle-texture` for macOS IOSurface
   - `wrap-rectangle-texture-as-skia-image` using `Image/adoptGLTextureFrom`

### Phase 2: macOS VideoToolbox (First Platform)

**Modify `src/lib/video/hwaccel/videotoolbox.clj`:**

- JNA bindings already exist for IOSurface/CVPixelBuffer
- Add `VideoToolboxFrame` implementing `HardwareFrame`:
  ```clojure
  (defrecord VideoToolboxFrame [cv-pixel-buffer io-surface width height]
    HardwareFrame
    (bind-to-texture! [_ texture-info]
      (bind-iosurface-to-texture! (:texture-id texture-info) io-surface width height))
    (get-dimensions [_] [width height])
    (release! [_]
      ;; CVPixelBuffer is autoreleased when AVFrame is unreffed
      nil))
  ```

- Add `create-vt-decoder` implementing `HardwareDecoder`
- Extract CVPixelBufferRef from `AVFrame.data[3]` after decode

**Handle GL_TEXTURE_RECTANGLE:**
- IOSurface binds to `GL_TEXTURE_RECTANGLE` (pixel coordinates, not normalized)
- Option A: Check if Skia supports rectangle textures directly
- Option B: Render to FBO with GL_TEXTURE_2D attachment (one GPU-GPU copy)

### Phase 3: Linux VAAPI (Intel/AMD)

**Modify `src/lib/video/hwaccel/vaapi.clj`:**

Add JNA bindings for:
- `libva.so`: `vaExportSurfaceHandle` (export VASurface as DMA-BUF fd)
- `libEGL.so`: `eglCreateImageKHR` with `EGL_LINUX_DMA_BUF_EXT`
- GL extension: `glEGLImageTargetTexture2DOES`

```clojure
(defrecord VAAPIFrame [va-surface dma-buf-fd egl-image width height]
  HardwareFrame
  (bind-to-texture! [_ texture-info]
    ;; glEGLImageTargetTexture2DOES binds EGLImage to GL texture
    (gl-egl-image-target-texture! (:texture-id texture-info) egl-image))
  (release! [_]
    (egl-destroy-image! egl-image)
    (close-dma-buf-fd! dma-buf-fd)))
```

**Requirement:** SDL3 must create EGL context (not GLX). Add detection in `detect.clj`.

### Phase 4: Linux NVIDIA CUDA

**Modify `src/lib/video/hwaccel/cuda.clj`:**

Add JNA bindings for CUDA driver API:
- `cuGraphicsGLRegisterImage` (register GL texture with CUDA, once per texture)
- `cuGraphicsMapResources` / `cuGraphicsUnmapResources` (per frame)
- `cuMemcpy2D` (GPU-to-GPU copy from CUDA buffer to mapped GL texture)

```clojure
(defrecord CUDAFrame [cuda-device-ptr cuda-pitch width height]
  HardwareFrame
  (bind-to-texture! [_ texture-info]
    (let [cuda-resource @(:cuda-resource texture-info)]
      (cu-graphics-map-resources! cuda-resource)
      (cu-memcpy-2d! cuda-device-ptr cuda-pitch cuda-resource width height)
      (cu-graphics-unmap-resources! cuda-resource)))
  (release! [_] nil)) ;; CUDA buffer owned by AVFrame
```

### Phase 5: Windows D3D11

**Modify `src/lib/video/hwaccel/d3d11.clj`:**

Add JNA bindings for WGL extensions:
- `wglDXOpenDeviceNV` (open D3D11 device for sharing, once)
- `wglDXRegisterObjectNV` (register D3D11 texture with GL)
- `wglDXLockObjectsNV` / `wglDXUnlockObjectsNV` (per frame)

```clojure
(defrecord D3D11Frame [d3d11-texture wgl-handle width height]
  HardwareFrame
  (bind-to-texture! [_ texture-info]
    (wgl-dx-lock-objects! wgl-handle)
    ;; GL texture now contains D3D11 texture contents
    true)
  (release! [_]
    (wgl-dx-unlock-objects! wgl-handle)))
```

### Phase 6: Integration

**Modify `src/lib/video/core.clj`:**
- Add `:zero-copy?` option to `from-file` (default: true)
- Wire up `ZeroCopyDecoder` with fallback chain

**Fallback Chain:**
```
Zero-copy HW decode → HW decode + CPU copy → Software decode
         ↓ fail              ↓ fail              ↓
    (current impl)     (current impl)      (baseline)
```

---

## Critical Files

| File | Action | Purpose |
|------|--------|---------|
| `src/lib/video/hwaccel/hw_protocol.clj` | Create | HardwareFrame, HardwareDecoder protocols |
| `src/lib/video/hwaccel/raw_decoder.clj` | Create | JavaCPP raw FFmpeg decoder |
| `src/lib/video/hwaccel/zero_copy.clj` | Create | Unified zero-copy decoder with dispatch |
| `src/lib/video/hwaccel/videotoolbox.clj` | Modify | Add VideoToolboxFrame, create-vt-decoder |
| `src/lib/video/hwaccel/vaapi.clj` | Modify | Add VAAPIFrame, EGL/DMA-BUF bindings |
| `src/lib/video/hwaccel/cuda.clj` | Modify | Add CUDAFrame, CUDA/GL interop |
| `src/lib/video/hwaccel/d3d11.clj` | Modify | Add D3D11Frame, WGL_NV_DX_interop |
| `src/lib/video/hwaccel/detect.clj` | Modify | Add EGL, CUDA, WGL extension checks |
| `src/lib/video/texture.clj` | Modify | Add rectangle texture support |
| `src/lib/video/core.clj` | Modify | Add :zero-copy? option, wire up decoder |

---

## Dependencies

```clojure
;; Already present
org.bytedeco/javacv {:mvn/version "1.5.10"}
org.bytedeco/ffmpeg {:mvn/version "6.1.1-1.5.10" :classifier "macosx-arm64"}
net.java.dev.jna/jna {:mvn/version "5.14.0"}
net.java.dev.jna/jna-platform {:mvn/version "5.14.0"}
```

No new dependencies needed - JavaCPP (via JavaCV) provides raw FFmpeg access.

---

## Verification

### Per-Platform Tests

```bash
# macOS
clj -M:dev:macos-arm64 -e "(do (require '[lib.video.hwaccel.detect :as d]) (prn (d/decoder-info)))"
# Should show {:decoder :videotoolbox ...}

clj -M:dev:macos-arm64 -e "(open :howto/video-demo)"
# Play 4K video, verify low CPU usage
```

### Performance Validation

1. **CPU Usage**: During 4K playback, CPU should be <10% (vs ~30-50% with CPU copy)
2. **Frame Time**: Consistent frame times without GC spikes
3. **Memory**: No large ByteBuffer allocations per frame

### Fallback Verification

```clojure
;; Force software fallback
(video/from-file "test.mp4" {:zero-copy? false})
;; Should work with CPU copy path
```

---

## Implementation Order

1. **Phase 1**: Foundation (protocols, raw decoder skeleton)
2. **Phase 2**: macOS VideoToolbox (first working platform)
3. **Phase 3**: Linux VAAPI (Intel/AMD)
4. **Phase 4**: Linux NVIDIA CUDA
5. **Phase 5**: Windows D3D11
6. **Phase 6**: Integration and polish

Start with macOS since JNA bindings already exist in `videotoolbox.clj`.

---

## References

- [VideoToolbox IOSurface Demo](https://github.com/jyavenard/DecodeTest)
- [VAAPI EGL Zero-Copy Demo](https://github.com/fmor/demo_ffmpeg_vaapi_gl)
- [CUDA/GL Interop](https://docs.nvidia.com/cuda/cuda-runtime-api/group__CUDART__OPENGL.html)
- [WGL_NV_DX_interop Spec](https://registry.khronos.org/OpenGL/extensions/NV/WGL_NV_DX_interop.txt)
- [JavaCPP FFmpeg AVFrame API](http://bytedeco.org/javacpp-presets/ffmpeg/apidocs/org/bytedeco/ffmpeg/avutil/AVFrame.html)
