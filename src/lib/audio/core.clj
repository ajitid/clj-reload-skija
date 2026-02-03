(ns lib.audio.core
  "Love2D-style audio API for Clojure.

   Provides simple, game-friendly audio playback:
   - (file->audio \"sound.wav\") -> Source (default :static, fully buffered)
   - (file->audio \"music.mp3\" {:type :stream}) -> Source (streaming, low memory)
   - (play! source), (stop! source), (pause! source), (resume! source)
   - (set-volume! source 0.5), (set-looping! source true)
   - (playing? source), (duration source)
   - (close! source) - optional, resources auto-close on GC (like Skija)

   Type options (following Love2D model):
   - :static (default) - Entire audio loaded into memory via Clip. Best for short sounds.
   - :stream - SourceDataLine streaming. Best for long audio (music, ambience).

   Supports WAV, OGG, and MP3 formats via Java Sound SPIs."
  (:require [lib.audio.protocol :as proto]
            [lib.audio.static :as static]
            [lib.audio.streaming :as streaming]
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

;; ============================================================
;; Loading
;; ============================================================

(defn file->audio
  "Load audio source from file (WAV/OGG/MP3).

   Options:
     :type - :static (default, fully buffered) or :stream (streaming, low memory)

   :static loads the entire audio into memory - best for short sounds (SFX).
   :stream uses SourceDataLine for streaming - best for long audio (music).

   Returns a Source handle. Resources are automatically released when
   the Source is garbage collected (like Love2D)."
  ([path]
   (file->audio path {}))
  ([path opts]
   (let [id (swap! state/source-counter inc)
         source-type (get opts :type :static)
         source-impl (case source-type
                       :stream (streaming/create-stream-source path)
                       (static/create-static-source path))
         source (->Source id)]
     (swap! state/sources assoc id source-impl)
     ;; Register automatic cleanup when Source is GC'd
     ;; Note: cleanup fn must not capture `source` (would prevent GC)
     (.register cleaner source
                (fn []
                  (when-let [impl (get @state/sources id)]
                    (try
                      (proto/close* impl)
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
  (when-let [impl (get-source-data source)]
    (proto/play* impl))
  source)

(defn stop!
  "Stop the source and reset to beginning."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/stop* impl))
  source)

(defn pause!
  "Pause the source at current position."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/pause* impl))
  source)

(defn resume!
  "Resume the source from paused position."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/resume* impl))
  source)

;; ============================================================
;; Properties
;; ============================================================

(defn set-volume!
  "Set volume (0.0 = silent, 1.0 = normal, >1.0 = boost)."
  [source volume]
  (when-let [impl (get-source-data source)]
    (proto/set-volume* impl volume))
  source)

(defn set-looping!
  "Set whether the source should loop."
  [source looping?]
  (when-let [impl (get-source-data source)]
    (proto/set-loop* impl looping?))
  source)

;; ============================================================
;; Query
;; ============================================================

(defn playing?
  "Check if the source is currently playing."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/playing?* impl)))

(defn paused?
  "Check if the source is paused."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/paused?* impl)))

(defn duration
  "Get total duration in seconds."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/duration* impl)))

(defn tell
  "Get current playback position in seconds."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/tell* impl)))

(defn seek!
  "Seek to a position in seconds."
  [source seconds]
  (when-let [impl (get-source-data source)]
    (proto/seek* impl seconds))
  source)

(defn get-volume
  "Get current volume setting."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/get-volume* impl)))

(defn looping?
  "Check if the source is set to loop."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/looping?* impl)))

(defn seeking?
  "Check if the source is currently seeking (streaming sources only)."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/seeking?* impl)))

;; ============================================================
;; Resource management
;; ============================================================

(defn close!
  "Immediately close a source's resources (like Skija's .close).
   Optional - resources are auto-closed when Source is garbage collected."
  [source]
  (when-let [impl (get-source-data source)]
    (proto/close* impl)
    (swap! state/sources dissoc (:id source)))
  nil)

(defn cleanup!
  "Dispose of all sources. Called on window close."
  []
  (doseq [[_id impl] @state/sources]
    (try
      (proto/close* impl)
      (catch Exception _)))
  (reset! state/sources {}))
