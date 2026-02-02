(ns lib.video.sync
  "Audio/video synchronization using audio as master clock.

   Sync Strategy:
   - Audio playback drives timing (master clock)
   - Video frames are displayed when their PTS matches audio position
   - Uses a sync window to handle minor timing variations
   - Drops/repeats video frames if sync drifts too far

   This approach provides smooth audio (no dropouts) while keeping
   video in sync within a configurable tolerance window.")

;; ============================================================
;; Sync Configuration
;; ============================================================

;; Maximum allowed drift before correction (seconds)
(def ^:private max-drift 0.05)

;; Frame skip threshold - if video is this far behind, skip to catch up
(def ^:private skip-threshold 0.1)

;; Frame repeat threshold - if video is this far ahead, wait
(def ^:private repeat-threshold 0.05)

;; ============================================================
;; Sync State
;; ============================================================

(defrecord SyncState
    [;; Audio clock (authoritative time source)
     audio-position      ; atom - current audio position in seconds
     ;; Video state
     video-pts           ; atom - PTS of currently displayed video frame
     ;; Sync metrics
     drift               ; atom - current A/V drift (audio-pos - video-pts)
     frames-dropped      ; atom - count of dropped frames
     frames-repeated])   ; atom - count of repeated frames

(defn create-sync-state
  "Create a new synchronization state."
  []
  (->SyncState
    (atom 0.0)  ; audio-position
    (atom 0.0)  ; video-pts
    (atom 0.0)  ; drift
    (atom 0)    ; frames-dropped
    (atom 0)))  ; frames-repeated

;; ============================================================
;; Sync Operations
;; ============================================================

(defn update-audio-position!
  "Update audio position from the audio playback clock."
  [^SyncState sync-state audio-pos]
  (reset! (:audio-position sync-state) audio-pos))

(defn update-video-pts!
  "Update the PTS of the currently displayed video frame."
  [^SyncState sync-state pts]
  (reset! (:video-pts sync-state) pts))

(defn calculate-drift
  "Calculate the current A/V drift (positive = video behind, negative = video ahead)."
  [^SyncState sync-state]
  (let [audio-pos @(:audio-position sync-state)
        video-pts @(:video-pts sync-state)]
    (- audio-pos video-pts)))

(defn sync-decision
  "Determine what action to take for video frame timing.

   Returns one of:
   - :display - display the next frame normally
   - :skip    - skip frames to catch up (video behind)
   - :repeat  - repeat current frame (video ahead)
   - :wait    - waiting for audio to start"
  [^SyncState sync-state next-frame-pts]
  (let [audio-pos @(:audio-position sync-state)
        frame-delta (- audio-pos next-frame-pts)]
    (cond
      ;; Audio not started or at beginning
      (< audio-pos 0.001)
      :wait

      ;; Video is too far behind - skip to catch up
      (> frame-delta skip-threshold)
      :skip

      ;; Video is ahead of audio - repeat current frame
      (< frame-delta (- repeat-threshold))
      :repeat

      ;; Within acceptable range - display normally
      :else
      :display)))

(defn should-decode-frame?
  "Check if we should decode the next video frame based on sync state.

   audio-pos: current audio playback position
   video-pts: PTS of last displayed frame
   frame-duration: expected duration of one frame (1/fps)"
  [audio-pos video-pts frame-duration]
  (let [next-frame-target (+ video-pts frame-duration)
        time-until-next (- next-frame-target audio-pos)]
    ;; Decode when we're within half a frame of needing it
    ;; or if we're behind
    (<= time-until-next (* frame-duration 0.5))))

(defn reset-sync!
  "Reset sync state (e.g., after seek)."
  [^SyncState sync-state]
  (reset! (:audio-position sync-state) 0.0)
  (reset! (:video-pts sync-state) 0.0)
  (reset! (:drift sync-state) 0.0))

(defn reset-sync-to!
  "Reset sync state to a specific position (e.g., after seek)."
  [^SyncState sync-state position]
  (reset! (:audio-position sync-state) position)
  (reset! (:video-pts sync-state) position)
  (reset! (:drift sync-state) 0.0))

;; ============================================================
;; Sync Metrics
;; ============================================================

(defn record-frame-drop!
  "Record that a frame was dropped."
  [^SyncState sync-state]
  (swap! (:frames-dropped sync-state) inc))

(defn record-frame-repeat!
  "Record that a frame was repeated."
  [^SyncState sync-state]
  (swap! (:frames-repeated sync-state) inc))

(defn get-sync-metrics
  "Get current sync metrics for debugging/display."
  [^SyncState sync-state]
  {:audio-position @(:audio-position sync-state)
   :video-pts      @(:video-pts sync-state)
   :drift          (calculate-drift sync-state)
   :frames-dropped @(:frames-dropped sync-state)
   :frames-repeated @(:frames-repeated sync-state)})
