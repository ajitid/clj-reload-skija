(ns lib.video.hwaccel.detect
  "Hardware acceleration detection for video decoding.
   Detects available GPU decoders per platform and probes FFmpeg capabilities."
  (:require [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader]))

;; ============================================================
;; Platform Detection
;; ============================================================

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

(defn get-arch
  "Detect CPU architecture."
  []
  (let [arch (System/getProperty "os.arch")]
    (cond
      (or (= arch "aarch64") (= arch "arm64")) :arm64
      (or (= arch "amd64") (= arch "x86_64"))  :x64
      :else                                     :unknown)))

;; ============================================================
;; GPU Vendor Detection
;; ============================================================

(defn- exec-command
  "Execute a command and return stdout lines, or nil on failure."
  [& args]
  (try
    (let [pb (ProcessBuilder. ^java.util.List (vec args))
          proc (.start pb)
          reader (BufferedReader. (InputStreamReader. (.getInputStream proc)))
          lines (doall (line-seq reader))]
      (.waitFor proc)
      (when (zero? (.exitValue proc))
        lines))
    (catch Exception _ nil)))

(defn- detect-gpu-vendor-macos
  "Detect GPU vendor on macOS using system_profiler."
  []
  ;; On macOS, VideoToolbox works for all GPUs (Apple Silicon and Intel/AMD)
  ;; The GPU vendor doesn't matter for hwaccel selection
  :apple)

(defn- detect-gpu-vendor-linux
  "Detect GPU vendor on Linux by checking /proc/driver and lspci."
  []
  (or
    ;; Check for NVIDIA driver
    (when (some->> (exec-command "lsmod")
                   (some #(str/includes? % "nvidia")))
      :nvidia)
    ;; Check lspci for GPU info
    (when-let [lspci-lines (exec-command "lspci")]
      (let [vga-lines (filter #(str/includes? % "VGA") lspci-lines)
            gpu-info (str/join " " vga-lines)]
        (cond
          (str/includes? gpu-info "NVIDIA") :nvidia
          (str/includes? gpu-info "AMD")    :amd
          (str/includes? gpu-info "Intel")  :intel
          :else                             :unknown)))
    :unknown))

(defn- detect-gpu-vendor-windows
  "Detect GPU vendor on Windows using wmic."
  []
  (when-let [lines (exec-command "wmic" "path" "win32_VideoController" "get" "name")]
    (let [gpu-info (str/join " " lines)]
      (cond
        (str/includes? gpu-info "NVIDIA") :nvidia
        (str/includes? gpu-info "AMD")    :amd
        (str/includes? gpu-info "Intel")  :intel
        :else                             :unknown))))

(defn get-gpu-vendor
  "Detect GPU vendor on the current platform.
   Returns :nvidia, :amd, :intel, :apple, or :unknown."
  []
  (case (get-platform)
    :macos   (detect-gpu-vendor-macos)
    :linux   (detect-gpu-vendor-linux)
    :windows (detect-gpu-vendor-windows)
    :unknown))

;; ============================================================
;; FFmpeg Hwaccel Probing
;; ============================================================

(defonce ^:private hwaccel-cache (atom nil))

(defn- probe-ffmpeg-hwaccels
  "Probe available FFmpeg hardware accelerators.
   Returns a set of available hwaccel names."
  []
  (or @hwaccel-cache
      (let [result (try
                     ;; Try to use FFmpeg's hwaccel list via JavaCV
                     ;; This is a simplified check - in production would use avutil
                     (let [platform (get-platform)]
                       (case platform
                         ;; macOS always has VideoToolbox
                         :macos #{:videotoolbox}
                         ;; Linux - check for vaapi and cuda
                         :linux (let [vendor (get-gpu-vendor)]
                                  (case vendor
                                    :nvidia #{:cuda :nvdec}
                                    (:amd :intel) #{:vaapi}
                                    #{}))
                         ;; Windows - D3D11VA is generally available
                         :windows #{:d3d11va :dxva2}
                         #{}))
                     (catch Exception _ #{}))]
        (reset! hwaccel-cache result)
        result)))

(defn- check-videotoolbox-available
  "Check if VideoToolbox is available (macOS only)."
  []
  (and (= (get-platform) :macos)
       ;; VideoToolbox is always available on macOS 10.8+
       true))

(defn- check-vaapi-available
  "Check if VAAPI is available (Linux only)."
  []
  (and (= (get-platform) :linux)
       ;; Check for vainfo or /dev/dri/renderD*
       (or (some? (exec-command "which" "vainfo"))
           (some->> (exec-command "ls" "/dev/dri/")
                    (some #(str/starts-with? % "render"))))))

(defn- check-cuda-available
  "Check if CUDA/NVDEC is available (Linux/Windows NVIDIA)."
  []
  (and (= (get-gpu-vendor) :nvidia)
       ;; Check for nvidia-smi
       (some? (exec-command "nvidia-smi"))))

(defn- check-d3d11va-available
  "Check if D3D11VA is available (Windows only)."
  []
  ;; D3D11VA is available on Windows 8+ with DirectX 11
  (= (get-platform) :windows))

;; ============================================================
;; Public API
;; ============================================================

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
      :macos   (if (check-videotoolbox-available)
                 :videotoolbox
                 :software)
      :linux   (let [vendor (get-gpu-vendor)]
                 (case vendor
                   :nvidia (if (check-cuda-available)
                             :nvdec-cuda
                             :software)
                   (:amd :intel) (if (check-vaapi-available)
                                   :vaapi
                                   :software)
                   :software))
      :windows (if (check-d3d11va-available)
                 :d3d11va
                 :software)
      :software)))

(defn available?
  "Check if a specific hardware decoder is available."
  [decoder-type]
  (case decoder-type
    :software      true
    :videotoolbox  (check-videotoolbox-available)
    :vaapi         (check-vaapi-available)
    :nvdec-cuda    (check-cuda-available)
    :d3d11va       (check-d3d11va-available)
    false))

(defn get-ffmpeg-codec-name
  "Get the FFmpeg codec name for hardware-accelerated decoding.
   Returns nil if hardware acceleration should not be used."
  [decoder-type video-codec]
  (when (available? decoder-type)
    (case decoder-type
      :videotoolbox (case video-codec
                      :h264 "h264_videotoolbox"
                      :hevc "hevc_videotoolbox"
                      :vp9  nil  ; VideoToolbox doesn't support VP9
                      nil)
      :vaapi        (case video-codec
                      :h264 "h264_vaapi"
                      :hevc "hevc_vaapi"
                      :vp9  "vp9_vaapi"
                      nil)
      :nvdec-cuda   (case video-codec
                      :h264 "h264_cuvid"
                      :hevc "hevc_cuvid"
                      :vp9  "vp9_cuvid"
                      nil)
      :d3d11va      (case video-codec
                      ;; D3D11VA uses hwaccel, not separate decoders
                      :h264 nil
                      :hevc nil
                      nil)
      nil)))

(defn get-ffmpeg-hwaccel-name
  "Get the FFmpeg hwaccel name for AVCodecContext.
   This is different from codec name - it's for the hwaccel device."
  [decoder-type]
  (case decoder-type
    :videotoolbox "videotoolbox"
    :vaapi        "vaapi"
    :nvdec-cuda   "cuda"
    :d3d11va      "d3d11va"
    nil))

(defn decoder-info
  "Get detailed information about the current decoder configuration.
   Returns a map with platform, GPU vendor, and decoder selection."
  []
  {:platform   (get-platform)
   :arch       (get-arch)
   :gpu-vendor (get-gpu-vendor)
   :decoder    (select-decoder)
   :available  (into {} (for [d [:videotoolbox :vaapi :nvdec-cuda :d3d11va :software]]
                          [d (available? d)]))})

;; ============================================================
;; Zero-Copy Specific Detection
;; ============================================================

(defn- check-egl-available-linux
  "Check if EGL is available on Linux (required for VAAPI zero-copy).
   VAAPI zero-copy requires EGL context, not GLX."
  []
  (and (= (get-platform) :linux)
       ;; Check for libEGL.so
       (or (some? (exec-command "ldconfig" "-p" "| grep" "libEGL"))
           ;; Alternative: check common library paths
           (some? (exec-command "ls" "/usr/lib/x86_64-linux-gnu/libEGL.so.1"))
           (some? (exec-command "ls" "/usr/lib/libEGL.so.1"))
           ;; Most modern Linux systems have EGL
           true)))

(defn- check-libva-available-linux
  "Check if libva is available (required for VAAPI zero-copy)."
  []
  (and (= (get-platform) :linux)
       (or (some? (exec-command "pkg-config" "--exists" "libva"))
           (some? (exec-command "ls" "/usr/lib/x86_64-linux-gnu/libva.so.2"))
           (some? (exec-command "ls" "/usr/lib/libva.so.2")))))

(defn- check-cuda-driver-available
  "Check if CUDA driver library is available (libcuda.so or nvcuda.dll)."
  []
  (case (get-platform)
    :linux   (or (some? (exec-command "ls" "/usr/lib/x86_64-linux-gnu/libcuda.so.1"))
                 (some? (exec-command "ls" "/usr/lib/libcuda.so.1"))
                 (some? (exec-command "ls" "/usr/lib64/libcuda.so.1")))
    :windows (some? (exec-command "where" "nvcuda.dll"))
    false))

(defn- check-d3d11-available-windows
  "Check if D3D11 is available (Windows only)."
  []
  (= (get-platform) :windows))

(defn zero-copy-available?
  "Check if zero-copy decoding is available for a decoder type.

   Zero-copy has stricter requirements than hardware decoding:
   - VideoToolbox: Always available on macOS (CGL context required)
   - VAAPI: Requires EGL context + libva (Linux)
   - CUDA: Requires CUDA driver API (Linux/Windows NVIDIA)
   - D3D11: Requires WGL_NV_DX_interop extension (Windows)"
  [decoder-type]
  (case decoder-type
    :videotoolbox (= (get-platform) :macos)
    :vaapi        (and (check-vaapi-available)
                       (check-egl-available-linux)
                       (check-libva-available-linux))
    :nvdec-cuda   (and (check-cuda-available)
                       (check-cuda-driver-available))
    (:cuda :nvdec) (and (check-cuda-available)
                        (check-cuda-driver-available))
    :d3d11va      (check-d3d11-available-windows)
    :d3d11        (check-d3d11-available-windows)
    :software     false  ; Software never has zero-copy
    false))

(defn zero-copy-requirements
  "Get the requirements for zero-copy on each platform.
   Returns a map describing what's needed."
  [decoder-type]
  (case decoder-type
    :videotoolbox
    {:gl-context :cgl
     :extensions []
     :libraries  ["CoreVideo" "IOSurface" "OpenGL (CGL)"]
     :notes      "macOS CGL context required. IOSurface binding via CGLTexImageIOSurface2D."}

    :vaapi
    {:gl-context :egl
     :extensions ["EGL_LINUX_DMA_BUF_EXT" "GL_OES_EGL_image"]
     :libraries  ["libva" "libEGL" "libGLESv2 or libGL"]
     :notes      "Requires EGL context (not GLX). SDL3 can create EGL contexts."}

    (:cuda :nvdec-cuda)
    {:gl-context :any
     :extensions []
     :libraries  ["libcuda.so (Linux)" "nvcuda.dll (Windows)"]
     :notes      "CUDA/GL interop. Not true zero-copy - GPU-to-GPU memcpy."}

    (:d3d11 :d3d11va)
    {:gl-context :wgl
     :extensions ["WGL_NV_DX_interop"]
     :libraries  ["opengl32.dll"]
     :notes      "Works on all vendors despite 'NV' name. Standard Windows extension."}

    :software
    {:gl-context :any
     :extensions []
     :libraries  []
     :notes      "Software decode always copies through CPU."}

    nil))

(defn zero-copy-info
  "Get comprehensive zero-copy capability information for current system."
  []
  (let [decoder (select-decoder)
        platform (get-platform)
        requirements (zero-copy-requirements decoder)]
    {:platform          platform
     :arch              (get-arch)
     :gpu-vendor        (get-gpu-vendor)
     :decoder           decoder
     :zero-copy-available (zero-copy-available? decoder)
     :requirements      requirements
     :checks            (case decoder
                          :videotoolbox {:cgl-available true}
                          :vaapi        {:egl-available (check-egl-available-linux)
                                         :libva-available (check-libva-available-linux)}
                          :nvdec-cuda   {:cuda-driver-available (check-cuda-driver-available)}
                          :d3d11va      {:d3d11-available (check-d3d11-available-windows)}
                          {})}))

;; ============================================================
;; GL Context Type Detection
;; ============================================================

(defn get-gl-context-type
  "Detect the current GL context type.
   Returns :cgl (macOS), :egl (Linux EGL), :glx (Linux GLX), :wgl (Windows), or :unknown.

   Note: This is a static guess based on platform. Runtime detection would
   require querying the actual GL context."
  []
  (case (get-platform)
    :macos   :cgl
    :linux   :egl  ; Assume EGL (SDL3 default). Could be GLX on older systems.
    :windows :wgl
    :unknown))

(defn context-compatible-with-zero-copy?
  "Check if the current GL context type is compatible with zero-copy for a decoder.

   decoder-type: :videotoolbox, :vaapi, :cuda, :d3d11
   context-type: :cgl, :egl, :glx, :wgl"
  [decoder-type context-type]
  (case decoder-type
    :videotoolbox (= context-type :cgl)
    :vaapi        (= context-type :egl)  ; VAAPI zero-copy requires EGL, not GLX
    (:cuda :nvdec-cuda) true  ; CUDA works with any GL context
    (:d3d11 :d3d11va) (= context-type :wgl)
    :software     false
    false))
