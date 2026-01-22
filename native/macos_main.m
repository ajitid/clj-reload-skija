/**
 * macOS Main Thread Dispatcher
 * 
 * This native library allows JVM code to dispatch work to macOS's main thread (thread 0),
 * which is required for SDL3/Cocoa window operations.
 * 
 * Architecture:
 * - JVM typically starts on a secondary thread
 * - SDL3 requires all video/window operations on the main thread
 * - This library uses dispatch_async(dispatch_get_main_queue(), ...) to marshal work
 * - CFRunLoopRun() keeps the main queue alive
 */

#import <Foundation/Foundation.h>
#import <CoreFoundation/CoreFoundation.h>
#import <AppKit/AppKit.h>
#import <dispatch/dispatch.h>
#import <jni.h>
#import <pthread.h>

// Global JVM reference for callbacks
static JavaVM *gJVM = NULL;

// Flag to track if main loop is running
static volatile int gMainLoopRunning = 0;

/**
 * JNI: Run a Runnable on macOS main thread.
 * 
 * This dispatches the given Runnable to the main queue and returns immediately.
 * The Runnable.run() method will be called on thread 0.
 */
JNIEXPORT void JNICALL Java_lib_window_macos_MainThread_runOnMainThread
  (JNIEnv *env, jclass cls, jobject callback) {
    
    // Store JVM reference for callbacks
    (*env)->GetJavaVM(env, &gJVM);
    
    // Create global ref so it survives across threads
    jobject globalCallback = (*env)->NewGlobalRef(env, callback);
    
    dispatch_async(dispatch_get_main_queue(), ^{
        @autoreleasepool {
            JNIEnv *callbackEnv = NULL;
            jint attachResult;
            
            // Attach this thread to JVM (required for JNI calls from native threads)
            attachResult = (*gJVM)->AttachCurrentThread(gJVM, (void**)&callbackEnv, NULL);
            if (attachResult != JNI_OK) {
                NSLog(@"Failed to attach main thread to JVM: %d", attachResult);
                return;
            }
            
            // Get Runnable.run() method
            jclass runnableClass = (*callbackEnv)->GetObjectClass(callbackEnv, globalCallback);
            jmethodID runMethod = (*callbackEnv)->GetMethodID(callbackEnv, runnableClass, "run", "()V");
            
            if (runMethod == NULL) {
                NSLog(@"Failed to find Runnable.run() method");
                (*callbackEnv)->DeleteGlobalRef(callbackEnv, globalCallback);
                return;
            }
            
            // Call Runnable.run()
            (*callbackEnv)->CallVoidMethod(callbackEnv, globalCallback, runMethod);
            
            // Check for exceptions
            if ((*callbackEnv)->ExceptionCheck(callbackEnv)) {
                NSLog(@"Exception in Runnable.run():");
                (*callbackEnv)->ExceptionDescribe(callbackEnv);
                (*callbackEnv)->ExceptionClear(callbackEnv);
            }
            
            // Clean up
            (*callbackEnv)->DeleteGlobalRef(callbackEnv, globalCallback);
            
            // Note: We don't detach here because the main thread may be reused
            // for future dispatches. JVM handles cleanup on exit.
        }
    });
}

/**
 * JNI: Run a Runnable on macOS main thread SYNCHRONOUSLY.
 * 
 * This dispatches the given Runnable to the main queue and BLOCKS
 * until it completes. Use this when you need to wait for the result
 * or when the callback runs an event loop.
 */
JNIEXPORT void JNICALL Java_lib_window_macos_MainThread_runOnMainThreadSync
  (JNIEnv *env, jclass cls, jobject callback) {
    
    // Store JVM reference for callbacks
    (*env)->GetJavaVM(env, &gJVM);
    
    // Create global ref so it survives across threads
    jobject globalCallback = (*env)->NewGlobalRef(env, callback);
    
    // Use dispatch_sync to block until callback completes on thread 0
    dispatch_sync(dispatch_get_main_queue(), ^{
        @autoreleasepool {
            JNIEnv *callbackEnv = NULL;
            jint attachResult;
            
            // Attach this thread to JVM (required for JNI calls from native threads)
            attachResult = (*gJVM)->AttachCurrentThread(gJVM, (void**)&callbackEnv, NULL);
            if (attachResult != JNI_OK) {
                NSLog(@"Failed to attach main thread to JVM: %d", attachResult);
                return;
            }
            
            // Get Runnable.run() method
            jclass runnableClass = (*callbackEnv)->GetObjectClass(callbackEnv, globalCallback);
            jmethodID runMethod = (*callbackEnv)->GetMethodID(callbackEnv, runnableClass, "run", "()V");
            
            if (runMethod == NULL) {
                NSLog(@"Failed to find Runnable.run() method");
                (*callbackEnv)->DeleteGlobalRef(callbackEnv, globalCallback);
                return;
            }
            
            // Call Runnable.run()
            (*callbackEnv)->CallVoidMethod(callbackEnv, globalCallback, runMethod);
            
            // Check for exceptions
            if ((*callbackEnv)->ExceptionCheck(callbackEnv)) {
                NSLog(@"Exception in Runnable.run():");
                (*callbackEnv)->ExceptionDescribe(callbackEnv);
                (*callbackEnv)->ExceptionClear(callbackEnv);
            }
            
            // Clean up
            (*callbackEnv)->DeleteGlobalRef(callbackEnv, globalCallback);
        }
    });
}

/**
 * JNI: Run the main CFRunLoop.
 * 
 * This starts the macOS main run loop, which is required to process
 * dispatch_async calls to the main queue. This call blocks until
 * stopMainLoop is called.
 */
JNIEXPORT void JNICALL Java_lib_window_macos_MainThread_runMainLoop
  (JNIEnv *env, jclass cls) {
    
    gMainLoopRunning = 1;
    
    // Run the main run loop - this processes dispatch_async calls
    // This will block until CFRunLoopStop is called
    CFRunLoopRun();
    
    gMainLoopRunning = 0;
}

/**
 * JNI: Stop the main CFRunLoop.
 * 
 * This stops the main run loop started by runMainLoop().
 */
JNIEXPORT void JNICALL Java_lib_window_macos_MainThread_stopMainLoop
  (JNIEnv *env, jclass cls) {
    
    if (gMainLoopRunning) {
        dispatch_async(dispatch_get_main_queue(), ^{
            CFRunLoopStop(CFRunLoopGetMain());
        });
    }
}

/**
 * JNI: Check if currently on macOS main thread.
 */
JNIEXPORT jboolean JNICALL Java_lib_window_macos_MainThread_isMainThread
  (JNIEnv *env, jclass cls) {

    return pthread_main_np() != 0;
}

/**
 * JNI: Activate the application, bringing it to foreground focus.
 * This is needed on macOS to receive keyboard/mouse input when
 * launched from Terminal (SDL_RaiseWindow only changes z-order).
 */
JNIEXPORT void JNICALL Java_lib_window_macos_MainThread_activateApp
  (JNIEnv *env, jclass cls) {
    @autoreleasepool {
        [NSApp activateIgnoringOtherApps:YES];
    }
}
