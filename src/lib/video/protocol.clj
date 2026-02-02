(ns lib.video.protocol
  "VideoSource protocol defining the interface for all video source types.")

(defprotocol VideoSource
  "Protocol for video playback sources.
   Implementations: SoftwareDecoder (CPU path), HardwareDecoder (GPU path)"
  (play* [this] "Start or resume playback, returns this")
  (stop* [this] "Stop and reset to beginning, returns this")
  (pause* [this] "Pause at current position, returns this")
  (seek* [this seconds] "Seek to position in seconds, returns this")
  (tell* [this] "Get current position in seconds")
  (duration* [this] "Get total duration in seconds")
  (width* [this] "Get video width in pixels")
  (height* [this] "Get video height in pixels")
  (fps* [this] "Get video frame rate")
  (playing?* [this] "Check if currently playing")
  (paused?* [this] "Check if paused")
  (ensure-first-frame!* [this] "Decode and upload first frame if not already done")
  (current-frame* [this direct-context] "Get current frame as Skia Image (GPU-backed)")
  (advance-frame!* [this dt] "Advance playback by dt seconds, decode next frame if needed")
  (close* [this] "Release all resources")
  ;; Audio sync methods
  (has-audio?* [this] "Check if source has audio track")
  (set-volume!* [this volume] "Set audio volume 0.0-1.0")
  (get-volume* [this] "Get current audio volume")
  (audio-position* [this] "Get audio playback position (master clock for sync)"))
