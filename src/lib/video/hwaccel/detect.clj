(ns lib.video.hwaccel.detect
  "Hardware acceleration detection for video decoding.
   Detects available GPU decoders per platform.")

(defn get-platform
  "Detect the current platform."
  []
  (let [os-name (System/getProperty "os.name")
        os-lower (.toLowerCase os-name)]
    (cond
      (.contains os-lower "mac")   :macos
      (.contains os-lower "linux") :linux
      (.contains os-lower "win")   :windows
      :else                        :unknown)))

(defn get-gpu-vendor
  "Attempt to detect GPU vendor on Linux.
   Returns :nvidia, :amd, :intel, or :unknown."
  []
  ;; This is a placeholder - would need JNA or shell command to detect
  ;; For now, return :unknown to trigger software fallback
  :unknown)

(defn select-decoder
  "Select the best available decoder for the current platform.
   Returns a keyword for the decoder type:
   - :videotoolbox (macOS)
   - :vaapi (Linux Intel/AMD)
   - :nvdec-cuda (Linux NVIDIA)
   - :d3d11va (Windows)
   - :software (fallback)"
  []
  (let [platform (get-platform)]
    (case platform
      :macos   :videotoolbox
      :linux   (case (get-gpu-vendor)
                 :nvidia :nvdec-cuda
                 (:amd :intel) :vaapi
                 :software)
      :windows :d3d11va
      :software)))

(defn available?
  "Check if a specific hardware decoder is available.
   Currently returns true for software, false for hardware
   (Phase 2 will implement proper detection)."
  [decoder-type]
  (case decoder-type
    :software true
    ;; Hardware decoders will be checked in Phase 2
    false))

(defn get-ffmpeg-codec-name
  "Get the FFmpeg codec name for hardware acceleration.
   Returns nil if hardware acceleration should not be used."
  [decoder-type video-codec]
  (when (available? decoder-type)
    (case decoder-type
      :videotoolbox (case video-codec
                      :h264 "h264_videotoolbox"
                      :hevc "hevc_videotoolbox"
                      nil)
      :vaapi        (case video-codec
                      :h264 "h264_vaapi"
                      :hevc "hevc_vaapi"
                      nil)
      :nvdec-cuda   (case video-codec
                      :h264 "h264_cuvid"
                      :hevc "hevc_cuvid"
                      nil)
      :d3d11va      (case video-codec
                      :h264 "h264_d3d11va"
                      :hevc "hevc_d3d11va"
                      nil)
      nil)))
