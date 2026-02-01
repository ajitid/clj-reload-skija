# Plan: GPU-Accelerated Video Playback with Skia Effects

## Goal

Implement video playback that:
1. Uses hardware/GPU decoding (VideoToolbox/VAAPI/NVDEC per platform)
2. Renders video frames directly to OpenGL textures (zero-copy where possible)
3. Wraps textures as Skia Images for effects (rounded corners, shaders, filters)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Video File (.mp4, .webm, etc.)                  │
└───────────────────────────────┬─────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    FFmpeg (via JavaCPP/JavaCV)                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               │
│  │ VideoToolbox │  │    VAAPI     │  │  NVDEC/CUDA  │               │
│  │   (macOS)    │  │   (Linux)    │  │   (NVIDIA)   │               │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘               │
└─────────┼─────────────────┼─────────────────┼───────────────────────┘
          ▼                 ▼                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│               Platform-Specific GPU Memory / Texture                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               │
│  │  IOSurface   │  │   DMA-BUF    │  │ CUDA Buffer  │               │
│  │ CVPixelBuf   │  │  EGLImage    │  │              │               │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘               │
└─────────┼─────────────────┼─────────────────┼───────────────────────┘
          ▼                 ▼                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       OpenGL Texture                                │
│           (glTexImage2D or zero-copy import)                        │
└───────────────────────────────┬─────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                Image.adoptGLTextureFrom(ctx, texId, ...)            │
│                        (Skija 0.116.4+)                             │
└───────────────────────────────┬─────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Skia Canvas Drawing                              │
│         canvas.drawImageRect(image, src, dst, paint)                │
│         + RRect clipping, shaders, filters, etc.                    │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Current State

- **Skija version**: 0.143.5 (includes `adoptGLTextureFrom` from 0.116.4)
- **DirectContext**: Already exposed via `lib/window/layer.clj:77-80`
- **OpenGL context**: Created by SDL3 via LWJGL in `lib/window/internal.clj`

---

## Implementation Strategy

**Priority**: GPU hardware decode first, CPU software decode as fallback.

```clojure
;; Decoder selection order (lib/video/hwaccel/detect.clj)
(defn select-decoder [platform gpu-vendor]
  (case platform
    :macos   :videotoolbox
    :linux   (case gpu-vendor
               :nvidia :nvdec-cuda
               (:amd :intel) :vaapi)
    :windows :d3d11va  ; works for all vendors
    :software))        ; fallback

;; Runtime: try hwaccel, fallback to software on failure
(defn create-decoder [path]
  (or (try-hwaccel-decoder path)
      (create-software-decoder path)))
```

---

## Implementation Phases

### Phase 1: CPU-Path Baseline (JavaCV + glTexImage2D)

**Goal**: Get video playing with Skia effects, accepting CPU→GPU copy per frame.
**Purpose**: Establishes the Skia integration pattern; becomes the fallback path.

**Files to create/modify**:
- `src/lib/video/core.clj` - Public API
- `src/lib/video/decoder.clj` - JavaCV FFmpegFrameGrabber wrapper
- `src/lib/video/texture.clj` - OpenGL texture management + Skia integration
- `deps.edn` - Add JavaCV dependency

**API Design**:
```clojure
(ns lib.video.core)

;; Load video (returns video-source handle)
(defn from-file [path opts])  ; opts: {:hw-accel? true}

;; Playback control
(defn play! [source])
(defn pause! [source])
(defn seek! [source time-seconds])
(defn tell [source])        ; current position
(defn duration [source])

;; Frame access for rendering
(defn current-frame [source])  ; returns Skia Image (GPU-backed)

;; Cleanup
(defn close! [source])
```

**Key implementation**:
```clojure
;; lib/video/texture.clj
(defn frame->skia-image
  "Convert decoded frame to Skia Image via OpenGL texture."
  [frame texture-id direct-context]
  (let [buf   ^ByteBuffer (aget (.image frame) 0)
        w     (.imageWidth frame)
        h     (.imageHeight frame)]
    ;; Upload to GL texture
    (GL11/glBindTexture GL11/GL_TEXTURE_2D texture-id)
    (GL11/glTexSubImage2D GL11/GL_TEXTURE_2D 0 0 0 w h
                          GL12/GL_BGR GL11/GL_UNSIGNED_BYTE buf)
    ;; Wrap as Skia Image
    (Image/adoptGLTextureFrom
      direct-context
      texture-id
      GL11/GL_TEXTURE_2D
      w h
      GL11/GL_RGBA8
      SurfaceOrigin/TOP_LEFT  ; video is top-down
      ColorType/RGBA_8888)))
```

**Dependencies to add**:
```clojure
;; deps.edn
org.bytedeco/javacv {:mvn/version "1.5.10"}
org.bytedeco/ffmpeg {:mvn/version "6.1.1-1.5.10" :classifier "macosx-arm64"}
;; (similar for other platforms)
```

---

### Phase 2: Hardware Decode (Platform-Specific)

**Goal**: Keep decoded frames in GPU memory, zero-copy to OpenGL texture.
**Fallback**: If hwaccel unavailable, use CPU decode (Phase 1 path).

#### Platform Coverage

| Platform | Decoder | Zero-Copy Path |
|----------|---------|----------------|
| macOS (Apple Silicon/Intel) | VideoToolbox | IOSurface → CGLTexImageIOSurface2D |
| Linux (Intel) | VAAPI | DMA-BUF → EGLImage → GL |
| Linux (AMD) | VAAPI (Mesa radeonsi) | DMA-BUF → EGLImage → GL |
| Linux (NVIDIA) | NVDEC/CUDA | CUDA buffer → cuGraphicsGLRegisterImage |
| Windows (AMD) | AMF | D3D11 → GL interop (complex) or Vulkan |
| Windows (NVIDIA) | NVDEC | D3D11VA → GL interop |
| Windows (Intel) | QSV/D3D11VA | D3D11 → GL interop |

#### macOS (VideoToolbox)

**Path**: `VideoToolbox → CVPixelBuffer → IOSurface → CGLTexImageIOSurface2D → GL Texture`

**Key APIs** (need JNA/JNI wrappers):
```c
// Get IOSurface from CVPixelBuffer
IOSurfaceRef surface = CVPixelBufferGetIOSurface(pixelBuffer);

// Create GL texture from IOSurface (zero-copy)
CGLTexImageIOSurface2D(cglContext, GL_TEXTURE_RECTANGLE,
                       GL_RGBA, width, height,
                       GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV,
                       surface, 0);
```

**FFmpeg setup**:
```clojure
;; Enable VideoToolbox hwaccel
(doto codec-context
  (.hw_device_ctx (create-hw-device-ctx AV_HWDEVICE_TYPE_VIDEOTOOLBOX)))
```

#### Linux (VAAPI + EGL) — Intel & AMD

**Path**: `VAAPI → VASurface → DMA-BUF fd → EGLImage → GL Texture`

**Drivers**:
- Intel: `i965-va-driver` or `intel-media-driver`
- AMD: Mesa `radeonsi` (open source, supports VAAPI)

**Key APIs**:
```c
// Export VAAPI surface as DMA-BUF
vaExportSurfaceHandle(va_display, surface_id,
                      VA_SURFACE_ATTRIB_MEM_TYPE_DRM_PRIME_2,
                      VA_EXPORT_SURFACE_READ_ONLY, &descriptor);

// Import DMA-BUF as EGLImage
EGLImage image = eglCreateImageKHR(egl_display, EGL_NO_CONTEXT,
                                   EGL_LINUX_DMA_BUF_EXT, NULL, attribs);

// Bind EGLImage to GL texture
glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, image);
```

**Note**: Requires EGL context (not GLX). SDL3 can create EGL contexts on Linux.

#### Linux (NVIDIA) — NVDEC/CUDA

**Path**: `NVDEC → CUDA buffer → cuGraphicsGLRegisterImage → GL Texture`

**Note**: NVIDIA doesn't support VAAPI+EGL properly. Need CUDA interop path.

#### Windows — D3D11 Interop (All GPUs)

**Path**: `D3D11VA/NVDEC/AMF → D3D11 Texture → WGL_NV_DX_interop → GL Texture`

**Note**: Windows requires D3D11↔OpenGL interop via `WGL_NV_DX_interop` extension (works on NVIDIA, AMD, Intel).
Alternative: Use ANGLE (OpenGL ES on D3D11) which handles this internally.

---

### Phase 3: Audio Sync

**Options**:
1. **Use existing Java Sound** for audio track (simplest)
2. **Add OpenAL** for lower latency (already in LWJGL)
3. **Sync logic**: Track video PTS, schedule frame display to match audio clock

---

## File Structure

```
src/lib/video/
├── core.clj           ;; Public API (from-file, play!, seek!, current-frame)
├── decoder.clj        ;; FFmpeg decoding wrapper (hwaccel or software fallback)
├── texture.clj        ;; GL texture pool + Skia Image wrapping
├── hwaccel/
│   ├── detect.clj        ;; Detect available hwaccel on current platform
│   ├── videotoolbox.clj  ;; macOS: IOSurface → GL (via JNA)
│   ├── vaapi.clj         ;; Linux Intel/AMD: DMA-BUF → EGL → GL
│   ├── cuda.clj          ;; Linux NVIDIA: CUDA → GL interop
│   └── d3d11.clj         ;; Windows all GPUs: D3D11 → GL interop
└── sync.clj           ;; Audio/video sync clock (PTS-based)
```

---

## Verification

### Phase 1 Test
```bash
clj -M:dev:macos-arm64 -e "(open :howto/video-demo)"
```
1. Video plays in a rounded rectangle
2. Can apply blur shader to video
3. Seek works smoothly
4. No visible tearing or stutter at 30fps content

### Phase 2 Test
```bash
# Check GPU decode is active
ffprobe -v debug video.mp4 2>&1 | grep -i videotoolbox
```
1. CPU usage should be low during playback
2. 4K video plays smoothly
3. Multiple video instances don't degrade performance

---

## Key Challenges & Mitigations

| Challenge | Mitigation |
|-----------|------------|
| `adoptGLTextureFrom` API undocumented | Check Skija source, reference issue #72 |
| macOS IOSurface needs native code | Create minimal JNA wrapper or use JavaCPP |
| Linux EGL vs GLX | Ensure SDL3 creates EGL context on Linux |
| Linux AMD VAAPI | Uses same Mesa radeonsi path as Intel, should work |
| Linux NVIDIA no VAAPI+EGL | Use CUDA interop path instead |
| Windows D3D11→GL interop | Use WGL_NV_DX_interop extension (works all vendors) |
| Audio sync complexity | Start with simple frame-rate matching, add PTS sync later |
| Texture format mismatch (YUV vs RGB) | Use FFmpeg swscale or GPU shader for conversion |
| Fallback to CPU | Detect hwaccel failure gracefully, use software decode |

---

## Dependencies Summary

```clojure
;; Required
org.bytedeco/javacv {:mvn/version "1.5.10"}

;; Platform-specific FFmpeg natives
org.bytedeco/ffmpeg {:mvn/version "6.1.1-1.5.10"
                     :classifier "macosx-arm64"}  ;; or linux-x86_64, windows-x86_64

;; Optional: For JNA wrappers to platform APIs
net.java.dev.jna/jna {:mvn/version "5.14.0"}
net.java.dev.jna/jna-platform {:mvn/version "5.14.0"}
```

---

## References

- [Skija adoptGLTextureFrom (Issue #72)](https://github.com/HumbleUI/Skija/issues/72)
- [FFmpeg Hardware Context System](https://deepwiki.com/FFmpeg/FFmpeg/7.1-hardware-context-system)
- [VAAPI EGL Zero-Copy Demo](https://github.com/fmor/demo_ffmpeg_vaapi_gl)
- [VideoToolbox IOSurface Demo](https://github.com/jyavenard/DecodeTest)
- [JavaCV FFmpegFrameGrabber](https://github.com/bytedeco/javacv)
