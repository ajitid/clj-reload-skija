package lib.window.macos;

/**
 * JNI wrapper for macOS main thread operations.
 * 
 * This class provides methods to dispatch work to macOS's main thread (thread 0),
 * which is required for SDL3/Cocoa window operations.
 * 
 * Usage:
 *   MainThread.loadLibrary("/path/to/libmacos_main.dylib");
 *   MainThread.runOnMainThread(() -> {
 *       // This code runs on macOS main thread
 *       SDL.init();
 *       // ... window operations ...
 *   });
 */
public class MainThread {
    
    private static volatile boolean loaded = false;
    
    /**
     * Load the native library from the given path.
     * Safe to call multiple times - only loads once.
     */
    public static synchronized void loadLibrary(String path) {
        if (!loaded) {
            System.load(path);
            loaded = true;
        }
    }
    
    /**
     * Check if the native library is loaded.
     */
    public static boolean isLoaded() {
        return loaded;
    }
    
    /**
     * Dispatch a Runnable to macOS main thread (async).
     * Returns immediately - the Runnable executes asynchronously on thread 0.
     */
    public static native void runOnMainThread(Runnable callback);
    
    /**
     * Dispatch a Runnable to macOS main thread (sync).
     * BLOCKS until the Runnable completes on thread 0.
     * Use this when you need to wait for the result or run an event loop.
     */
    public static native void runOnMainThreadSync(Runnable callback);
    
    /**
     * Run the macOS main run loop.
     * This blocks until stopMainLoop() is called.
     * Call this from a background thread to keep the main queue alive.
     */
    public static native void runMainLoop();
    
    /**
     * Stop the main run loop.
     */
    public static native void stopMainLoop();
    
    /**
     * Check if currently executing on macOS main thread.
     */
    public static native boolean isMainThread();
}
