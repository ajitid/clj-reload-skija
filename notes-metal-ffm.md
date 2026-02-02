# Plan: Metal Frame Capture via FFM (Foreign Function & Memory API)

## Problem

The capture system uses OpenGL-specific APIs (PBOs, glFenceSync) that don't work with Metal backend. The current Skia `readPixels` fallback is synchronous and causes ~1-2ms stalls per frame.

**Root cause**: LWJGL's JNI doesn't support enough arguments for Metal's struct-passing methods like `MTLTexture.getBytes:bytesPerRow:fromRegion:mipmapLevel:` (needs 12 args including MTLRegion struct fields).

## Solution: FFM for Async Metal Capture

Use Java's FFM API (stable in JDK 22+, project uses JDK 25) to:
1. Properly pass MTLOrigin/MTLSize structs by value
2. Call `MTLBlitCommandEncoder.copyFromTexture:toBuffer:` for async GPU-side DMA
3. Mirror OpenGL PBO double-buffer pattern: capture current frame, process previous

### Why FFM?
- **Built into JDK 22+** - no external dependencies
- **Proper struct handling** - MemoryLayout handles ARM64 ABI automatically
- **Near-native performance** - ~15-30ns call overhead after JIT warmup
- **Type-safe** - FunctionDescriptor validates signatures at creation time

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      Frame N Render Loop                        │
├─────────────────────────────────────────────────────────────────┤
│ 1. Draw to canvas                                               │
│ 2. Flush Skia → Metal texture                                   │
│ 3. [CAPTURE] Create blit encoder                                │
│ 4. [CAPTURE] copyFromTexture → MTLBuffer[N % 2]  (GPU async)   │
│ 5. [CAPTURE] End encoding, commit command buffer                │
│ 6. Present drawable                                             │
│ 7. [CAPTURE] Poll MTLBuffer[(N-1) % 2] status                   │
│ 8. [CAPTURE] If ready: memcpy to ByteBuffer, queue to FFmpeg    │
└─────────────────────────────────────────────────────────────────┘

MTLBuffer[0] ◄───► MTLBuffer[1]  (double-buffered, shared storage)
     │                   │
     └───────────────────┴──► CPU reads via .contents pointer
```

**Key insight**: GPU blit copy runs in parallel with CPU processing of previous frame. Zero stalls.

---

## Implementation Phases

### Phase 1: FFM Foundation (`src/lib/window/metal_ffm.clj`) - NEW FILE

**Struct Layouts:**
```clojure
(ns lib.window.metal-ffm
  (:import [java.lang.foreign Arena FunctionDescriptor Linker
            MemoryLayout MemorySegment SymbolLookup ValueLayout]))

;; MTLOrigin = { NSUInteger x, y, z } = 24 bytes
(def ^:private MTL-ORIGIN-LAYOUT
  (MemoryLayout/structLayout
    (.withName (ValueLayout/JAVA_LONG) "x")
    (.withName (ValueLayout/JAVA_LONG) "y")
    (.withName (ValueLayout/JAVA_LONG) "z")))

;; MTLSize = { NSUInteger width, height, depth } = 24 bytes
(def ^:private MTL-SIZE-LAYOUT
  (MemoryLayout/structLayout
    (.withName (ValueLayout/JAVA_LONG) "width")
    (.withName (ValueLayout/JAVA_LONG) "height")
    (.withName (ValueLayout/JAVA_LONG) "depth")))
```

**objc_msgSend Downcall Handle:**
```clojure
;; For copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:
;;     toBuffer:destinationOffset:destinationBytesPerRow:destinationBytesPerImage:
(def ^:private BLIT-COPY-DESCRIPTOR
  (FunctionDescriptor/ofVoid
    (ValueLayout/ADDRESS)     ; self (blit encoder)
    (ValueLayout/ADDRESS)     ; _cmd (selector)
    (ValueLayout/ADDRESS)     ; sourceTexture
    (ValueLayout/JAVA_LONG)   ; sourceSlice
    (ValueLayout/JAVA_LONG)   ; sourceLevel
    MTL-ORIGIN-LAYOUT         ; sourceOrigin (struct by value)
    MTL-SIZE-LAYOUT           ; sourceSize (struct by value)
    (ValueLayout/ADDRESS)     ; destinationBuffer
    (ValueLayout/JAVA_LONG)   ; destinationOffset
    (ValueLayout/JAVA_LONG)   ; destinationBytesPerRow
    (ValueLayout/JAVA_LONG))) ; destinationBytesPerImage

(defn- get-blit-copy-handle []
  (let [linker (Linker/nativeLinker)
        libobjc (SymbolLookup/libraryLookup "/usr/lib/libobjc.dylib" (Arena/global))
        addr (.orElseThrow (.find libobjc "objc_msgSend"))]
    (.downcallHandle linker addr BLIT-COPY-DESCRIPTOR)))
```

**Public API:**
```clojure
(defn copy-texture-to-buffer!
  "Issue async GPU blit from texture to buffer via FFM.
   Returns immediately - GPU copy runs asynchronously."
  [blit-encoder selector texture slice level
   origin-x origin-y origin-z
   size-w size-h size-d
   buffer offset bytes-per-row bytes-per-image]
  ...)
```

### Phase 2: Async Capture State (`src/lib/window/capture_metal.clj`) - MODIFY

**New State Structure:**
```clojure
(defonce capture-state
  (atom {:mtl-buffers    [nil nil]    ; MTLBuffer pointers (double-buffered)
         :cmd-buffers    [nil nil]    ; Command buffers for completion tracking
         :buffer-index   0            ; Current write target (ping-pong)
         :width          0
         :height         0
         :initialized?   false
         :primed?        false}))
```

**New Functions:**
- `init-async-buffers!` - Create shared-storage MTLBuffers
- `destroy-async-buffers!` - Release MTLBuffers
- `issue-async-blit!` - Submit GPU copy command (non-blocking)
- `poll-and-read-buffer` - Check completion, read pixels if ready

### Phase 3: Integration (`src/lib/window/core.clj`) - MODIFY

**Updated render-frame-metal!:**
```clojure
(defn- render-frame-metal! [window]
  (let [[pw ph] (get-window-size-in-pixels handle)]
    (when-let [{:keys [surface canvas texture flush-fn present-fn]}
               (layer-metal/frame! pw ph)]
      (when (dispatch-event! window (->EventFrameSkija surface canvas))
        ;; 1. Flush Skia
        (flush-fn)

        ;; 2. CAPTURE: Issue async blit (before present, texture still valid)
        (when @capture-active?
          (capture-metal/issue-async-blit! texture pw ph))

        ;; 3. Present
        (present-fn)

        ;; 4. CAPTURE: Process previous frame (non-blocking poll)
        (when @capture-active?
          (capture/process-frame! pw ph scale :metal))))))
```

---

## Files to Modify/Create

| File | Action | Description |
|------|--------|-------------|
| `src/lib/window/metal_ffm.clj` | **CREATE** | FFM bindings for struct-passing Metal methods |
| `src/lib/window/capture_metal.clj` | **MODIFY** | Async blit capture using FFM |
| `src/lib/window/core.clj` | **MODIFY** | Hook async blit before present |
| `src/lib/window/capture.clj` | **MODIFY** | Backend dispatch for Metal async path |
| `src/lib/window/metal.clj` | **KEEP** | Existing LWJGL JNI (simple methods still use this) |
| `src/lib/window/layer_metal.clj` | **KEEP** | Already exposes texture in frame! return |

---

## Detailed Flow: Async Blit Copy

```
Frame N:
  ┌─────────────────────────────────────────────────────────────┐
  │ 1. Skia draws to Metal texture                              │
  │ 2. flush-fn (Skia → Metal commands submitted)               │
  │ 3. Create blit command encoder from queue                   │
  │ 4. FFM: copyFromTexture(texture) → MTLBuffer[0]             │
  │    └─ GPU DMA copy, returns immediately                     │
  │ 5. endEncoding, commit command buffer                       │
  │ 6. Store command buffer for status tracking                 │
  │ 7. present-fn (drawable presented)                          │
  │ 8. Check MTLBuffer[1] cmd status (poll, non-blocking)       │
  │    └─ Not ready yet (first frame) → skip                    │
  └─────────────────────────────────────────────────────────────┘

Frame N+1:
  ┌─────────────────────────────────────────────────────────────┐
  │ ... same as above, but blit to MTLBuffer[1] ...             │
  │ 8. Check MTLBuffer[0] cmd status                            │
  │    └─ status == Completed!                                  │
  │ 9. Read pixels: memcpy(buffer[0].contents → ByteBuffer)     │
  │ 10. Queue ByteBuffer to FFmpeg worker thread                │
  └─────────────────────────────────────────────────────────────┘
```

---

## Error Handling & Fallback

```clojure
(defn capture-frame! [texture width height]
  (try
    ;; Primary: FFM async blit
    (issue-async-blit! texture width height)
    (catch UnsupportedOperationException e
      ;; FFM not available (old Java)
      (println "[capture-metal] FFM unavailable, using Skia fallback")
      (capture-surface-skia! surface width height))
    (catch Exception e
      ;; FFM call failed
      (println "[capture-metal] FFM error:" (.getMessage e))
      (capture-surface-skia! surface width height))))
```

**Fallback chain:**
1. FFM async blit (optimal)
2. Skia `readPixels` (works but synchronous)

---

## Verification

1. **Basic capture test:**
   ```clojure
   (open :playground/ball-spring)
   (lib.window.capture/screenshot! "test.png" :png)
   ;; Verify: image exists, correct content, not flipped
   ```

2. **Recording test:**
   ```clojure
   (lib.window.capture/start-recording! "test.mp4" {:fps 60})
   ;; Wait 5 seconds
   (lib.window.capture/stop-recording!)
   ;; Verify: video plays, smooth, no dropped frames
   ```

3. **Performance test:**
   ```clojure
   ;; Check console during recording - should see no "Frame dropped" warnings
   ;; Frame time should remain consistent (no spikes from sync reads)
   ```

4. **Fallback test:**
   ```clojure
   ;; Temporarily break FFM path, verify Skia fallback works
   ```

---

## Dependencies

**No new dependencies required!** FFM is built into JDK 22+.

**JVM flags** (may be needed):
```bash
clj -J--enable-native-access=ALL-UNNAMED -A:dev:macos-arm64
```

Or add to `deps.edn` alias:
```clojure
:dev {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}
```

---

## Performance Comparison

| Approach | Latency | Stall | Notes |
|----------|---------|-------|-------|
| OpenGL PBO | 1 frame | Zero | Current working implementation |
| **FFM async blit** | 1 frame | Zero | Mirrors PBO pattern for Metal |
| Skia readPixels | 0 frames | ~1-2ms | Synchronous, blocks render |
| LWJGL JNI (broken) | N/A | N/A | Can't pass structs |

---

## References

- [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454)
- [MTLBlitCommandEncoder.copyFromTexture](https://developer.apple.com/documentation/metal/mtlblitcommandencoder/1400756-copyfromtexture)
- [ARM64 Procedure Call Standard](https://github.com/ARM-software/abi-aa/blob/main/aapcs64/aapcs64.rst)
- [Dissecting objc_msgSend on ARM64](https://www.mikeash.com/pyblog/friday-qa-2017-06-30-dissecting-objc_msgsend-on-arm64.html)
