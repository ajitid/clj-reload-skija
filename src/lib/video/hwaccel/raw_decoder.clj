(ns lib.video.hwaccel.raw-decoder
  "Raw FFmpeg decoder using JavaCPP bindings for zero-copy hardware decoding.

   This module bypasses JavaCV's Frame abstraction to access the raw
   AVFrame.data[3] pointer which contains the platform-specific hardware
   frame handle (CVPixelBuffer, VASurface, CUDA device ptr, D3D11 texture).

   Architecture:
   1. Use avformat to open video file and find video stream
   2. Create hardware device context (AVHWDeviceContext)
   3. Configure codec context to output hardware frames
   4. Decode frames and extract hardware frame pointer from AVFrame.data(3)

   The hardware frame pointer is then passed to platform-specific modules
   (videotoolbox.clj, vaapi.clj, cuda.clj, d3d11.clj) for texture binding."
  (:require [lib.video.hwaccel.detect :as detect])
  (:import [org.bytedeco.ffmpeg.avformat AVFormatContext AVStream]
           [org.bytedeco.ffmpeg.avcodec AVCodec AVCodecContext AVPacket]
           [org.bytedeco.ffmpeg.avutil AVFrame AVRational AVDictionary
            AVBufferRef AVHWDeviceContext]
           [org.bytedeco.javacpp Pointer PointerPointer IntPointer]))

;; ============================================================
;; Debug Logging
;; ============================================================

(defonce ^:private debug-enabled (atom false))

(defn enable-debug!
  "Enable debug logging for raw decoder."
  []
  (reset! debug-enabled true))

(defn disable-debug!
  "Disable debug logging."
  []
  (reset! debug-enabled false))

(defn- debug-log
  "Log message if debug is enabled."
  [& args]
  (when @debug-enabled
    (apply println "[raw-decoder]" args)))

;; ============================================================
;; FFmpeg Error Handling
;; ============================================================

(defn- averror->string
  "Convert FFmpeg error code to string."
  [error-code]
  (let [buf-size 256
        buf (byte-array buf-size)]
    (org.bytedeco.ffmpeg.global.avutil/av_strerror error-code buf buf-size)
    (String. buf 0 (min buf-size
                        (count (take-while #(not= % 0) buf))))))

(defn- check-error!
  "Check FFmpeg return value, throw on error."
  [ret operation]
  (when (neg? ret)
    (throw (ex-info (str "FFmpeg error in " operation ": " (averror->string ret))
                    {:operation operation
                     :error-code ret
                     :error-string (averror->string ret)})))
  ret)

;; ============================================================
;; Hardware Device Type Mapping
;; ============================================================

(def ^:private hwaccel-type->av-hwdevice-type
  "Map our decoder types to FFmpeg AV_HWDEVICE_TYPE constants."
  {:videotoolbox org.bytedeco.ffmpeg.global.avutil/AV_HWDEVICE_TYPE_VIDEOTOOLBOX
   :vaapi        org.bytedeco.ffmpeg.global.avutil/AV_HWDEVICE_TYPE_VAAPI
   :cuda         org.bytedeco.ffmpeg.global.avutil/AV_HWDEVICE_TYPE_CUDA
   :nvdec-cuda   org.bytedeco.ffmpeg.global.avutil/AV_HWDEVICE_TYPE_CUDA
   :d3d11va      org.bytedeco.ffmpeg.global.avutil/AV_HWDEVICE_TYPE_D3D11VA
   :d3d11        org.bytedeco.ffmpeg.global.avutil/AV_HWDEVICE_TYPE_D3D11VA})

(def ^:private av-hwdevice-type->name
  "Map FFmpeg hardware device types to human-readable names."
  {org.bytedeco.ffmpeg.global.avutil/AV_HWDEVICE_TYPE_VIDEOTOOLBOX "videotoolbox"
   org.bytedeco.ffmpeg.global.avutil/AV_HWDEVICE_TYPE_VAAPI        "vaapi"
   org.bytedeco.ffmpeg.global.avutil/AV_HWDEVICE_TYPE_CUDA         "cuda"
   org.bytedeco.ffmpeg.global.avutil/AV_HWDEVICE_TYPE_D3D11VA      "d3d11va"})

;; ============================================================
;; FFmpeg Error Constants
;; ============================================================
;; These are standard FFmpeg error values - AVERROR macros convert
;; POSIX errors to negative values, and special FFmpeg errors use FFERRTAG.

(def ^:private ^:const AVERROR_EAGAIN
  "Resource temporarily unavailable - need more input data."
  -11)  ;; -EAGAIN on most systems

(def ^:private ^:const AVERROR_EOF
  "End of file reached."
  -541478725)  ;; FFERRTAG('E','O','F',' ')

(def ^:private ^:const AV_NOPTS_VALUE
  "Undefined timestamp value."
  0x8000000000000000)  ;; Same as Long/MIN_VALUE

;; ============================================================
;; AVRational Helpers
;; ============================================================

(defn- rational->double
  "Convert AVRational to double."
  [^AVRational r]
  (if (and r (not (zero? (.den r))))
    (/ (double (.num r)) (double (.den r)))
    0.0))

;; ============================================================
;; Raw Decoder Record
;; ============================================================

(defrecord RawHWDecoder
    [;; FFmpeg contexts (native pointers)
     ^AVFormatContext format-ctx
     ^AVCodecContext codec-ctx
     ^AVBufferRef hw-device-ctx
     ;; Stream info
     video-stream-idx
     width height
     fps-val
     duration-val
     time-base              ; AVRational for PTS conversion
     ;; Decoder state
     ^AVPacket packet       ; Reusable packet for reading
     ^AVFrame hw-frame      ; Reusable frame for hardware output
     ^AVFrame sw-frame      ; Reusable frame for software fallback
     ;; State
     current-pts-atom       ; Current position in seconds
     decoder-type           ; :videotoolbox, :vaapi, :cuda, :d3d11
     closed?-atom])

;; ============================================================
;; Create Hardware Device Context
;; ============================================================

(defn- create-hw-device-context!
  "Create FFmpeg hardware device context.

   Returns AVBufferRef pointer or nil on failure."
  [decoder-type device-path]
  (let [hw-type (get hwaccel-type->av-hwdevice-type decoder-type)
        device-ctx-ptr (PointerPointer. 1)]
    (when hw-type
      (debug-log "Creating hw device context, type:" (get av-hwdevice-type->name hw-type "unknown"))
      (let [ret (org.bytedeco.ffmpeg.global.avutil/av_hwdevice_ctx_create
                  device-ctx-ptr
                  hw-type
                  device-path      ; device path (nil for auto)
                  nil              ; options dict
                  0)]              ; flags
        (if (zero? ret)
          (let [ctx (AVBufferRef. (.get device-ctx-ptr 0))]
            (debug-log "Hardware device context created successfully")
            ctx)
          (do
            (debug-log "Failed to create hw device context:" (averror->string ret))
            nil))))))

;; ============================================================
;; Get Hardware Frame Pixel Format
;; ============================================================

(defn- get-hw-pix-fmt
  "Get the hardware pixel format for codec/device type combination."
  [^AVCodec codec ^Integer hw-device-type]
  (debug-log "Looking for hw pix_fmt for device type:" hw-device-type)
  (let [hw-configs (.hw_configs codec)]
    (when hw-configs
      (loop [i 0]
        (let [cfg-ptr (org.bytedeco.ffmpeg.global.avcodec/avcodec_get_hw_config codec i)]
          (when cfg-ptr
            (let [device-type (.device_type cfg-ptr)
                  methods (.methods cfg-ptr)
                  pix-fmt (.pix_fmt cfg-ptr)]
              (debug-log "  Config" i "- device_type:" device-type
                         "methods:" methods "pix_fmt:" pix-fmt)
              (if (and (= device-type hw-device-type)
                       (not (zero? (bit-and methods
                                            org.bytedeco.ffmpeg.global.avcodec/AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX))))
                (do
                  (debug-log "  Found matching hw config, pix_fmt:" pix-fmt)
                  pix-fmt)
                (recur (inc i))))))))))

;; ============================================================
;; Open Video File
;; ============================================================

(defn- open-format-context!
  "Open video file and return AVFormatContext."
  [path]
  ;; Create format context - pass null to allocate new one
  (let [fmt-ctx (AVFormatContext. nil)
        ^AVDictionary null-dict nil]
    ;; Open input file
    (check-error!
      (org.bytedeco.ffmpeg.global.avformat/avformat_open_input fmt-ctx path nil null-dict)
      "avformat_open_input")
    ;; Find stream info
    (let [^PointerPointer null-pp nil]
      (check-error!
        (org.bytedeco.ffmpeg.global.avformat/avformat_find_stream_info fmt-ctx null-pp)
        "avformat_find_stream_info"))
    fmt-ctx))

(defn- find-video-stream
  "Find the best video stream in format context.
   Returns {:stream-idx int :stream AVStream :codec AVCodec}."
  [^AVFormatContext fmt-ctx]
  (let [codec-ptr (PointerPointer. 1)
        stream-idx (org.bytedeco.ffmpeg.global.avformat/av_find_best_stream
                     fmt-ctx
                     org.bytedeco.ffmpeg.global.avutil/AVMEDIA_TYPE_VIDEO
                     -1      ; wanted stream
                     -1      ; related stream
                     codec-ptr
                     0)]     ; flags
    (check-error! stream-idx "av_find_best_stream")
    (let [stream (.streams fmt-ctx stream-idx)
          codec (AVCodec. (.get codec-ptr 0))]
      {:stream-idx stream-idx
       :stream stream
       :codec codec})))

;; ============================================================
;; Setup Codec Context
;; ============================================================

(defn- setup-codec-context!
  "Setup codec context with hardware acceleration.

   Returns {:codec-ctx AVCodecContext :hw-device-ctx AVBufferRef}."
  [^AVFormatContext fmt-ctx ^AVStream stream ^AVCodec codec decoder-type]
  (let [codec-ctx (org.bytedeco.ffmpeg.global.avcodec/avcodec_alloc_context3 codec)
        codec-params (.codecpar stream)
        ^PointerPointer null-pp nil]

    ;; Copy codec parameters from stream
    (check-error!
      (org.bytedeco.ffmpeg.global.avcodec/avcodec_parameters_to_context codec-ctx codec-params)
      "avcodec_parameters_to_context")

    ;; Create hardware device context if hardware decoding requested
    (let [hw-device-ctx (when decoder-type
                          (create-hw-device-context!
                            decoder-type
                            (when (= decoder-type :vaapi)
                              "/dev/dri/renderD128")))]

      (when hw-device-ctx
        ;; Set hardware device context on codec
        (.hw_device_ctx codec-ctx (org.bytedeco.ffmpeg.global.avutil/av_buffer_ref hw-device-ctx))
        (debug-log "Set hw_device_ctx on codec context"))

      ;; Set thread count for software fallback
      (.thread_count codec-ctx (max 1 (quot (.availableProcessors (Runtime/getRuntime)) 2)))

      ;; Open codec
      (check-error!
        (org.bytedeco.ffmpeg.global.avcodec/avcodec_open2 codec-ctx codec null-pp)
        "avcodec_open2")

      (debug-log "Codec opened successfully, pix_fmt:" (.pix_fmt codec-ctx))

      {:codec-ctx codec-ctx
       :hw-device-ctx hw-device-ctx})))

;; ============================================================
;; Create Raw Decoder
;; ============================================================

(defn create-raw-decoder
  "Create a raw hardware-accelerated decoder.

   Options:
     :decoder-type  - :videotoolbox, :vaapi, :cuda, :d3d11 (auto-detect if nil)
     :debug?        - Enable debug logging

   Returns a RawHWDecoder record or throws on failure."
  ([path]
   (create-raw-decoder path {}))
  ([path opts]
   (when (:debug? opts)
     (enable-debug!))

   (let [;; Determine decoder type
         decoder-type (or (:decoder-type opts)
                          (let [auto (detect/select-decoder)]
                            (when (not= auto :software) auto)))

         _ (debug-log "Opening file:" path "with decoder:" decoder-type)

         ;; Open format context
         fmt-ctx (open-format-context! path)

         ;; Find video stream
         {:keys [stream-idx stream codec]} (find-video-stream fmt-ctx)
         _ (debug-log "Found video stream at index:" stream-idx)

         ;; Setup codec context with hwaccel
         {:keys [codec-ctx hw-device-ctx]} (setup-codec-context!
                                              fmt-ctx stream codec decoder-type)

         ;; Extract video info
         codec-params (.codecpar stream)
         width (.width codec-params)
         height (.height codec-params)
         time-base (.time_base stream)
         fps (let [r (.avg_frame_rate stream)]
               (rational->double r))
         duration (let [dur (.duration fmt-ctx)]
                    (if (pos? dur)
                      (/ dur 1000000.0) ; AV_TIME_BASE to seconds
                      0.0))

         ;; Allocate reusable packet and frames
         packet (org.bytedeco.ffmpeg.global.avcodec/av_packet_alloc)
         hw-frame (org.bytedeco.ffmpeg.global.avutil/av_frame_alloc)
         sw-frame (org.bytedeco.ffmpeg.global.avutil/av_frame_alloc)]

     (debug-log "Video info - " width "x" height "@" fps "fps, duration:" duration "s")

     (->RawHWDecoder
       fmt-ctx
       codec-ctx
       hw-device-ctx
       stream-idx
       width height
       fps
       duration
       time-base
       packet
       hw-frame
       sw-frame
       (atom 0.0)
       decoder-type
       (atom false)))))

;; ============================================================
;; Decode Frame
;; ============================================================

(defn- read-next-packet!
  "Read next video packet from format context.
   Returns true if packet read, false at EOF."
  [^AVFormatContext fmt-ctx ^AVPacket packet video-stream-idx]
  (loop []
    (let [ret (org.bytedeco.ffmpeg.global.avformat/av_read_frame fmt-ctx packet)]
      (cond
        ;; EOF or error
        (neg? ret)
        false

        ;; Found video packet
        (= (.stream_index packet) video-stream-idx)
        true

        ;; Not video, skip and try again
        :else
        (do
          (org.bytedeco.ffmpeg.global.avcodec/av_packet_unref packet)
          (recur))))))

(defn decode-next-frame!
  "Decode the next video frame.

   Returns a map with frame info, or nil at EOF:
     :hw-frame      - AVFrame with hardware frame data
     :hw-data-ptr   - Pointer to hardware frame (AVFrame.data[3])
     :pts           - Presentation timestamp in seconds
     :is-hw-frame?  - True if frame is hardware-accelerated
     :pix-fmt       - Pixel format of the frame

   The hw-data-ptr contains:
     - macOS: CVPixelBufferRef
     - Linux VAAPI: VASurfaceID (as uintptr_t)
     - Linux CUDA: CUdeviceptr
     - Windows: ID3D11Texture2D*"
  [^RawHWDecoder decoder]
  (when-not @(:closed?-atom decoder)
    (let [{:keys [format-ctx codec-ctx packet hw-frame
                  video-stream-idx time-base current-pts-atom]} decoder]

      ;; Try to receive a frame from the decoder
      (loop [need-packet? true]
        (let [recv-ret (when-not need-packet?
                         (org.bytedeco.ffmpeg.global.avcodec/avcodec_receive_frame codec-ctx hw-frame))]

          (cond
            ;; Successfully received a frame
            (and recv-ret (zero? recv-ret))
            (let [pts-ticks (.pts hw-frame)
                  pts-seconds (if (not= pts-ticks AV_NOPTS_VALUE)
                                (* pts-ticks (rational->double time-base))
                                @current-pts-atom)
                  pix-fmt (.format hw-frame)
                  ;; Check if this is a hardware frame
                  ;; Hardware frames have data[3] set to the native handle
                  hw-data (.data hw-frame 3)
                  is-hw-frame? (and hw-data (not (.isNull hw-data)))]

              (reset! current-pts-atom pts-seconds)

              (debug-log "Decoded frame - pts:" pts-seconds
                         "pix_fmt:" pix-fmt
                         "is_hw:" is-hw-frame?
                         "hw_data:" (when hw-data (.address hw-data)))

              {:hw-frame     hw-frame
               :hw-data-ptr  hw-data
               :pts          pts-seconds
               :is-hw-frame? is-hw-frame?
               :pix-fmt      pix-fmt})

            ;; Need more data - read and send a packet
            (or need-packet?
                (= recv-ret AVERROR_EAGAIN))
            (if (read-next-packet! format-ctx packet video-stream-idx)
              (do
                ;; Send packet to decoder
                (let [send-ret (org.bytedeco.ffmpeg.global.avcodec/avcodec_send_packet codec-ctx packet)]
                  (org.bytedeco.ffmpeg.global.avcodec/av_packet_unref packet)
                  (when (neg? send-ret)
                    (debug-log "Send packet error:" (averror->string send-ret))))
                (recur false))
              ;; EOF - flush decoder
              (do
                (org.bytedeco.ffmpeg.global.avcodec/avcodec_send_packet codec-ctx nil)
                (recur false)))

            ;; EOF from decoder
            (= recv-ret AVERROR_EOF)
            nil

            ;; Other error
            :else
            (do
              (debug-log "Receive frame error:" (averror->string recv-ret))
              nil)))))))

;; ============================================================
;; Seek
;; ============================================================

(defn seek!
  "Seek to position in seconds."
  [^RawHWDecoder decoder seconds]
  (when-not @(:closed?-atom decoder)
    (let [{:keys [format-ctx codec-ctx video-stream-idx
                  time-base current-pts-atom]} decoder
          ;; Convert to stream time base
          target-ts (long (/ seconds (rational->double time-base)))]

      (debug-log "Seeking to:" seconds "s (ts:" target-ts ")")

      ;; Seek in format context
      (let [ret (org.bytedeco.ffmpeg.global.avformat/av_seek_frame format-ctx video-stream-idx target-ts
                                        org.bytedeco.ffmpeg.global.avformat/AVSEEK_FLAG_BACKWARD)]
        (when (neg? ret)
          (debug-log "Seek error:" (averror->string ret))))

      ;; Flush decoder
      (org.bytedeco.ffmpeg.global.avcodec/avcodec_flush_buffers codec-ctx)
      (reset! current-pts-atom seconds)))

  decoder)

;; ============================================================
;; Get Position
;; ============================================================

(defn tell
  "Get current position in seconds."
  [^RawHWDecoder decoder]
  @(:current-pts-atom decoder))

;; ============================================================
;; Get Decoder Info
;; ============================================================

(defn get-info
  "Get decoder information."
  [^RawHWDecoder decoder]
  {:decoder-type (:decoder-type decoder)
   :width        (:width decoder)
   :height       (:height decoder)
   :fps          (:fps-val decoder)
   :duration     (:duration-val decoder)})

;; ============================================================
;; Close
;; ============================================================

(defn close!
  "Close the decoder and release all resources."
  [^RawHWDecoder decoder]
  (when (compare-and-set! (:closed?-atom decoder) false true)
    (let [{:keys [format-ctx codec-ctx hw-device-ctx
                  packet hw-frame sw-frame]} decoder]
      (debug-log "Closing raw decoder")

      ;; Free packet and frames
      (when packet
        (org.bytedeco.ffmpeg.global.avcodec/av_packet_free (doto (PointerPointer. 1) (.put packet))))
      (when hw-frame
        (org.bytedeco.ffmpeg.global.avutil/av_frame_free (doto (PointerPointer. 1) (.put hw-frame))))
      (when sw-frame
        (org.bytedeco.ffmpeg.global.avutil/av_frame_free (doto (PointerPointer. 1) (.put sw-frame))))

      ;; Close codec context
      (when codec-ctx
        (org.bytedeco.ffmpeg.global.avcodec/avcodec_free_context
          (doto (PointerPointer. 1) (.put codec-ctx))))

      ;; Free hardware device context
      (when hw-device-ctx
        (org.bytedeco.ffmpeg.global.avutil/av_buffer_unref
          (doto (PointerPointer. 1) (.put hw-device-ctx))))

      ;; Close format context
      (when format-ctx
        (org.bytedeco.ffmpeg.global.avformat/avformat_close_input
          (doto (PointerPointer. 1) (.put format-ctx))))))

  decoder)

;; ============================================================
;; Transfer Hardware Frame to Software (fallback)
;; ============================================================

(defn transfer-hw-frame-to-sw!
  "Transfer a hardware frame to software memory.

   Used as fallback when zero-copy path fails.
   Returns the software AVFrame with data in CPU memory."
  [^RawHWDecoder decoder ^AVFrame hw-frame]
  (let [sw-frame (:sw-frame decoder)]
    ;; Transfer data from GPU to CPU
    (let [ret (org.bytedeco.ffmpeg.global.avutil/av_hwframe_transfer_data sw-frame hw-frame 0)]
      (if (zero? ret)
        (do
          ;; Copy frame properties
          (org.bytedeco.ffmpeg.global.avutil/av_frame_copy_props sw-frame hw-frame)
          sw-frame)
        (do
          (debug-log "Hardware frame transfer failed:" (averror->string ret))
          nil)))))
