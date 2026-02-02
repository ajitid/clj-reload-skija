(ns lib.window.metal-ffm
  "Metal FFM bindings for struct-passing methods.

   Uses Java's Foreign Function & Memory API (FFM, stable in JDK 22+) to call
   Metal methods that require passing structs by value - something LWJGL's JNI
   cannot handle due to argument count limits.

   Key capability: copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:
                   toBuffer:destinationOffset:destinationBytesPerRow:destinationBytesPerImage:

   This enables async GPU-side DMA copy from texture to buffer, avoiding
   synchronous Skia readPixels calls that stall the render pipeline."
  (:import [java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
            MemoryLayout MemorySegment SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle]))

;; ============================================================
;; FFM Availability Check
;; ============================================================

(defn available?
  "Check if FFM is available (JDK 22+)."
  []
  (try
    ;; Check if FFM classes are available
    (Class/forName "java.lang.foreign.Linker")
    true
    (catch ClassNotFoundException _
      false)))

;; ============================================================
;; Struct Layouts
;; ============================================================

;; MTLOrigin = { NSUInteger x, y, z } = 24 bytes on 64-bit
;; NSUInteger is unsigned long (8 bytes) on 64-bit macOS
(def ^:private MTL-ORIGIN-LAYOUT
  (MemoryLayout/structLayout
    (into-array MemoryLayout
      [(.withName ValueLayout/JAVA_LONG "x")
       (.withName ValueLayout/JAVA_LONG "y")
       (.withName ValueLayout/JAVA_LONG "z")])))

;; MTLSize = { NSUInteger width, height, depth } = 24 bytes on 64-bit
(def ^:private MTL-SIZE-LAYOUT
  (MemoryLayout/structLayout
    (into-array MemoryLayout
      [(.withName ValueLayout/JAVA_LONG "width")
       (.withName ValueLayout/JAVA_LONG "height")
       (.withName ValueLayout/JAVA_LONG "depth")])))

;; ============================================================
;; Function Descriptors
;; ============================================================

;; sel_registerName descriptor: const char* -> SEL (pointer)
(def ^:private SEL-REGISTER-DESCRIPTOR
  (FunctionDescriptor/of
    ValueLayout/ADDRESS    ; returns SEL
    (into-array MemoryLayout [ValueLayout/ADDRESS])))  ; const char* name

;; newBufferWithLength:options: descriptor
;; Method signature: id<MTLBuffer> (id self, SEL _cmd, NSUInteger length, MTLResourceOptions options)
;; Returns pointer, takes: pointer, pointer, long, long
(def ^:private BUFFER-CREATE-DESCRIPTOR
  (FunctionDescriptor/of
    ValueLayout/ADDRESS     ; returns id<MTLBuffer>
    (into-array MemoryLayout
      [ValueLayout/ADDRESS     ; self (device)
       ValueLayout/ADDRESS     ; _cmd (selector)
       ValueLayout/JAVA_LONG   ; length (NSUInteger)
       ValueLayout/JAVA_LONG]))) ; options (MTLResourceOptions)

;; copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:
;;     toBuffer:destinationOffset:destinationBytesPerRow:destinationBytesPerImage:
;;
;; Method signature:
;;   void (id<MTLBlitCommandEncoder> self, SEL _cmd,
;;         id<MTLTexture> sourceTexture,
;;         NSUInteger sourceSlice,
;;         NSUInteger sourceLevel,
;;         MTLOrigin sourceOrigin,      <- struct by value (24 bytes)
;;         MTLSize sourceSize,          <- struct by value (24 bytes)
;;         id<MTLBuffer> destinationBuffer,
;;         NSUInteger destinationOffset,
;;         NSUInteger destinationBytesPerRow,
;;         NSUInteger destinationBytesPerImage)
(def ^:private BLIT-COPY-DESCRIPTOR
  (FunctionDescriptor/ofVoid
    (into-array MemoryLayout
      [ValueLayout/ADDRESS     ; self (blit encoder)
       ValueLayout/ADDRESS     ; _cmd (selector)
       ValueLayout/ADDRESS     ; sourceTexture
       ValueLayout/JAVA_LONG   ; sourceSlice
       ValueLayout/JAVA_LONG   ; sourceLevel
       MTL-ORIGIN-LAYOUT       ; sourceOrigin (struct by value)
       MTL-SIZE-LAYOUT         ; sourceSize (struct by value)
       ValueLayout/ADDRESS     ; destinationBuffer
       ValueLayout/JAVA_LONG   ; destinationOffset
       ValueLayout/JAVA_LONG   ; destinationBytesPerRow
       ValueLayout/JAVA_LONG]))) ; destinationBytesPerImage

;; getBytes:bytesPerRow:fromRegion:mipmapLevel: descriptor
;; Method signature:
;;   void (id<MTLTexture> self, SEL _cmd,
;;         void* pixelBytes,
;;         NSUInteger bytesPerRow,
;;         MTLRegion region,            <- MTLOrigin (24 bytes) + MTLSize (24 bytes) = 48 bytes
;;         NSUInteger mipmapLevel)
;; Note: MTLRegion is passed as two consecutive structs (origin then size)
(def ^:private GET-BYTES-DESCRIPTOR
  (FunctionDescriptor/ofVoid
    (into-array MemoryLayout
      [ValueLayout/ADDRESS     ; self (texture)
       ValueLayout/ADDRESS     ; _cmd (selector)
       ValueLayout/ADDRESS     ; pixelBytes (destination pointer)
       ValueLayout/JAVA_LONG   ; bytesPerRow
       MTL-ORIGIN-LAYOUT       ; region.origin (struct by value)
       MTL-SIZE-LAYOUT         ; region.size (struct by value)
       ValueLayout/JAVA_LONG]))) ; mipmapLevel

;; ============================================================
;; FFM State (cached handles)
;; ============================================================

(defonce ^:private ffm-state
  (atom {:initialized?        false
         :linker              nil
         :libobjc             nil
         :objc-msg-send       nil
         :sel-register-name   nil
         :blit-copy-handle    nil
         :blit-copy-sel       nil
         :buffer-create-handle nil
         :buffer-create-sel   nil
         :get-bytes-handle    nil
         :get-bytes-sel       nil
         :arena               nil}))

;; ============================================================
;; Initialization
;; ============================================================

(defn- init-ffm!
  "Initialize FFM handles. Called lazily on first use."
  []
  (when-not (:initialized? @ffm-state)
    (try
      (let [linker (Linker/nativeLinker)
            ;; Use confined arena that we manage manually
            arena (Arena/ofConfined)
            ;; Load libobjc
            libobjc (SymbolLookup/libraryLookup "/usr/lib/libobjc.dylib" arena)
            ;; Get objc_msgSend address
            objc-msg-send-addr (.orElseThrow (.find libobjc "objc_msgSend"))
            ;; Get sel_registerName address
            sel-register-addr (.orElseThrow (.find libobjc "sel_registerName"))
            ;; Create downcall handles (JDK 25+ requires empty options array)
            no-opts (into-array Linker$Option [])
            sel-register-handle (.downcallHandle linker sel-register-addr SEL-REGISTER-DESCRIPTOR no-opts)
            blit-copy-handle (.downcallHandle linker objc-msg-send-addr BLIT-COPY-DESCRIPTOR no-opts)
            buffer-create-handle (.downcallHandle linker objc-msg-send-addr BUFFER-CREATE-DESCRIPTOR no-opts)
            get-bytes-handle (.downcallHandle linker objc-msg-send-addr GET-BYTES-DESCRIPTOR no-opts)

            ;; Pre-register selectors
            ;; Blit copy selector
            blit-sel-name "copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:toBuffer:destinationOffset:destinationBytesPerRow:destinationBytesPerImage:"
            blit-sel-segment (.allocateFrom arena blit-sel-name)
            blit-copy-sel (.invokeWithArguments sel-register-handle [blit-sel-segment])

            ;; Buffer create selector
            buffer-sel-name "newBufferWithLength:options:"
            buffer-sel-segment (.allocateFrom arena buffer-sel-name)
            buffer-create-sel (.invokeWithArguments sel-register-handle [buffer-sel-segment])

            ;; Get bytes selector (for synchronous texture read)
            get-bytes-sel-name "getBytes:bytesPerRow:fromRegion:mipmapLevel:"
            get-bytes-sel-segment (.allocateFrom arena get-bytes-sel-name)
            get-bytes-sel (.invokeWithArguments sel-register-handle [get-bytes-sel-segment])]
        (swap! ffm-state assoc
               :initialized? true
               :linker linker
               :libobjc libobjc
               :objc-msg-send objc-msg-send-addr
               :sel-register-name sel-register-handle
               :blit-copy-handle blit-copy-handle
               :blit-copy-sel blit-copy-sel
               :buffer-create-handle buffer-create-handle
               :buffer-create-sel buffer-create-sel
               :get-bytes-handle get-bytes-handle
               :get-bytes-sel get-bytes-sel
               :arena arena)
        (println "[metal-ffm] FFM initialized successfully"))
      (catch Exception e
        (println "[metal-ffm] FFM initialization failed:" (.getMessage e))
        (swap! ffm-state assoc :initialized? false)))))

(defn ensure-initialized!
  "Ensure FFM is initialized. Returns true if ready."
  []
  (when-not (:initialized? @ffm-state)
    (init-ffm!))
  (:initialized? @ffm-state))

;; ============================================================
;; Struct Allocation Helpers
;; ============================================================

(defn- alloc-origin
  "Allocate and populate MTLOrigin struct."
  ^MemorySegment [^Arena arena x y z]
  (let [segment (.allocate arena MTL-ORIGIN-LAYOUT)]
    (.set segment ValueLayout/JAVA_LONG 0 (long x))
    (.set segment ValueLayout/JAVA_LONG 8 (long y))
    (.set segment ValueLayout/JAVA_LONG 16 (long z))
    segment))

(defn- alloc-size
  "Allocate and populate MTLSize struct."
  ^MemorySegment [^Arena arena width height depth]
  (let [segment (.allocate arena MTL-SIZE-LAYOUT)]
    (.set segment ValueLayout/JAVA_LONG 0 (long width))
    (.set segment ValueLayout/JAVA_LONG 8 (long height))
    (.set segment ValueLayout/JAVA_LONG 16 (long depth))
    segment))

;; ============================================================
;; Public API
;; ============================================================

(defn- ptr->segment
  "Convert a native pointer (long) to a MemorySegment.
   The segment is zero-length but can be passed to native functions."
  ^MemorySegment [^long ptr]
  (MemorySegment/ofAddress ptr))

(defn copy-texture-to-buffer!
  "Issue async GPU blit copy from texture to buffer via FFM.

   This calls MTLBlitCommandEncoder's copyFromTexture:sourceSlice:sourceLevel:
   sourceOrigin:sourceSize:toBuffer:destinationOffset:destinationBytesPerRow:
   destinationBytesPerImage: method using FFM to pass structs by value.

   Returns immediately - GPU copy runs asynchronously.

   Parameters:
   - blit-encoder: MTLBlitCommandEncoder pointer (long)
   - texture: MTLTexture pointer (long)
   - buffer: MTLBuffer pointer (long)
   - width, height: Texture dimensions to copy
   - bytes-per-row: Destination row stride (typically width * 4 for BGRA)

   Returns true if the call succeeded, false otherwise."
  [blit-encoder texture buffer width height bytes-per-row]
  (if-not (ensure-initialized!)
    (do
      (println "[metal-ffm] copy-texture-to-buffer! skipped - FFM not initialized")
      false)
    (let [{:keys [^java.lang.invoke.MethodHandle blit-copy-handle
                  ^MemorySegment blit-copy-sel]} @ffm-state]
      (if-not (and blit-copy-handle blit-copy-sel
                   (pos? blit-encoder) (pos? texture) (pos? buffer))
        (do
          (println "[metal-ffm] copy-texture-to-buffer! skipped - invalid params:"
                   "handle?" (boolean blit-copy-handle)
                   "sel?" (boolean blit-copy-sel)
                   "encoder?" (pos? blit-encoder)
                   "texture?" (pos? texture)
                   "buffer?" (pos? buffer))
          false)
        (try
          ;; Use confined arena for temporary struct allocations
          (with-open [temp-arena (Arena/ofConfined)]
            (let [;; Create struct segments for origin and size
                  origin (alloc-origin temp-arena 0 0 0)
                  size (alloc-size temp-arena width height 1)
                  ;; Convert pointers to MemorySegment
                  encoder-seg (ptr->segment blit-encoder)
                  texture-seg (ptr->segment texture)
                  buffer-seg (ptr->segment buffer)
                  ;; Calculate bytes per image (full frame size)
                  bytes-per-image (* (long bytes-per-row) (long height))]
              ;; Call the method via objc_msgSend with FFM
              ;; Order: self, _cmd, texture, slice, level, origin, size, buffer, offset, bpr, bpi
              (.invokeWithArguments blit-copy-handle
                [encoder-seg         ; self
                 blit-copy-sel       ; _cmd
                 texture-seg         ; sourceTexture
                 (long 0)            ; sourceSlice
                 (long 0)            ; sourceLevel
                 origin              ; sourceOrigin (struct)
                 size                ; sourceSize (struct)
                 buffer-seg          ; destinationBuffer
                 (long 0)            ; destinationOffset
                 (long bytes-per-row)         ; destinationBytesPerRow
                 (long bytes-per-image)])      ; destinationBytesPerImage
              true))
          (catch Exception e
            (println "[metal-ffm] Blit copy failed:" (.getMessage e))
            (.printStackTrace e)
            false))))))

(defn get-texture-bytes!
  "Read texture pixels into a pre-allocated buffer via FFM.

   This is a SYNCHRONOUS operation - GPU must be idle first.
   Call after waitUntilCompleted on the render command buffer.

   Parameters:
   - texture: MTLTexture pointer (long)
   - dest-ptr: Destination memory pointer (long, from MemoryUtil/memAlloc)
   - bytes-per-row: Row stride in bytes (typically width * 4 for BGRA)
   - width, height: Texture dimensions

   Returns true on success, false on failure."
  [texture dest-ptr bytes-per-row width height]
  (when (ensure-initialized!)
    (let [{:keys [^MethodHandle get-bytes-handle
                  ^MemorySegment get-bytes-sel]} @ffm-state]
      (when (and get-bytes-handle get-bytes-sel
                 (pos? texture) (pos? dest-ptr)
                 (pos? bytes-per-row) (pos? width) (pos? height))
        (try
          (with-open [temp-arena (Arena/ofConfined)]
            (let [;; Create struct segments for region (origin + size)
                  origin (alloc-origin temp-arena 0 0 0)
                  size (alloc-size temp-arena width height 1)
                  ;; Convert pointers to MemorySegment
                  texture-seg (ptr->segment texture)
                  dest-seg (ptr->segment dest-ptr)]
              ;; Call the method via objc_msgSend
              (.invokeWithArguments get-bytes-handle
                [texture-seg        ; self (texture)
                 get-bytes-sel      ; _cmd (selector)
                 dest-seg           ; pixelBytes
                 (long bytes-per-row) ; bytesPerRow
                 origin             ; region.origin (struct)
                 size               ; region.size (struct)
                 (long 0)])          ; mipmapLevel
              true))
          (catch Exception e
            (println "[metal-ffm] Get texture bytes failed:" (.getMessage e))
            false))))))

;; MTLResourceStorageModeShared = 0 (CPU & GPU accessible)
(def ^:const MTLResourceStorageModeShared 0)

(defn create-buffer
  "Create MTLBuffer with shared storage for CPU read access via FFM.

   Parameters:
   - device: MTLDevice pointer (long)
   - size: Buffer size in bytes

   Returns buffer pointer (long) or 0 on failure."
  ^long [device size]
  (if (ensure-initialized!)
    (let [{:keys [^MethodHandle buffer-create-handle
                  ^MemorySegment buffer-create-sel]} @ffm-state]
      (if (and buffer-create-handle buffer-create-sel
               (pos? device) (pos? size))
        (try
          (let [device-seg (ptr->segment device)
                result (.invokeWithArguments buffer-create-handle
                         [device-seg           ; self (device)
                          buffer-create-sel    ; _cmd (selector)
                          (long size)          ; length
                          (long MTLResourceStorageModeShared)])] ; options
            ;; Result is a MemorySegment, extract address
            (.address ^MemorySegment result))
          (catch Exception e
            (println "[metal-ffm] Buffer creation failed:" (.getMessage e))
            0))
        (do
          (println "[metal-ffm] Buffer creation skipped - invalid params:"
                   "handle?" (boolean buffer-create-handle)
                   "sel?" (boolean buffer-create-sel)
                   "device?" (pos? device)
                   "size?" (pos? size))
          0)))
    (do
      (println "[metal-ffm] Buffer creation skipped - FFM not initialized")
      0)))

;; ============================================================
;; Cleanup
;; ============================================================

(defn cleanup!
  "Release FFM resources."
  []
  (when-let [arena (:arena @ffm-state)]
    (try
      (.close arena)
      (catch Exception _)))
  (reset! ffm-state {:initialized? false
                     :linker nil
                     :libobjc nil
                     :objc-msg-send nil
                     :sel-register-name nil
                     :blit-copy-handle nil
                     :blit-copy-sel nil
                     :buffer-create-handle nil
                     :buffer-create-sel nil
                     :get-bytes-handle nil
                     :get-bytes-sel nil
                     :arena nil}))
