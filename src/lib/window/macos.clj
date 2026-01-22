(ns lib.window.macos
  "macOS main thread dispatch for SDL3/Cocoa.
   
   SDL3 requires all window/video operations to run on macOS's main thread (thread 0).
   When JVM starts without -XstartOnFirstThread, the main() runs on a secondary thread.
   
   This namespace provides:
   - `run-on-main-thread!` - dispatch a function to thread 0
   - `run-main-loop!` - keep main queue alive (blocking)
   - `stop-main-loop!` - stop the main loop
   
   Architecture:
   1. Call `start-main-loop-async!` from any thread (starts CFRunLoop on thread 0)
   2. Call `run-on-main-thread!` to dispatch SDL work to thread 0
   3. Call `stop-main-loop!` when done
   
   Usage from app.core:
     (macos/start-main-loop-async!)  ;; Once at startup
     (macos/run-on-main-thread!      ;; For SDL operations
       #(do (window/create-window ...)
            (window/run! win)))"
  (:import [lib.window.macos MainThread]))

;; ============================================================
;; Library Loading
;; ============================================================

(defonce ^:private lib-loaded?
  (atom false))

(defn- get-lib-path
  "Get the path to the native library."
  []
  (let [user-dir (System/getProperty "user.dir")]
    (str user-dir "/native/libmacos_main.dylib")))

(defn ensure-loaded!
  "Ensure the native library is loaded. Returns true on success."
  []
  (when-not @lib-loaded?
    (let [path (get-lib-path)]
      (when-not (.exists (java.io.File. path))
        (throw (ex-info "Native library not found. Run: ./scripts/build-native.sh"
                        {:path path})))
      (MainThread/loadLibrary path)
      (reset! lib-loaded? true)))
  true)

;; ============================================================
;; Main Thread API
;; ============================================================

(defn run-on-main-thread!
  "Dispatch a function to run on macOS main thread.
   Returns immediately - function executes asynchronously on thread 0.
   
   The main loop must be running (via start-main-loop-async!) for this to work.
   
   Note: Captures the current thread's context classloader and restores it
   on the main thread, since JNI AttachCurrentThread doesn't inherit it."
  [f]
  (ensure-loaded!)
  ;; Capture the current classloader - main thread won't have it after AttachCurrentThread
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (MainThread/runOnMainThread 
      (reify Runnable 
        (run [_] 
          ;; Restore classloader on main thread so Clojure can find classes
          (.setContextClassLoader (Thread/currentThread) cl)
          (f))))))

(defn run-on-main-thread-sync!
  "Dispatch a function to run on macOS main thread and BLOCK until it completes.
   Use this when you need the result or when the callback runs an event loop.
   
   Note: Captures the current thread's context classloader and restores it
   on the main thread, since JNI AttachCurrentThread doesn't inherit it."
  [f]
  (ensure-loaded!)
  ;; Capture the current classloader - main thread won't have it after AttachCurrentThread
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (MainThread/runOnMainThreadSync 
      (reify Runnable 
        (run [_] 
          ;; Restore classloader on main thread so Clojure can find classes
          (.setContextClassLoader (Thread/currentThread) cl)
          (f))))))

(defn main-thread?
  "Check if currently executing on macOS main thread."
  []
  (ensure-loaded!)
  (MainThread/isMainThread))

(defn activate-app!
  "Activate the macOS application, bringing it to foreground focus.
   This is needed to receive keyboard/mouse input when launched from Terminal."
  []
  (ensure-loaded!)
  (MainThread/activateApp))

(defn run-main-loop!
  "Run the macOS main run loop. BLOCKS until stop-main-loop! is called.
   This must be called from the main thread to process dispatched work."
  []
  (ensure-loaded!)
  (MainThread/runMainLoop))

(defn stop-main-loop!
  "Stop the main run loop."
  []
  (ensure-loaded!)
  (MainThread/stopMainLoop))

;; ============================================================
;; High-level API
;; ============================================================

(defonce ^:private main-loop-started?
  (atom false))

(defn start-main-loop-async!
  "Start the main loop on a background thread.
   
   This creates a daemon thread that:
   1. Dispatches the main loop setup to thread 0
   2. Keeps thread 0's CFRunLoop alive
   
   Call this once at application startup before any run-on-main-thread! calls.
   The main loop runs until stop-main-loop! is called or JVM exits."
  []
  (when-not @main-loop-started?
    (ensure-loaded!)
    (reset! main-loop-started? true)
    ;; Start a thread that will pump the main queue
    ;; We dispatch runMainLoop to thread 0 itself
    (let [t (Thread.
              (fn []
                (try
                  ;; This blocks until stopMainLoop is called
                  (MainThread/runMainLoop)
                  (catch Exception e
                    (println "Main loop error:" (.getMessage e)))))
              "macos-main-loop")]
      (.setDaemon t true)
      (.start t)
      ;; Give thread 0 a moment to start processing
      (Thread/sleep 50))))
