# Metal Video Playback - Add `Image.adoptMetalTextureFrom` to Skija

## Problem Statement

Video playback with Metal backend doesn't work because Skija is missing the Metal equivalent of `Image.adoptGLTextureFrom`.

**User Requirement**: Video must work as a proper Skia Image like OpenGL - with full support for clips, effects, and compositing. Must support 1080p without performance issues.

---

## Solution: Add Metal Bindings to Skija

**Key Discovery**: Skija source (`/Users/as186073/Downloads/Skija-master`) already has:
- `BackendRenderTarget._nMakeMetal` in `platform/cc/BackendRenderTarget.cc` (lines 26-39)
- Pattern for `Image.adoptGLTextureFrom` in `platform/cc/Image.cc` (lines 15-39)

We just need to add the Metal equivalent following the **exact same pattern**.

---

## Implementation Plan

### Step 1: Add C++ Native Method

**File**: `/Users/as186073/Downloads/Skija-master/platform/cc/Image.cc`

Add after line 39 (after the GL version):

```cpp
#ifdef SK_METAL
#include "include/gpu/ganesh/mtl/GrMtlBackendSurface.h"
#include "include/gpu/ganesh/mtl/GrMtlTypes.h"

extern "C" JNIEXPORT jlong JNICALL Java_io_github_humbleui_skija_Image__1nAdoptMetalTextureFrom
  (JNIEnv* env, jclass jclass, jlong contextPtr, jlong texturePtr, jint width, jint height, jint surfaceOrigin, jint colorType) {

    GrDirectContext* context = reinterpret_cast<GrDirectContext*>(static_cast<uintptr_t>(contextPtr));
    GrMTLHandle texture = reinterpret_cast<GrMTLHandle>(static_cast<uintptr_t>(texturePtr));

    GrMtlTextureInfo texInfo;
    texInfo.fTexture.retain(texture);

    GrBackendTexture backendTexture = GrBackendTextures::MakeMtl(
        width, height,
        skgpu::Mipmapped::kNo,
        texInfo
    );

    sk_sp<SkImage> image = SkImages::AdoptTextureFrom(
        context,
        backendTexture,
        static_cast<GrSurfaceOrigin>(surfaceOrigin),
        static_cast<SkColorType>(colorType)
    );

    return reinterpret_cast<jlong>(image.release());
}
#endif //SK_METAL
```

### Step 2: Add Java Wrapper

**File**: `/Users/as186073/Downloads/Skija-master/shared/java/Image.java`

Add after `adoptGLTextureFrom` method (around line 62):

```java
/**
 * Creates Image from an existing Metal texture.
 *
 * @param context       DirectContext
 * @param texturePtr    Metal texture pointer (MTLTexture)
 * @param width         texture width
 * @param height        texture height
 * @param surfaceOrigin texture origin (TOP_LEFT for video)
 * @param colorType     color type (BGRA_8888 for Metal)
 * @return              Image
 */
public static Image adoptMetalTextureFrom(DirectContext context, long texturePtr, int width, int height, SurfaceOrigin surfaceOrigin, ColorType colorType) {
    try {
        Stats.onNativeCall();
        long ptr = _nAdoptMetalTextureFrom(Native.getPtr(context),
                                        texturePtr,
                                        width,
                                        height,
                                        surfaceOrigin.ordinal(),
                                        colorType.ordinal());
        if (ptr == 0)
            throw new RuntimeException("Failed to adoptMetalTextureFrom " + texturePtr + " " + width + "x" + height);
        return new Image(ptr);
    } finally {
        ReferenceUtil.reachabilityFence(context);
    }
}
```

Add native declaration (around line 537):

```java
@ApiStatus.Internal public static native long _nAdoptMetalTextureFrom(long contextPtr, long texturePtr, int width, int height, int surfaceOrigin, int colorType);
```

### Step 3: Rebuild Skija

```bash
cd /Users/as186073/Downloads/Skija-master
python3 script/build.py --arch arm64
```

This builds both the native library (with new `_nAdoptMetalTextureFrom`) and Java classes (with new `adoptMetalTextureFrom` method).

### Step 4: Update deps.edn to Use Local Skija

**File**: `deps.edn`

Replace Maven artifact with local JAR:

```clojure
;; Before (Maven):
io.github.humbleui/skija-macos-arm64 {:mvn/version "0.143.5" :exclusions [io.github.humbleui/types]}

;; After (Local):
io.github.humbleui/skija-macos-arm64 {:local/root "/Users/as186073/Downloads/Skija-master/target/skija-macos-arm64-0.143.6.jar"
                                      :exclusions [io.github.humbleui/types]}
```

Or install to local Maven:
```bash
cd /Users/as186073/Downloads/Skija-master
mvn install:install-file -Dfile=target/skija-macos-arm64-0.143.6.jar \
    -DgroupId=io.github.humbleui \
    -DartifactId=skija-macos-arm64 \
    -Dversion=0.143.6-local \
    -Dpackaging=jar
```

Then in deps.edn:
```clojure
io.github.humbleui/skija-macos-arm64 {:mvn/version "0.143.6-local" :exclusions [io.github.humbleui/types]}
```

### Step 5: Use in Video Decoder

**File**: `src/lib/video/hwaccel/zero_copy.clj`

```clojure
(defn wrap-metal-texture-as-skia-image
  "Wrap a Metal texture as a Skia Image using adoptMetalTextureFrom."
  [direct-context mtl-texture-ptr width height]
  (io.github.humbleui.skija.Image/adoptMetalTextureFrom
    direct-context
    mtl-texture-ptr
    (int width)
    (int height)
    SurfaceOrigin/TOP_LEFT
    ColorType/BGRA_8888))
```

---

## Files to Modify

### In Skija (`/Users/as186073/Downloads/Skija-master`)

| File | Changes |
|------|---------|
| `platform/cc/Image.cc` | Add `_nAdoptMetalTextureFrom` native method |
| `shared/java/Image.java` | Add `adoptMetalTextureFrom` public method + native declaration |

### In Project (`clj-reload-skija`)

| File | Changes |
|------|---------|
| `deps.edn` | Point to local Skija build with Metal support |
| `src/lib/video/hwaccel/videotoolbox_metal.clj` | Fix binder to return valid MTLTexture |
| `src/lib/video/hwaccel/zero_copy.clj` | Use `Image/adoptMetalTextureFrom` for Metal path |
| `src/lib/video/core.clj` | Remove blit-based draw-frame! |
| `src/lib/video/render_metal.clj` | DELETE (no longer needed) |

---

## Why This Works

1. **Same pattern as OpenGL**: Following exact same pattern as `adoptGLTextureFrom`
2. **Native API exists**: `GrBackendTextures::MakeMtl()` + `SkImages::AdoptTextureFrom()` are in Skia
3. **Already proven**: `BackendRenderTarget.makeMetal` uses identical pattern and works
4. **Zero-copy**: Metal texture → Skia Image directly, no CPU involvement
5. **Full Skia integration**: Clips, effects, compositing all work

---

## Verification Plan

### Test 1: Build Skija
```bash
cd /Users/as186073/Downloads/Skija-master
./script/build.py --arch arm64 --only-native
# Should compile without errors
```

### Test 2: Video Playback
```bash
clj -M:dev:macos-arm64 -e "(quick-open :howto/video-demo)"
# Video should display with rounded corners
```

### Test 3: 1080p Performance
- Load a 1080p video
- FPS should remain stable (60fps)
- No stuttering or frame drops

---

## Risk Assessment

| Risk | Likelihood | Mitigation |
|------|-----------|-----------|
| Skija build fails | Low | Follow existing build scripts |
| Metal texture format mismatch | Medium | Ensure BGRA format from VideoToolbox |
| Texture lifetime issues | Medium | Manage texture refs properly |

---

## Alternative: Submit PR to Skija

After verifying this works, submit PR to HumbleUI/Skija:
- Adds value to the community
- Gets code review from maintainers
- Removes need to maintain fork long-term

## Implementation Order

1. **Modify Skija** - Add `Image.adoptMetalTextureFrom` (C++ and Java)
2. **Build Skija** - Compile with Metal support
3. **Update deps.edn** - Point to local Skija build
4. **Fix videotoolbox_metal.clj** - Ensure valid MTLTexture is returned
5. **Modify zero_copy.clj** - Use `adoptMetalTextureFrom` for Metal path
6. **Test** - Verify video plays with clips/effects
7. **Cleanup** - Remove old blit-based code
