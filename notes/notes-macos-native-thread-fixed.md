---
name: Fix SDL3 main thread
overview: The UI stopped opening because the switch from JWM to SDL3 removed main thread handling. JWM's `App/start` internally marshalled window operations to macOS's main thread via JNI. The fix is to create a similar JNI native library that dispatches SDL work to the macOS main queue, mirroring JWM's architecture.
todos:
  - id: create-native-lib
    content: Create native/macos_main.m - Objective-C library with dispatch_async to main queue
    status: pending
  - id: create-java-wrapper
    content: Create src/lib/window/macos.clj - JNI wrapper to load and call native library
    status: pending
  - id: create-build-script
    content: Create scripts/build-native.sh to compile the native library
    status: pending
  - id: modify-deps
    content: Remove -XstartOnFirstThread from deps.edn macOS aliases
    status: pending
  - id: modify-start-app
    content: Update app.core/start-app to use native main thread dispatch
    status: pending
  - id: test-pool-open
    content: Test that bb scripts/pool.clj open works correctly
    status: pending
---

# Fix SDL3 Main Thread Requirement for macOS

## Problem Analysis

The UI stopped opening between commits because:

1. **Old JWM code** ([src/app/core.clj](src/app/core.clj) at ce0d24b):
```clojure
(defn start-app []
  (when-not @state/running?
    (App/start create-window)))  ;; JWM handles main thread internally via JNI
```

JWM's `App/start` internally marshals window creation and the Cocoa event loop to macOS's main thread via native JNI code using `dispatch_async(dispatch_get_main_queue(), ...)`.

2. **New SDL3 code** (current):
```clojure
(defn start-app []
  (when-not @state/running?
    (let [win (window/create-window {...})]
      ...
      (window/run! win))))  ;; Direct call - no main thread handling
```

SDL3 requires all video/window operations on the main thread. When `pool.clj` sends `(open)` via nREPL, it runs on an nREPL worker thread.

## Solution: JNI Native Library (JWM-like approach)

Create a native library that mimics how JWM handles macOS main thread requirements. This approach:
- Removes `-XstartOnFirstThread` so macOS thread 0 is free
- Uses native `dispatch_async(dispatch_get_main_queue(), ...)` to run SDL on thread 0
- Provides JNI callbacks back to Java

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     JVM Process                             │
├─────────────────────────────────────────────────────────────┤
│  macOS Thread 0 (main thread) - NOT occupied by JVM         │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  Native Cocoa Main Queue                             │   │
│  │  - dispatch_async receives work from JNI             │   │
│  │  - SDL_Init runs here                                │   │
│  │  - SDL event loop runs here                          │   │
│  │  - Calls back to Java via AttachCurrentThread        │   │
│  └─────────────────────────────────────────────────────┘   │
│                           ▲                                 │
│                           │ dispatch_async                  │
│  JVM Threads (secondary)  │                                 │
│  ┌─────────────────────┐  │                                 │
│  │  Java main()        │──┘                                 │
│  │  nREPL, REPL        │                                    │
│  │  calls JNI native   │                                    │
│  └─────────────────────┘                                    │
└─────────────────────────────────────────────────────────────┘
```

### Files to Create/Modify

**1. Native Library: `native/macos_main.m`** (~150 lines)

Objective-C code that:
- Stores JavaVM pointer for callbacks
- Provides `runOnMainThread(callback)` via `dispatch_async(dispatch_get_main_queue(), ...)`
- Uses `AttachCurrentThread` before calling back to Java
- Handles autorelease pools properly

```objective-c
#import <dispatch/dispatch.h>
#import <jni.h>

static JavaVM *gJVM = NULL;
static jobject gCallback = NULL;

JNIEXPORT void JNICALL Java_lib_window_macos_MainThread_runOnMainThread
  (JNIEnv *env, jclass cls, jobject callback) {
    (*env)->GetJavaVM(env, &gJVM);
    gCallback = (*env)->NewGlobalRef(env, callback);
    
    dispatch_async(dispatch_get_main_queue(), ^{
        @autoreleasepool {
            JNIEnv *callbackEnv;
            (*gJVM)->AttachCurrentThread(gJVM, (void**)&callbackEnv, NULL);
            
            jclass runnableClass = (*callbackEnv)->GetObjectClass(callbackEnv, gCallback);
            jmethodID runMethod = (*callbackEnv)->GetMethodID(callbackEnv, runnableClass, "run", "()V");
            (*callbackEnv)->CallVoidMethod(callbackEnv, gCallback, runMethod);
            
            (*callbackEnv)->DeleteGlobalRef(callbackEnv, gCallback);
            (*gJVM)->DetachCurrentThread(gJVM);
        }
    });
}

// Keep main queue alive (CFRunLoop)
JNIEXPORT void JNICALL Java_lib_window_macos_MainThread_runMainLoop
  (JNIEnv *env, jclass cls) {
    CFRunLoopRun();
}
```

**2. Java Wrapper: `src/lib/window/macos.clj`**

```clojure
(ns lib.window.macos
  (:import [java.io File]))

;; Load native library
(defonce ^:private loaded?
  (delay
    (let [lib-path (str (System/getProperty "user.dir") "/native/libmacos_main.dylib")]
      (System/load lib-path)
      true)))

(defn ensure-loaded! [] @loaded?)

;; JNI declarations (gen-class or manual)
(gen-class
  :name lib.window.macos.MainThread
  :methods [^:static [runOnMainThread [Runnable] void]
            ^:static [runMainLoop [] void]])
```

**3. Build Script: `scripts/build-native.sh`**

```bash
#!/bin/bash
clang -shared -o native/libmacos_main.dylib \
  -framework Foundation -framework CoreFoundation \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
  native/macos_main.m
```

**4. Modify `deps.edn`**

Remove `-XstartOnFirstThread` from `:macos-arm64` and `:macos-x64` aliases.

**5. Modify `src/app/core.clj`**

```clojure
(defn start-app []
  (when-not @state/running?
    (macos/ensure-loaded!)
    ;; Run SDL initialization and event loop on macOS main thread
    (lib.window.macos.MainThread/runOnMainThread
      (fn []
        (reset! state/running? true)
        (let [win (window/create-window {...})]
          ...)))))
```

### Key Implementation Details

- **No `-XstartOnFirstThread`**: macOS thread 0 is free for Cocoa
- **`dispatch_async(dispatch_get_main_queue(), ...)`**: Dispatches work to thread 0
- **`CFRunLoopRun()`**: Keeps the main queue alive for receiving work
- **`AttachCurrentThread`**: Required before JNI callbacks from main thread
- **Autorelease pools**: Proper Objective-C memory management