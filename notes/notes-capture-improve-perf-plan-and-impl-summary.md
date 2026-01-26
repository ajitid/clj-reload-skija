---
name: Capture Performance Improvements
overview: "Implement three performance improvements to video capture: hardware encoder selection, glFenceSync for async PBO reads, and a worker thread for FFmpeg writes."
todos:
  - id: hw-encoder
    content: Add hardware encoder detection and platform-specific FFmpeg args
    status: pending
  - id: fence-sync
    content: Add glFenceSync to PBO reads to avoid blocking on incomplete transfers
    status: pending
  - id: worker-thread
    content: Create worker thread with bounded queue for FFmpeg writes
    status: pending
---

# Capture Performance Improvements

Optimize video recording by offloading work from the render thread and using hardware encoders.

## Changes to [src/lib/window/capture.clj](src/lib/window/capture.clj)

### 1. Hardware Encoder Selection

Add platform detection and hardware encoder support:

```clojure
(defn- get-hw-encoder []
  (let [os (System/getProperty "os.name")]
    (cond
      (str/includes? os "Mac")   "h264_videotoolbox"
      (str/includes? os "Linux") "h264_vaapi"
      (str/includes? os "Win")   "h264_nvenc"
      :else                      "libx264")))
```

Update `spawn-ffmpeg-recording!` to use detected encoder with platform-specific args:

- macOS: `-c:v h264_videotoolbox`
- Linux: `-vaapi_device /dev/dri/renderD128 -vf format=nv12,hwupload,vflip -c:v h264_vaapi`
- Windows: `-c:v h264_nvenc`
- Fallback: `-c:v libx264 -preset fast`

### 2. glFenceSync for Async PBO Reads

Add fence tracking per PBO to avoid blocking on incomplete GPU transfers:

```clojure
;; Add to pbo-state
:fences [nil nil]  ; Fence sync objects per PBO

;; After glReadPixels, create fence:
(GL32/glFenceSync GL32/GL_SYNC_GPU_COMMANDS_COMPLETE 0)

;; Before glMapBuffer, check fence:
(GL32/glClientWaitSync fence 0 0)  ; Non-blocking check
```

Import `org.lwjgl.opengl.GL32` for fence functions.

### 3. Worker Thread for FFmpeg Writes

Create a bounded queue (4 frames) and worker thread:

```clojure
(defonce worker-state
  (atom {:queue nil        ; LinkedBlockingQueue
         :thread nil       ; Worker Thread
         :running? false}))

;; Worker thread loop:
(while running?
  (when-let [frame (.poll queue 100 TimeUnit/MILLISECONDS)]
    (write-frame-data! frame)))

;; In process-frame!, instead of write-frame-to-ffmpeg!:
(if (.offer queue frame-data)
  :queued
  (do (MemoryUtil/memFree pixels) :dropped))
```

Frame ownership: render thread allocates, worker thread frees.

## Key Code Locations

| Change | Location | Lines |

|--------|----------|-------|

| Encoder detection | New function `get-hw-encoder` | ~15 lines |

| FFmpeg args | `spawn-ffmpeg-recording!` | Lines 318-365 |

| Fence sync | `start-async-read!`, `get-previous-frame` | Lines 121-155 |

| Worker thread | New `worker-state`, `start-worker!`, `stop-worker!` | ~50 lines |

| Queue integration | `process-frame!`, `write-frame-to-ffmpeg!` | Lines 446-516 |

## Design Decisions

- **Queue size:** 4 frames (~32MB at 1080p)
- **Backpressure:** Drop new frame when queue full (don't block render)
- **Linux encoder:** Default to VAAPI (Intel/AMD); NVIDIA users can override
- **Fallback:** If hardware encoder fails, fall back to libx264

----

post impl:

Here are the key benefits:
1. Hardware Encoder — Offloads H.264 encoding from CPU to GPU. On a Mac, VideoToolbox can encode 1080p60 using ~5% CPU vs ~40%+ with libx264. Similar gains on Windows (NVENC) and Linux (VAAPI).
2. glFenceSync — The render thread no longer blocks waiting for GPU→CPU pixel transfers to complete. Previously `glMapBuffer` could stall if the DMA wasn't finished; now we check a fence first and skip if not ready, keeping frame times consistent.
3. Worker Thread — FFmpeg writes (which can stall on disk I/O or encoder backpressure) now happen off the render thread. The render loop just drops a frame pointer into a queue and continues immediately. This eliminates the biggest source of frame hitches during recording.
Net result: Recording video should now have minimal impact on frame rate and frame time consistency, especially at high resolutions or frame rates.

---

## Implementation Summary

I've implemented all three performance improvements from the plan. Here's a summary of the changes made to `src/lib/window/capture.clj`:

## 1. Hardware Encoder Selection

Added two functions to detect and use platform-specific hardware encoders:

- `get-hw-encoder` - Detects OS and returns the appropriate encoder:
  - macOS: `h264_videotoolbox`
  - Linux: `h264_vaapi`
  - Windows: `h264_nvenc`
  - Fallback: `libx264`

- `get-encoder-args` - Returns platform-specific FFmpeg arguments for each encoder

The `spawn-ffmpeg-recording!` function now tries the hardware encoder first and automatically falls back to `libx264` if it fails.

## 2. glFenceSync for Async PBO Reads

Added fence synchronization to avoid blocking on incomplete GPU transfers:

- Added `:fences [nil nil]` to `pbo-state` to track fence sync objects per PBO
- `start-async-read!` now creates a fence after `glReadPixels` using `GL32/glFenceSync`
- `get-previous-frame` checks the fence with `GL32/glClientWaitSync` (non-blocking) before mapping the buffer
- `destroy-pbos!` now properly cleans up fences with `GL32/glDeleteSync`

## 3. Worker Thread for FFmpeg Writes

Created a separate worker thread with a bounded queue (4 frames, ~32MB at 1080p):

- Added `worker-state` atom with queue, thread, and running flag
- `worker-loop` - Main loop that polls the queue and writes frames to FFmpeg
- `start-worker!` / `stop-worker!` - Lifecycle management
- `queue-frame!` - Non-blocking queue with backpressure (drops frames when full)
- Frame ownership: render thread allocates memory, worker thread frees it

The recording flow now queues frames instead of blocking on FFmpeg writes, and frames are drained on shutdown to ensure no data is lost.