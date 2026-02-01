(ns lib.audio.core
  "Love2D-style audio API for Clojure.

   Provides simple, game-friendly audio playback:
   - (new-source \"sound.wav\") -> Source
   - (play! source), (stop! source), (pause! source), (resume! source)
   - (set-volume! source 0.5), (set-looping! source true)
   - (playing? source), (duration source)
   - (close! source) - optional, resources auto-close on GC (like Skija)

   Supports WAV, OGG, and MP3 formats via Java Sound SPIs."
  (:require [lib.audio.internal :as audio]
            [lib.audio.state :as state])
  (:import [java.lang.ref Cleaner]))

;; ============================================================
;; Automatic cleanup via JVM Cleaner (Java 9+)
;; ============================================================

(defonce ^:private cleaner (Cleaner/create))

;; ============================================================
;; Source record
;; ============================================================

(defrecord Source [id])

(defn- get-source-data
  "Get the internal data for a source."
  [source]
  (when source
    (get @state/sources (:id source))))

(defn- update-source-data!
  "Update the internal data for a source."
  [source f & args]
  (swap! state/sources update (:id source) #(apply f % args)))

;; ============================================================
;; Loading
;; ============================================================

(defn new-source
  "Load audio source from file (WAV/OGG/MP3).

   Options:
     :type - :static (default, fully buffered) or :stream (not yet implemented)

   Returns a Source handle. Resources are automatically released when
   the Source is garbage collected (like Love2D)."
  ([path]
   (new-source path {}))
  ([path opts]
   (let [id (swap! state/source-counter inc)
         clip (audio/create-clip path)
         source-data {:clip clip
                      :path path
                      :volume 1.0
                      :looping? false
                      :paused? false}
         source (->Source id)]
     (swap! state/sources assoc id source-data)
     ;; Register automatic cleanup when Source is GC'd
     ;; Note: cleanup fn must not capture `source` (would prevent GC)
     (.register cleaner source
                (fn []
                  (when-let [{:keys [clip]} (get @state/sources id)]
                    (try
                      (audio/close-clip! clip)
                      (catch Exception _)))
                  (swap! state/sources dissoc id)))
     source)))

;; ============================================================
;; Playback control
;; ============================================================

(defn play!
  "Play the source from the beginning.
   If already playing, restarts from the beginning."
  [source]
  (when-let [{:keys [clip looping?]} (get-source-data source)]
    (audio/stop-clip! clip)
    (when looping?
      (audio/set-clip-loop! clip true))
    (audio/play-clip! clip)
    (update-source-data! source assoc :paused? false))
  source)

(defn stop!
  "Stop the source and reset to beginning."
  [source]
  (when-let [{:keys [clip]} (get-source-data source)]
    (audio/stop-clip! clip)
    (update-source-data! source assoc :paused? false))
  source)

(defn pause!
  "Pause the source at current position."
  [source]
  (when-let [{:keys [clip]} (get-source-data source)]
    (when (audio/clip-playing? clip)
      (audio/pause-clip! clip)
      (update-source-data! source assoc :paused? true)))
  source)

(defn resume!
  "Resume the source from paused position."
  [source]
  (when-let [{:keys [clip looping?]} (get-source-data source)]
    (when (:paused? (get-source-data source))
      (when looping?
        (audio/set-clip-loop! clip true))
      (audio/resume-clip! clip)
      (update-source-data! source assoc :paused? false)))
  source)

;; ============================================================
;; Properties
;; ============================================================

(defn set-volume!
  "Set volume (0.0 = silent, 1.0 = normal, >1.0 = boost)."
  [source volume]
  (when-let [{:keys [clip]} (get-source-data source)]
    (audio/set-clip-volume! clip volume)
    (update-source-data! source assoc :volume volume))
  source)

(defn set-looping!
  "Set whether the source should loop."
  [source looping?]
  (when-let [{:keys [clip]} (get-source-data source)]
    (audio/set-clip-loop! clip looping?)
    (update-source-data! source assoc :looping? looping?))
  source)

(defn set-pitch!
  "Set pitch/playback rate.
   Note: Java Sound Clip does not support pitch modification.
   This is a no-op for API compatibility."
  [source _pitch]
  source)

;; ============================================================
;; Query
;; ============================================================

(defn playing?
  "Check if the source is currently playing."
  [source]
  (when-let [{:keys [clip]} (get-source-data source)]
    (audio/clip-playing? clip)))

(defn paused?
  "Check if the source is paused."
  [source]
  (:paused? (get-source-data source) false))

(defn duration
  "Get total duration in seconds."
  [source]
  (when-let [{:keys [clip]} (get-source-data source)]
    (audio/clip-duration clip)))

(defn tell
  "Get current playback position in seconds."
  [source]
  (when-let [{:keys [clip]} (get-source-data source)]
    (audio/clip-position clip)))

(defn seek!
  "Seek to a position in seconds."
  [source seconds]
  (when-let [{:keys [clip]} (get-source-data source)]
    (audio/seek-clip! clip seconds))
  source)

(defn get-volume
  "Get current volume setting."
  [source]
  (:volume (get-source-data source) 1.0))

(defn looping?
  "Check if the source is set to loop."
  [source]
  (:looping? (get-source-data source) false))

;; ============================================================
;; Resource management
;; ============================================================

(defn close!
  "Immediately close a source's resources (like Skija's .close).
   Optional - resources are auto-closed when Source is garbage collected."
  [source]
  (when-let [{:keys [clip]} (get-source-data source)]
    (audio/close-clip! clip)
    (swap! state/sources dissoc (:id source)))
  nil)

(defn cleanup!
  "Dispose of all sources. Called on window close."
  []
  (doseq [[id {:keys [clip]}] @state/sources]
    (try
      (audio/close-clip! clip)
      (catch Exception _)))
  (reset! state/sources {}))
