(ns lib.audio.protocol
  "AudioSource protocol defining the interface for all audio source types.")

(defprotocol AudioSource
  "Protocol for audio playback sources.
   Implementations: StaticSource (Clip), StreamSource (SourceDataLine)"
  (play* [this] "Play from beginning, returns this")
  (stop* [this] "Stop and reset to beginning, returns this")
  (pause* [this] "Pause at current position, returns this")
  (resume* [this] "Resume from paused position, returns this")
  (seek* [this seconds] "Seek to position in seconds, returns this")
  (tell* [this] "Get current position in seconds")
  (duration* [this] "Get total duration in seconds")
  (set-volume* [this volume] "Set volume 0.0-1.0+, returns this")
  (set-loop* [this loop?] "Set looping on/off, returns this")
  (playing?* [this] "Check if currently playing")
  (paused?* [this] "Check if paused")
  (looping?* [this] "Check if looping is enabled")
  (seeking?* [this] "Check if seek is in progress")
  (get-volume* [this] "Get current volume")
  (close* [this] "Release all resources"))
