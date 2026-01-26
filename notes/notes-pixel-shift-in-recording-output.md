# Recording Pixel Shift Analysis

_Please see the very bottom first_

## Issue
Recorded video shows constant 1-2 pixel vertical jitter (content shifts up/down alternating between frames), while live UI display looks fine. Recording at native resolution, no scaling.

## Recording Pipeline (capture.clj)
```
Render → Flush → glReadPixels(PBO) → get-previous-frame → queue → worker → FFmpeg(vflip) → MP4
```

## Root Cause Analysis

After reviewing the code, I identified **one most likely cause** and two secondary possibilities:

### **PRIMARY: Missing GL_PACK_ALIGNMENT** (capture.clj:153)
The `glReadPixels` call doesn't set `GL_PACK_ALIGNMENT`. While RGBA (4 bytes/pixel) should be naturally aligned, the default `GL_PACK_ALIGNMENT=4` can still cause subtle issues on some GPU drivers when combined with vflip.

**Evidence**: The symptom of 1-2 pixel constant jitter is classic for row alignment mismatch during vertical flip operations.

### Secondary: Buffer position state (capture.clj:180-184)
```clojure
(let [mapped (GL15/glMapBuffer ...)]
  (when mapped
    (let [result (MemoryUtil/memAlloc size)]
      (.put result (.asReadOnlyBuffer mapped))  ;; <-- mapped position not guaranteed to be 0
      (.flip result)
```
The `.asReadOnlyBuffer` creates a view with independent position, but the original `mapped` buffer's position may not be at 0. If it's offset by a row, every frame would have the same shift.

### Secondary: vflip + YUV420P interaction (capture.clj:496)
The output uses `-pix_fmt yuv420p` which has 2x2 chroma subsampling. If vflip operates on the wrong stage of the colorspace conversion pipeline, it could cause 1-pixel vertical artifacts.

## Fix Plan

### Step 1: Add GL_PACK_ALIGNMENT (capture.clj:141-159)
In `start-async-read!`, before `glReadPixels`:
```clojure
(GL11/glPixelStorei GL11/GL_PACK_ALIGNMENT 1)
```

### Step 2: Explicitly rewind mapped buffer (capture.clj:180)
In `get-previous-frame`, after glMapBuffer:
```clojure
(when mapped
  (.rewind mapped)  ;; Ensure position is 0
  ...)
```

### Step 3: If still broken, diagnostic test
Temporarily change `build-vf-filter` to output without vflip (video will be upside-down). If no jitter, the issue is in FFmpeg's vflip filter + yuv420p interaction.

## Files to Modify
- `src/lib/window/capture.clj` (lines ~151 and ~180)

---

`.rewind()` just sets an integer (position = 0). It's a single field assignment - nanoseconds, no memory copy, no I/O.

What each does:

| Rewind         | Where                 | Why                                                                                                                                       |
|----------------|-----------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| `.rewind mapped` | After `glMapBuffer`     | The mapped PBO buffer's position should be 0, but isn't guaranteed. If it's off by one row (width×4 bytes), you get vertical pixel shift. |
| `.rewind pixels` | Before `.get` in worker | Defensive. The buffer travels through a queue between threads. If anything touched it, position could drift.                              |



`.rewind mapped` (essential):
- Ensures we copy the correct data from the PBO
- If skipped and mapped's position is off, we copy wrong pixels into result
- The data itself would be corrupted - no later fix possible

`.rewind pixels` (defensive):
- After .flip in get-previous-frame, position is already 0
- The queue just passes a reference, shouldn't modify position
- This is a "just in case" safety net

## The very bottom

> Turns out there was no issue at all. The player I was using to view the content actually was shifting for some reason the video. When I played that video on Chrome, Chrome's player, no shift happens. So what do you suggest? What would you suggest? Should we like remove all these three? guards and fixes or should we keep them? because they're not giving any benefit right now?

⏺ Good to know it was the video player, not the code!

My recommendation: Keep all three fixes. Here's why:

| Fix                   | Cost          | Benefit                                                   |
|-----------------------|---------------|-----------------------------------------------------------|
| GL_PACK_ALIGNMENT = 1 | ~1 nanosecond | Ensures no row padding on any GPU driver/width combo      |
| .rewind mapped        | ~1 nanosecond | Guarantees PBO buffer position is 0 (not spec-guaranteed) |
| .rewind pixels        | ~1 nanosecond | Defensive - buffer crosses thread boundary                |

All three are essentially free (just integer assignments) and represent defensive programming that prevents potential issues on:
- Different GPU drivers
- Edge-case window dimensions
- Future code changes that might touch buffer positions

They don't hurt performance and they make the code more robust. I'd keep them.