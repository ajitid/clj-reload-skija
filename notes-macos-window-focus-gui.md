# Conversation: So on macOS, for macOS you probably have some extra code to ensure that the window when it launches 

**Date:** 2026-01-31T10:58:00.988Z
**Project:** /Users/as186073/Downloads/vvvv-clj/clj-reload-skija
**Messages:** 18

## Table of Contents

1. [So on macOS, for macOS you probably have some extra code to ensure that the window when it launches ...](#user-1)
2. [Okay, fine. Do you think we should add it?](#user-2)
3. [Here you can see that I probably am having that dock icon. The only thing being... I don't see anyth...](#user-3)
4. ["  - It's the "complete" pattern for CLI-launched GUI apps on macOS"](#user-4)
5. [Okay, let's add it then.](#user-5)
6. [Great thanks. Anything else that we may have added extra because we are launching this app from CLI ...](#user-6)

---

## <a id="user-1"></a>User #1

So on macOS, for macOS you probably have some extra code to ensure that the window when it launches gets focused. Can you check if it matches with the logic that I'm going to describe from other project here? 

```
  ▘▘ ▝▝    ~/ghq/unknown-host/unknown-user/gleam-skia-sdl

❯ So, I think some commits before probably the window or maybe I have amended the commit so it's hard to track but how have we solved the problem of the window not getting focus when it
  opens up? Can you check the code and tell me?

⏺ Let me search the codebase for window focus handling.

⏺ Search(pattern: "focus|raise|activate", path: "~/ghq/unknown-host/unknown-user/gleam-skia-sdl")
  ⎿  Found 6 files (ctrl+o to expand)

⏺ Search(pattern: "WindowFlags|WINDOW_", path: "native")
  ⎿  Found 0 files

⏺ Search(pattern: "focus|raise|activate", path: "native/gleam_renderer/src/main.rs", output_mode: "content")
  ⎿  Found 66 lines (ctrl+o to expand)

⏺ The window focus problem on macOS was solved with a dedicated activate_macos_app() function in native/gleam_renderer/src/main.rs:38-58. Here's how it works:

  The Problem:
  CLI apps (apps without an app bundle) on macOS don't automatically get focus when opening a window. The system treats them differently from regular GUI apps.

  The Solution (two-step activation):

  1. Set activation policy to Regular - This makes the CLI app behave like a normal GUI app (with dock icon and menu bar):
  app.setActivationPolicy(NSApplicationActivationPolicy::Regular);
  2. Activate ignoring other apps - Forces the app to the foreground:
  app.activateIgnoringOtherApps(true);

  Critical timing requirement (line 236-244):
  The activation must happen after the SDL event pump is created, otherwise Cocoa won't deliver the activation events properly. That's why the code creates the event pump first, then calls
  activate_macos_app(), then does a quick event poll to process the activation events:

  let mut event_pump = sdl.event_pump()...;
  // NOW activate the app - Cocoa can deliver events after event pump exists                                                                                                                    #[cfg(target_os = "macos")]
  {                                                                                                                                                                                                 activate_macos_app();
      // Process one event cycle to deliver activation events                                                                                                                                       for _ in event_pump.poll_iter() {}
  }
  This uses the objc2_app_kit and objc2_foundation crates to make direct Objective-C calls to NSApplication.
```

---

**Assistant:**

Let me search for window focus handling in this project.

> _Searched code: "focus|raise|activate|NSApplication|Activation"_

---

**Assistant:**

Let me read the native macOS implementation and how it's called.

> _Read: ~/Downloads/vvvv-clj/clj-reload-skija/native/macos_main.m_
> _Read: ~/Downloads/vvvv-clj/clj-reload-skija/src/lib/window/macos.clj_
> _Read: ~/Downloads/vvvv-clj/clj-reload-skija/src/app/core.clj_

---

**Assistant:**

Yes, this project has similar macOS focus handling. Here's the comparison:

## This Project (Clojure/JNI)

**Native code** (`native/macos_main.m:191-207`):
```objc
JNIEXPORT void JNICALL Java_lib_window_macos_MainThread_activateApp(...) {
    if (@available(macOS 14.0, *)) {
        [NSApp activate];  // Modern API
    }
    [NSApp activateIgnoringOtherApps:YES];  // Always also call deprecated fallback
}
```

**Timing** (`src/app/core.clj:160-162`):
```clojure
;; Called on first frame event (EventFrameSkija)
(when (and (macos?) (not @sys/app-activated?))
  (macos/activate-app!)
  (reset! sys/app-activated? true))
```

## Key Differences from Your Gleam Project

| Aspect | Gleam/Rust | Clojure/JNI |
|--------|------------|-------------|
| **Activation policy** | Sets `NSApplicationActivationPolicy::Regular` first | Does NOT set activation policy |
| **Activate call** | `activateIgnoringOtherApps(true)` | Both `[NSApp activate]` (14+) AND `activateIgnoringOtherApps:YES` |
| **Timing** | After event pump created, before loop | On first frame event (lazy) |
| **Event pump** | Explicit poll cycle after activation | No explicit poll cycle |

## Missing Piece

This project is **missing** the activation policy step:
```objc
[NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
```

This makes CLI apps behave like regular GUI apps (dock icon, menu bar). Without it, the `activateIgnoringOtherApps` may be less reliable. The Gleam project has this as a two-step process, while this project only does the second step.

---

## <a id="user-2"></a>User #2

Okay, fine. Do you think we should add it? 

---

**Assistant:**

It depends on whether you're having focus issues currently. 

**Reasons to add it:**
- The Gleam project needed both steps to reliably get focus
- It's the "complete" pattern for CLI-launched GUI apps on macOS
- Low risk - it's a one-liner addition

**Tradeoff to consider:**
- `NSApplicationActivationPolicyRegular` makes a **dock icon appear** for your app. If you prefer the app to stay "invisible" in the dock (like a background tool), you might not want this.

Are you experiencing focus problems when launching from terminal, or is this just for consistency with the other project?

---

## <a id="user-3"></a>User #3

Here you can see that I probably am having that dock icon. The only thing being... I don't see anything on the menu bar. The menu bar still points to ghostty app 

---

**Assistant:**

That's exactly the symptom. You have a dock icon (SDL creates that), but the **menu bar doesn't switch** to your app because macOS doesn't consider it a "regular" application.

Adding `setActivationPolicy:NSApplicationActivationPolicyRegular` should fix the menu bar. It tells macOS "this is a proper GUI app that owns the menu bar when focused."

Want me to add it to `native/macos_main.m`? It would be a small addition to `activateApp`:

```objc
// Before the activate calls:
[NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
```

---

## <a id="user-4"></a>User #4

"  - It's the "complete" pattern for CLI-launched GUI apps on macOS"
^ Can you check the web and confirm if this is actually true? 

---

> _Searched web: "macOS NSApplicationActivationPolicyRegular CLI app window focus activateIgnoringOtherApps"_

---

**Assistant:**

Yes, it's confirmed. The search results show this is the standard pattern:

**The Pattern (from search results):**
> To open a window and make it appear on top of other windows on macOS, you can do: `myWindow.makeKeyAndOrderFront(nil)`, `NSApp.setActivationPolicy(.regular)`, `NSApp.activate(ignoringOtherApps: true)`. This works to show the window, make it topmost and **also display the app menu**.

**Why it's needed for CLI apps:**
> If you have a command-line program that converts itself into a GUI app using `-[NSApplication setActivationPolicy:]` or the deprecated `TransformProcessType()`, the user running the tool intends to keep it active. However, since Terminal remains active and does not deactivate spontaneously, your program must steal focus.

**Known caveat:** There's a bug where "the app menu is not clickable until you tab away from the application and back again" - but this seems to be a separate issue from menu bar ownership.

So yes, `setActivationPolicy(.regular)` + `activateIgnoringOtherApps` is the documented two-step pattern for CLI-launched GUI apps. Your current code only does step 2.

**Sources:**
- [Apple Developer Documentation - activate(ignoringOtherApps:)](https://developer.apple.com/documentation/appkit/nsapplication/1428468-activate)
- [Workaround for unclickable app menu bug - Aral Balkan](https://ar.al/2018/09/17/workaround-for-unclickable-app-menu-bug-with-window.makekeyandorderfront-and-nsapp.activate-on-macos/)
- [setActivationPolicy irregularities | Apple Developer Forums](https://developer.apple.com/forums/thread/67584)

---

## <a id="user-5"></a>User #5

Okay, let's add it then. 

---

> _Edited: ~/Downloads/vvvv-clj/clj-reload-skija/native/macos_main.m_

---

**Assistant:**

Done. Now you need to rebuild the native library:

```bash
./scripts/build-native.sh
```

Then restart the app to test if the menu bar switches correctly.

---

## <a id="user-6"></a>User #6

Great thanks. Anything else that we may have added extra because we are launching this app from CLI and opening a GUI. So like do we need to have it in something extra, some sort of workaround that we can remove now or what we have is probably right. Can you confirm that? 

---

**Assistant:**

Let me search for any other CLI-to-GUI workarounds in the codebase.

> _Searched code: "TransformProcessType|makeKeyAndOrderFront|RaiseWindow|workaround|Ghostty|terminal|CLI|SetFrontProcess"_
> _Searched code: "SDL_WINDOW_|window.*flag|LWJGL.*window"_

---

> _Read: ~/Downloads/vvvv-clj/clj-reload-skija/src/lib/window/internal.clj_

---

**Assistant:**

Based on my search, what you have looks correct and clean. Here's the breakdown:

**What you have (all necessary):**

| Code | Purpose | Keep? |
|------|---------|-------|
| `SDL_RaiseWindow` (internal.clj:160) | Brings window to front (z-order only) | Yes - standard SDL |
| `activateApp` (macos_main.m) | macOS focus + menu bar ownership | Yes - we just fixed this |
| `app-activated?` atom | Prevents calling activate repeatedly | Yes - one-time gate |

**Why both are needed:**
The comment in your code explains it: "SDL_RaiseWindow only changes z-order" - it doesn't actually give keyboard/mouse focus on macOS when launched from CLI. That's why you need the separate `activateApp` call.

**No redundant workarounds found:**
- No `TransformProcessType` (old Carbon API)
- No `SetFrontProcess` hacks
- No duplicate focus attempts
- Window flags are standard (`OPENGL | RESIZABLE | HIGH_PIXEL_DENSITY`)

Your setup is the correct minimal pattern now:
1. SDL creates window + raises it (cross-platform)
2. macOS-specific activation sets policy + steals focus (once, on first frame)

Nothing to remove.

---

