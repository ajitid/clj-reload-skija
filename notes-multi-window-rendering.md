# Conversation: Can you look at the code and Tell me, like the architecture we are using, I suppose, like we have th

**Date:** 2026-02-04T06:55:05.247Z
**Project:** /Users/as186073/Downloads/vvvv-clj/clj-reload-skija
**Messages:** 20

## Table of Contents

1. [Can you look at the code and Tell me, like the architecture we are using, I suppose, like we have th...](#user-1)
2. [Okay, so, um... We can disable VSync on the secondary window (control pnael). There is one thing tha...](#user-2)

---

## <a id="user-1"></a>User #1

Can you look at the code and Tell me, like the architecture we are using, I suppose, like we have this main window in which we actually draw our main UI and then we have this control UI, right, in which we draw our, like control panel in which we render and draw and calculate and compute and draw stuff there as well. Just look at the architecture, check the web as well and think and tell if this whole idea is right. Because I suppose, maybe I could be wrong, but I think because of the way we have architected it, look your source app and lib if needed, source lib if needed. that that we like we are essentially because we're rendering at two different we are asking you to render and compute and calculate and render at two different places we are essentially um eating up like the 16 millisecond or whatever frame time of the main window when we have like the control panel open or maybe like even maybe when the control panel is closed as well I'm not totally sure if that's the right idea and that's how other multi window projects actually function can you check the web and tell like check the web for blog posts forums discussions issues and all and tell

---

**Assistant:**

(no content)

Let me explore the codebase architecture and research how multi-window rendering is typically handled.

> _Launched agent: Explore multi-window rendering architecture_
> _Launched agent: Research multi-window rendering patterns_

---

**Assistant:**

Good, I have a thorough understanding now. Let me read a few critical sections to verify the key details myself.

> _Read: ~/Downloads/vvvv-clj/clj-reload-skija/src/lib/window/core.clj_
> _Read: ~/Downloads/vvvv-clj/clj-reload-skija/src/app/core.clj_
> _Read: ~/Downloads/vvvv-clj/clj-reload-skija/src/app/core.clj_

---

**Assistant:**

Let me also check the `render-frame!` function and VSync configuration.

> _Read: ~/Downloads/vvvv-clj/clj-reload-skija/src/lib/window/core.clj_
> _Searched code: "vsync|swap-interval|VSync|SWAP"_

---

> _Searched code: "swap-interval|vsync|GL_SetSwapInterval|SwapInterval"_

---

**Assistant:**

Now let me check Metal's presentation behavior for VSync.

> _Searched code: "present|displaySyncEnabled|displayLink"_

---

> _Read: ~/Downloads/vvvv-clj/clj-reload-skija/src/app/core.clj_

---

**Assistant:**

I now have a complete picture. Let me write up my analysis.

> _Wrote: ~/.claude/plans/floofy-wiggling-ritchie.md_
> <details><summary>File content (102 lines)</summary>
>
> ```md
> # Multi-Window Rendering Architecture Analysis
> 
> ## Your Intuition Is Correct
> 
> **Yes, the control panel rendering eats into the main window's frame budget.** Both windows render sequentially on the same thread within one iteration of the event loop.
> 
> ## How It Works Today
> 
> ### Single-threaded, sequential rendering
> 
> The `run-multi!` loop in `src/lib/window/core.clj:301-404` does this every frame:
> 
> ```
> 1. Poll events for ALL windows
> 2. Route events to correct window handler
> 3. FOR EACH window that has frame-requested? = true:
>      render-frame!(window)     // sequentially, one after the other
> ```
> 
> Both windows render in the same `while` loop iteration (lines 378-382):
> ```clojure
> (doseq [w all-windows]
>   (when (and @(:running? w) @(:frame-requested? w))
>     (reset! (:frame-requested? w) false)
>     (render-frame! w)))
> ```
> 
> ### The main window drives the panel's frame requests
> 
> In `src/app/core.clj:290-294`, the main window's frame handler explicitly requests frames for the panel:
> ```clojure
> (window/request-frame! win)
> (when (and @shell-state/panel-visible? (some? @sys/panel-window))
>   (window/request-frame! @sys/panel-window))
> ```
> 
> This means: every time the main window renders, if the panel is visible, the panel also renders in the same loop iteration.
> 
> ### VSync makes this worse (for OpenGL backend)
> 
> `src/lib/window/internal.clj:179-180` sets VSync = 1:
> ```clojure
> (SDLVideo/SDL_GL_SetSwapInterval 1)
> ```
> 
> With VSync on and two windows calling `swap-buffers!`, each swap potentially blocks waiting for the next vsync. On a 60Hz display, each window's swap can block for up to ~16.67ms, so in the worst case: **main swap (16ms) + panel swap (16ms) = 30 FPS cap**.
> 
> This is a well-documented problem. OpenFrameworks users report frame rate dropping from 60 to 30 FPS just by opening a second window. SDL forum posts confirm: with VSync on, max FPS = refresh_rate / num_windows for double-buffered contexts.
> 
> ### When panel is HIDDEN: no rendering cost
> 
> The panel only requests frames when `panel-visible?` is true (line 181-182 in panel handler, lines 292-294 in main handler). When hidden, no frame events are dispatched to it and no `render-frame!` is called. So hiding the panel completely eliminates its cost.
> 
> ### When panel is VISIBLE: combined render time = your frame budget
> 
> The frame budget for both windows combined must fit within one vsync interval:
> 
> ```
> [main tick + main draw] + [panel draw] + [2x swap_buffers overhead] <= 16.67ms
> ```
> 
> If the main example is already heavy, adding the panel's render time on top pushes things over.
> 
> ## What Other Projects Do
> 
> Based on web research across SDL3, LWJGL, Dear ImGui, OpenFrameworks, Processing, and JUCE:
> 
> ### Same-thread multi-window is the standard approach
> Most frameworks do exactly what you're doing - render both windows on the same thread with context switching. This is simpler and avoids threading headaches with OpenGL (which is inherently single-context-per-thread).
> 
> ### The performance hit is well-known and accepted for dev tools
> - **Dear ImGui**: Renders everything as an overlay in the same window, not separate windows. This is the most efficient approach.
> - **Unity/Unreal**: Debug tools render as overlays in the main viewport, not separate windows.
> - **OpenFrameworks**: Multi-window causes measured FPS drops (60->30 with VSync on).
> - **JUCE**: Single-threaded UI; multiple OpenGL windows require careful context management.
> 
> ### Common mitigations
> 
> 1. **Overlay instead of separate window** - Render the control panel as an overlay in the main window (like Dear ImGui does). Zero context switching, zero additional swap overhead. This is what most game engines do for debug/inspector panels.
> 
> 2. **Disable VSync on the secondary window** - If you keep separate windows, only enable VSync on the main window. The panel doesn't need perfectly synced frames.
> 
> 3. **Render panel at lower frequency** - Only request panel frames every Nth main frame (e.g., 15fps for a control panel is fine).
> 
> 4. **Separate thread with separate GL context** - More complex, but allows true parallel rendering. Risky with shared state.
> 
> ## Summary
> 
> | Scenario | Frame budget impact |
> |----------|-------------------|
> | Panel hidden | None - no frames requested |
> | Panel visible (OpenGL + VSync) | Potentially halves FPS (two swap-buffer waits) |
> | Panel visible (Metal) | Adds panel render time to frame, but Metal present is less blocking than GL swap |
> | Panel visible (VSync off) | Adds panel render time only, no sync blocking |
> 
> ## Possible Improvements (if you want to address this)
> 
> 1. **Quick win**: Render the panel at reduced frequency (every 2-4 main frames)
> 2. **Medium effort**: Disable VSync on the panel window only, or switch to adaptive VSync
> 3. **Larger change**: Render panel as an in-window overlay instead of a separate window
> 4. **Skip panel rendering when main frame is heavy**: adaptive throttling
> ```
> </details>

---

**Assistant:**

Here's what I found:

**Your intuition is correct.** The control panel rendering does eat into the main window's frame budget. Here's why:

### The core issue

Both windows render **sequentially on the same thread** in `run-multi!` (`lib/window/core.clj:378-382`). Every loop iteration: poll events -> render main window -> render panel window. The total time for both must fit within one vsync interval (~16.67ms at 60Hz).

### VSync makes it worse

In `lib/window/internal.clj:179-180`, VSync is set to 1 (`SDL_GL_SetSwapInterval 1`). With two windows each calling `swap-buffers!`, each swap can block waiting for vsync. Worst case: **main swap (16ms) + panel swap (16ms) = 30 FPS cap**. This is a well-documented problem - OpenFrameworks users report exactly this: 60->30 FPS just from opening a second window.

### When panel is hidden: no cost

The panel only gets frames when `panel-visible?` is true, so hiding it fully eliminates the cost.

### What other projects do

Most frameworks (Dear ImGui, Unity, Unreal) render debug/tool panels as **overlays in the same window**, not separate windows. This avoids the context switch and double-swap problem entirely. Same-thread multi-window with VSync on is known to halve frame rate across SDL, LWJGL, OpenFrameworks, and JUCE communities.

### Possible fixes (ranked by effort)

1. **Render panel at lower frequency** (e.g., every 2-4 main frames) - quick win
2. **Disable VSync on panel window only** - medium effort
3. **Render panel as in-window overlay** instead of separate window - larger change but most efficient

I've written the full analysis with code references to the plan file. Would you like to discuss any of the improvement approaches, or do you have questions about the specifics?

> _Exited plan mode_

---

## <a id="user-2"></a>User #2

Okay, so, um... We can disable VSync on the secondary window (control pnael). There is one thing that I actually want to ask. So right now the implementations between the metal renderer and OpenGL is wrong then, right? So like for OpenGL we have VSync on. But for metal we don't I suppose we should make it consistent as well That's one another thing we should do both should have for the for the main window have the V sync on V sync on and and you're right about rendering panel at lower frequency we should cap rendering panel at lower frequency to 30 FPS that should be fine not 15 30 is fine And then you mentioned that with disabling VSync on the panel window only, which I agree with. But you also mentioned about adaptive sync. What is that? Can you tell me like how will it affect what it is and how will it affect? And the last thing that you mentioned, skipping panel rendering when mainframe is heavy. I am curious, like how you will identify, will you identify if we are, like if the mainframe is heavy, how will you identify that? 

---

**Assistant:**

(no content)

Let me first verify the Metal VSync situation, then I'll answer all your questions.

> _Read: ~/Downloads/vvvv-clj/clj-reload-skija/src/lib/window/layer_metal.clj_
> _Searched code: "displaySync|vsync|presentWithTransaction|framebufferOnly|presentsWithTransaction"_

---

**Assistant:**

Good. Now I have the complete picture on both backends. Let me answer all your questions.

---

### 1. Metal vs OpenGL VSync Consistency

You're right that there's an inconsistency, but it's subtler than it appears:

- **OpenGL** (`internal.clj:179-180`): Explicitly sets `SDL_GL_SetSwapInterval(1)` - VSync ON. The `swap-buffers!` call in `render-frame-opengl!` (`core.clj:162`) **blocks** until the next vsync.

- **Metal** (`layer_metal.clj`): There is **no** `setDisplaySyncEnabled:` call anywhere. On macOS, `CAMetalLayer.displaySyncEnabled` defaults to `YES` (VSync on). So Metal **is** vsync'd, just implicitly. However, Metal's `presentDrawable` (`metal.clj:116-121`) is **non-blocking** - it schedules the frame for the next vsync and returns immediately. It doesn't wait.

So the practical difference is:
- **OpenGL**: two `swap-buffers!` = two blocking waits = potentially 2x vsync penalty
- **Metal**: two `present-drawable` = two non-blocking calls = the compositor handles sync, no double-wait

Metal is actually in a better position here. The main window rendering still benefits from VSync, and the panel doesn't add a blocking wait. For making them consistent, you'd want to **explicitly** set `displaySyncEnabled` on Metal layers so it's visible in code rather than relying on a default, but the behavior is already reasonable.

---

### 2. Rendering Panel at 30 FPS

Agreed, 30 FPS for the panel is fine. The implementation would be straightforward: in the main window's frame handler (`app/core.clj:292-294`), instead of requesting a panel frame every time, count frames and only request every 2nd frame. That cuts the panel's rendering cost in half.

---

### 3. What is Adaptive VSync?

Your code already has a comment about it at `internal.clj:179`:
```clojure
;; Enable vsync (1 = on, 0 = off, -1 = adaptive)
```

The three modes of `SDL_GL_SetSwapInterval`:

| Value | Mode | Behavior |
|-------|------|----------|
| `1` | VSync ON | `swap-buffers!` always waits for next vsync. If you miss the deadline, you wait a full extra frame (drops to 30 FPS). |
| `0` | VSync OFF | `swap-buffers!` returns immediately. No tearing protection, but no blocking. |
| `-1` | **Adaptive VSync** | If you finish **before** the deadline, it waits for vsync (no tearing). If you finish **after** the deadline (missed it), it swaps **immediately** instead of waiting another full frame. |

The problem with regular VSync (`1`) is the cliff: if your frame takes 17ms (just 0.3ms over budget), you wait the full next vsync, so the effective frame time becomes 33ms (30 FPS). Adaptive VSync (`-1`) avoids that cliff - when you miss a deadline, you get a small tear but maintain higher average FPS instead of halving.

**How it would affect you**: If the main window + panel sometimes takes 17ms total (just over 16.67ms), with regular VSync you'd drop to 30 FPS. With adaptive VSync, you'd get ~59 FPS with occasional minor tearing instead. It's a graceful degradation.

**Caveat**: Not all GPU drivers support adaptive VSync. `SDL_GL_SetSwapInterval(-1)` can fail on some hardware. You'd want to try `-1` and fall back to `1`.

---

### 4. How to Detect if the Main Frame is Heavy

There are a few approaches:

**A. Measure the main window's render time directly.** Wrap the main window's `tick` + `draw` in timing:

```clojure
(let [start (System/nanoTime)]
  (tick-fn dt)
  (draw-fn canvas w h)
  (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)])
    ;; If main frame took > 12ms of the 16.67ms budget, skip panel this frame
    (when (< elapsed-ms 12.0)
      (window/request-frame! panel-win)))
```

This is the most direct approach. You already have the timing infrastructure in your frame handler (`app/core.clj:300-302`). The threshold (e.g., 12ms) leaves ~4ms headroom for panel rendering + swap overhead.

**B. Use FPS history you already track.** You already compute smoothed FPS in `shell-state/fps` (`app/core.clj:305-312`). If FPS drops below a threshold (say 55), throttle the panel more aggressively. This is less reactive but simpler - you're already computing it.

**C. Hybrid.** Measure per-frame render time (approach A) for immediate decisions, and use the smoothed FPS (approach B) to adaptively adjust the threshold - if FPS is trending down, become more aggressive about skipping panel frames.

Approach A is the most practical - it's direct, low overhead (one `System/nanoTime` call), and gives you frame-by-frame control.

> **Question:** Which of these improvements do you want to implement?
> - **All four** — 1) VSync consistency (explicit Metal displaySyncEnabled), 2) Panel at 30 FPS cap, 3) Disable VSync on panel (OpenGL), 4) Adaptive throttling based on main frame time
> - **Core two only** — Panel at 30 FPS cap + disable VSync on panel window. Skip Metal VSync explicitness and adaptive throttling for now.
> - **Just 30 FPS cap** — Only cap the panel to 30 FPS. Simplest change, biggest bang for the buck.
>
> **Answer:** You are a 30 FPS cap solution which is just divide like draw the control panel on every second frame. Isn't it wrong because if we have V-Sync enabled the monitor refresh rate can be 60, 90 or 120 so that by half thing doesn't sound right to me. also given the fact that you mentioned that metal like in the first point that the metal has a better logic here in than open GL so can we instead like in can we check the web and and find the ways to make the open GL behave more like how metal does right now because both have V-Sync on but but the mental behavior is better, right? That's what you mentioned. And about the third point, adaptive sync, you mentioned that not all GPU drivers support adaptive sync. One thing here, I can you check the web and actually check and confirm if modern hardware like you mentioned all not all GPU drivers support adaptive VSync can you check the web and confirm if all modern like like some of the all modern hard GPU hardwares or hardware in general does not support adaptive VSync because I would be using some modern hardware anyway so for example my laptop has 1650 Nvidia graphics and on the Mac side this this was what I was talking about my windows but on the Mac side at least I'll have Mac M1 or above. About the option, like about the point 4 you mentioned of how to detect if mainframe is heavy, let's skip that. Let's not include a mechanism to detect if mainframe is heavy and then to throttle the control panel rendering, let's not do that.

---

**Assistant:**

Good catches. You're right that "every 2nd frame" is wrong for variable refresh rates. And the OpenGL vs Metal question is interesting. Let me research both.

> _Launched agent: Research OpenGL non-blocking swap_
> _Launched agent: Research adaptive VSync hardware support_

---

**Assistant:**



Good research results. Let me summarize the key findings and address your points.

### On "every 2nd frame" being wrong

You're absolutely right. If the monitor is 120Hz, "every 2nd frame" gives 60 FPS for the panel, not 30. The correct approach is **time-based throttling** - track when the panel last rendered and only request a new frame if >= 33ms (1/30th of a second) has elapsed.

### On making OpenGL behave like Metal

**Unfortunately, this isn't possible.** OpenGL's `swap-buffers!` is fundamentally a blocking call when VSync is on - this is baked into the API design. Metal was designed from the ground up with non-blocking `presentDrawable`. There's no OpenGL extension or technique that gives you "present then continue" semantics. The options are:

1. **Disable VSync on the panel window** (`SetSwapInterval(0)`) - eliminates the blocking swap for the panel
2. **Use adaptive VSync** (`SetSwapInterval(-1)`) - reduces blocking when you miss deadlines
3. **Use Metal on macOS** (which you're already doing based on the backend detection code)

Since on macOS you're likely using Metal anyway, the OpenGL blocking swap issue primarily affects Windows/Linux.

### On adaptive VSync hardware support

For your specific hardware:

- **NVIDIA GTX 1650 (Windows)**: **YES**, fully supported. It's Turing architecture, and adaptive VSync (`WGL_EXT_swap_control_tear`) works on Pascal and newer.
- **Apple M1+ (macOS)**: **NO** for OpenGL. macOS doesn't natively support `EXT_swap_control_tear`. SDL has a buggy CVDisplayLink workaround. But this doesn't matter because on macOS you'd use the **Metal backend**, which already has non-blocking present.

So adaptive VSync is relevant for Windows/Linux (where it works), and irrelevant for macOS (where you use Metal instead).

### Revised plan

Given all this, let me design the implementation.

> _Launched agent: Design panel throttling implementation_

---

**Assistant:**

Good plan from the agent. Let me verify a few critical details before finalizing.

> _Read: ~/Downloads/vvvv-clj/clj-reload-skija/src/lib/window/core.clj_
> _Read: ~/Downloads/vvvv-clj/clj-reload-skija/src/lib/window/internal.clj_
> _Read: ~/Downloads/vvvv-clj/clj-reload-skija/src/lib/window/internal.clj_

---

> _Searched code: "\->Window"_

---

**Assistant:**

Good - only `map->Window` is used, so adding a field to the record is safe. Now let me write the final plan.

> _Read: ~/.claude/plans/floofy-wiggling-ritchie.md_

---

> _Wrote: ~/.claude/plans/floofy-wiggling-ritchie.md_
> <details><summary>File content (180 lines)</summary>
>
> ```md
> # Multi-Window Rendering Optimization Plan
> 
> ## Changes Overview
> 
> Three improvements to reduce panel window's impact on main window frame budget:
> 
> 1. **Time-based 30 FPS cap for panel** (monitor-rate independent)
> 2. **Disable VSync on panel window** (OpenGL: swap interval 0, Metal: displaySyncEnabled NO)
> 3. **Adaptive VSync for main window** (OpenGL: try -1, fall back to 1)
> 
> ---
> 
> ## File-by-File Changes
> 
> ### 1. `src/lib/window/metal.clj` — Add displaySyncEnabled setter
> 
> After `set-layer-framebuffer-only!` (~line 151), add:
> 
> ```clojure
> (defn set-layer-display-sync-enabled!
>   "Set whether the layer synchronizes presentation with the display refresh.
>    true = vsync on, false = vsync off."
>   [layer enabled?]
>   (when (and layer (pos? layer))
>     (let [sel (get-selector "setDisplaySyncEnabled:")
>           msg-send (objc-msg-send)]
>       (JNI/invokePPPV layer sel (if enabled? 1 0) msg-send))))
> ```
> 
> Same pattern as existing `set-layer-framebuffer-only!`.
> 
> ### 2. `src/lib/window/internal.clj` — Remove hardcoded VSync, add setter
> 
> **Line 179-180**: Remove `(SDLVideo/SDL_GL_SetSwapInterval 1)` from `create-gl-context!`.
> 
> **After `swap-buffers!` (~line 334)**, add:
> 
> ```clojure
> (defn set-swap-interval!
>   "Set OpenGL swap interval. 0=off, 1=vsync, -1=adaptive.
>    Returns true if supported."
>   [interval]
>   (SDLVideo/SDL_GL_SetSwapInterval interval))
> ```
> 
> ### 3. `src/lib/window/layer_metal.clj` — Wire displaySyncEnabled into init!
> 
> **Line 25**: Add `vsync?` keyword arg:
> 
> ```clojure
> (defn init! [window-handle & {:keys [vsync?] :or {vsync? true}}]
> ```
> 
> **After line 50** (`set-layer-framebuffer-only!`), add:
> 
> ```clojure
> (metal/set-layer-display-sync-enabled! metal-layer vsync?)
> ```
> 
> ### 4. `src/lib/window/core.clj` — Per-window VSync support
> 
> **Window record (line 22-24)**: Add `vsync` field:
> 
> ```clojure
> (defrecord Window [^long handle ^long gl-context window-id event-handler
>                    frame-requested? running?
>                    width height scale backend
>                    vsync])
> ```
> 
> Only `map->Window` is used (confirmed), so adding a field is safe.
> 
> **`create-window` (line 53)**: Accept `:vsync` option:
> 
> ```clojure
> {:keys [title width height x y resizable? high-dpi? always-on-top? display backend
>         shared-gl-context vsync]
>  :or {title "Window" width 800 height 600 resizable? true high-dpi? true backend :auto}}
> ```
> 
> **Metal branch (lines 66-86)**: Pass vsync to Metal init and store in record:
> 
> ```clojure
> (when-not (layer-metal/init! handle :vsync? (not= vsync 0))
>   (throw ...))
> ;; In map->Window:
> :vsync (atom (or vsync 1))
> ```
> 
> **OpenGL branch (lines 89-111)**: After context creation, for primary (non-shared) context, probe adaptive VSync:
> 
> ```clojure
> (let [;; For primary window, try adaptive vsync (-1), fall back to standard (1)
>       ;; For secondary windows, default to 0 (no vsync)
>       desired (or vsync (if shared-gl-context 0 -1))
>       effective (if (and (= desired -1) (not (sdl/set-swap-interval! -1)))
>                   1
>                   desired)]
>   (map->Window
>     {...
>      :vsync (atom effective)}))
> ```
> 
> **`render-frame-opengl!` (lines 139-162)**: Set swap interval before swapping:
> 
> ```clojure
> ;; Before (sdl/swap-buffers! handle), add:
> (sdl/set-swap-interval! @(:vsync window))
> ```
> 
> ### 5. `src/app/core.clj` — Time-based 30 FPS throttle + panel VSync=0
> 
> **Near line 37** (after `panel-height` defonce), add:
> 
> ```clojure
> (defonce panel-last-render-time (atom 0))
> ```
> 
> **Lines 291-294**: Replace unconditional panel frame request with time-based throttle:
> 
> ```clojure
> ;; Request panel frame at ~30 FPS (time-based, monitor-rate independent)
> (when (and @shell-state/panel-visible?
>            (some? @sys/panel-window))
>   (let [now-ms (/ (System/nanoTime) 1e6)
>         elapsed (- now-ms @panel-last-render-time)]
>     (when (>= elapsed 33.0)
>       (reset! panel-last-render-time now-ms)
>       (window/request-frame! @sys/panel-window))))
> ```
> 
> **Lines 550-560**: Add `:vsync 0` to panel window creation:
> 
> ```clojure
> (let [panel-opts (cond-> {:title "Controls"
>                           :width 260
>                           :height 500
>                           :x panel-x
>                           :y main-y
>                           :resizable? true
>                           :high-dpi? true
>                           :always-on-top? true
>                           :vsync 0}
>                    (= :opengl (window/get-backend win))
>                    (assoc :shared-gl-context (:gl-context win)))
> ```
> 
> ---
> 
> ## Implementation Order
> 
> 1. `metal.clj` — add `set-layer-display-sync-enabled!` (pure addition)
> 2. `internal.clj` — add `set-swap-interval!`, remove hardcoded VSync
> 3. `layer_metal.clj` — add `vsync?` param to `init!`
> 4. `core.clj` (lib/window) — add `vsync` to Window record, wire into `create-window` and `render-frame-opengl!`
> 5. `core.clj` (app) — time-based throttle + `:vsync 0` for panel
> 
> Steps 1-3 are independent. Step 4 depends on 2-3. Step 5 depends on 4.
> 
> ---
> 
> ## Verification
> 
> 1. Start app with `clj -A:dev:macos-arm64` (Metal backend)
> 2. Open an example: `(open :playground/ball-spring)`
> 3. Toggle control panel with Ctrl+` — verify it opens
> 4. Check FPS with panel open vs closed — should see minimal FPS difference now
> 5. Verify panel renders smoothly at ~30 FPS (responsive but not eating main budget)
> 6. After validating, run `bb scripts/check.clj` on all modified files
> 
> For OpenGL testing (Windows/Linux): verify adaptive VSync probe doesn't crash, panel swaps without blocking.
> 
> ---
> 
> ## Risk Notes
> 
> - **`SDL_GL_SetSwapInterval` per-swap cost**: Negligible — single driver flag set. Can cache if needed.
> - **Shared GL context**: If swap interval turns out to be per-context not per-window on some driver, the time-based throttle still correctly limits panel to 30 FPS. Main window might occasionally miss VSync after panel renders, but only ~30 times/sec.
> - **`setDisplaySyncEnabled:` availability**: Requires macOS 10.13+ (already required by Metal).
> ```
> </details>

---

**Assistant:**

The plan is written. To summarize the key decisions:

1. **30 FPS panel cap** is time-based (`System/nanoTime` >= 33ms elapsed), not frame-count based, so it works correctly at 60/90/120Hz monitors.

2. **Panel VSync disabled** on both backends — OpenGL gets `SetSwapInterval(0)` before each panel swap, Metal gets `setDisplaySyncEnabled:NO`. This eliminates the blocking-swap-per-panel-frame problem.

3. **Main window gets adaptive VSync** on OpenGL (tries `-1`, falls back to `1`). On your NVIDIA 1650 Windows machine this will work. On macOS you use Metal so this path doesn't apply.

4. **Metal VSync made explicit** — `setDisplaySyncEnabled:YES` for main window, `NO` for panel, instead of relying on macOS default.

5 files touched, with the safety property that even if the per-window swap interval trick doesn't work on some OpenGL driver, the time-based throttle alone still correctly limits panel to 30 FPS.

> _Exited plan mode_

---

**Assistant:**

Metal doesn't have an "adaptive VSync" option. Its `displaySyncEnabled` is binary: YES or NO. But this is actually fine because Metal doesn't need it.

Here's why: the problem adaptive VSync solves in OpenGL is the **blocking cliff**. With OpenGL VSync=1, if your frame takes 17ms (just over 16.67ms), `swap-buffers` blocks until the *next* vsync — so your effective frame time jumps from 16.67ms to 33.3ms (60 FPS drops to 30 FPS). Adaptive VSync (`-1`) fixes this by swapping immediately when you miss the deadline instead of blocking for another full frame.

Metal doesn't have this problem at all. `presentDrawable` is **non-blocking** even with `displaySyncEnabled = YES`. It schedules the frame for the next available vsync and returns immediately. If you miss a vsync deadline, the compositor just keeps showing the previous frame and your new frame appears on the next one. There's no blocking stall, no cliff — the CPU continues working on the next frame right away.

So in practice:
- **OpenGL**: VSync=1 has the cliff problem → adaptive VSync (-1) is the fix → fall back to VSync=1 if unsupported
- **Metal**: `displaySyncEnabled=YES` already behaves like "the best of both worlds" — tear-free presentation without blocking. No adaptive mode needed.

This is exactly why I noted earlier that Metal's architecture is inherently better for multi-window. The plan as written is correct: Metal main window gets `displaySyncEnabled=YES` (which is already graceful), Metal panel window gets `displaySyncEnabled=NO` (unsynced, since the time-based 30 FPS cap handles the throttling).

> _Exited plan mode_

---

