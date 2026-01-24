#!/usr/bin/env bb
;; JVM Pool Manager - Disposable Process Sandbox Pool
;; Usage: bb pool.clj <command>
;;
;; Commands:
;;   start [--spare N]  Start pool, keep N idle JVMs ready (default: 2, min: 1)
;;   stop              Kill all JVMs, cleanup
;;   open              Open app (restarts if already running)
;;   close             Close app
;;   status            Show pool state
;;   connect           Print connection info for active JVM

;; this is basic pool manager... an advanced one with a different use case (worker pool) is at https://deepwiki.com/ninjudd/drip

(ns pool
  (:require [babashka.process :as proc]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.lang ProcessHandle]))

;; ============================================================================
;; Platform Detection
;; ============================================================================

(def windows? (str/includes? (str/lower-case (System/getProperty "os.name")) "windows"))

;; Lock for thread-safe printing during parallel spawn
(def print-lock (Object.))

(defn safe-println [& args]
  (locking print-lock
    (apply println args)))

;; ============================================================================
;; Configuration
;; ============================================================================

(def pool-dir (fs/path (fs/cwd) ".jvm-pool"))
(def state-file (fs/path pool-dir "state.edn"))
(def jvms-dir (fs/path pool-dir "jvms"))
(def active-port-file (fs/path pool-dir "active-port"))

(defn detect-platform-alias []
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (str/lower-case (System/getProperty "os.arch"))]
    (cond
      (str/includes? os "windows") "dev:windows"
      (str/includes? os "linux") "dev:linux"
      (and (str/includes? os "mac") (str/includes? arch "aarch64")) "dev:macos-arm64"
      (str/includes? os "mac") "dev:macos-x64"
      :else "dev")))

(def default-config
  {:pool-size 2
   :cmd (str "clj -M:" (detect-platform-alias))
   :project-dir (str (fs/cwd))
   :nrepl-timeout-ms 60000})  ;; wait up to 60s for nREPL

;; ============================================================================
;; State Management
;; ============================================================================

(defn ensure-dirs! []
  (fs/create-dirs pool-dir)
  (fs/create-dirs jvms-dir))

(defn load-state []
  (if (fs/exists? state-file)
    (edn/read-string (slurp (str state-file)))
    {:config default-config
     :idle []
     :active nil}))

(defn save-state! [state]
  (spit (str state-file) (pr-str state)))

(defn update-state! [f & args]
  (let [new-state (apply f (load-state) args)]
    (save-state! new-state)
    new-state))

;; ============================================================================
;; Process Management
;; ============================================================================

(defn jvm-dir [id]
  (fs/path jvms-dir id))

(defn parse-nrepl-port
  "Parse nREPL port from log file. Looks for 'nREPL server started on port XXXX'"
  [log-file]
  (when (fs/exists? log-file)
    (let [content (slurp (str log-file))]
      (when-let [match (re-find #"(?i)nrepl.*?(?:port|started on)\s*:?\s*(\d+)" content)]
        (parse-long (second match))))))

(defn wait-for-nrepl
  "Wait for nREPL server to start, return port number"
  [id timeout-ms]
  (let [log-file (fs/path (jvm-dir id) "out.log")
        start (System/currentTimeMillis)]
    (loop []
      (if-let [port (parse-nrepl-port log-file)]
        port
        (if (> (- (System/currentTimeMillis) start) timeout-ms)
          (do
            (println "Timeout waiting for nREPL. Check" (str log-file))
            nil)
          (do
            (Thread/sleep 500)
            (recur)))))))

(defn process-alive? [pid]
  (try
    ;; Use Java's ProcessHandle for cross-platform process checking
    (let [handle (ProcessHandle/of pid)]
      (and (.isPresent handle)
           (.isAlive (.get handle))))
    (catch Exception _ false)))

;; ============================================================================
;; File Locking (for concurrent access protection)
;; ============================================================================

(def lock-dir (fs/path pool-dir "pool.lock.d"))

(defn acquire-file-lock!
  "Attempt to acquire lock using atomic mkdir. Returns true if acquired."
  []
  (ensure-dirs!)
  (let [lock-path (str lock-dir)
        pid-file (str (fs/path lock-dir "pid"))
        my-pid (.pid (ProcessHandle/current))]
    (try
      ;; mkdir is atomic - only one process can create it
      (fs/create-dir lock-path)
      ;; We got the lock, write our PID
      (spit pid-file (str my-pid))
      true
      (catch Exception _
        ;; Directory exists - check if holder is still alive
        (if (fs/exists? pid-file)
          (let [holder-pid (try (parse-long (str/trim (slurp pid-file))) (catch Exception _ nil))]
            (if (and holder-pid (process-alive? holder-pid))
              false  ;; Lock held by another live process
              ;; Stale lock, try to clean and re-acquire
              (do
                (try (fs/delete-tree lock-path) (catch Exception _))
                (try
                  (fs/create-dir lock-path)
                  (spit pid-file (str my-pid))
                  true
                  (catch Exception _ false)))))
          false)))))

(defn release-file-lock! []
  (try
    (when (fs/exists? lock-dir)
      (fs/delete-tree (str lock-dir)))
    (catch Exception _)))

(defmacro with-state-lock
  "Execute body with an exclusive file lock on the pool state.
   Spins until lock is acquired. Cross-platform (Windows/macOS/Linux)."
  [& body]
  `(do
     (loop [attempts# 0]
       (when-not (acquire-file-lock!)
         (when (zero? (mod attempts# 10))
           (safe-println "Waiting for lock..."))
         (Thread/sleep 100)
         (recur (inc attempts#))))
     (try
       ~@body
       (finally
         (release-file-lock!)))))

;; ============================================================================
;; JVM Spawning
;; ============================================================================

(defn spawn-jvm!
  "Spawn a new JVM, wait for nREPL, return JVM info map"
  [config]
  (let [id (str (random-uuid))
        dir (jvm-dir id)
        _ (fs/create-dirs dir)
        out-log (fs/file dir "out.log")
        err-log (fs/file dir "err.log")
        _ (safe-println "Spawning JVM" id "...")
        ;; Keep stdin open with a long-running process piped to the JVM
        ;; Unix: tail -f /dev/null | cmd
        ;; Windows: PowerShell infinite wait piped to cmd
        shell-cmd (if windows?
                    (str "powershell -Command \"while($true){Start-Sleep -Seconds 3600}\" | " (:cmd config))
                    (str "tail -f /dev/null | " (:cmd config)))
        process (proc/process {:dir (:project-dir config)
                               :out :write
                               :out-file out-log
                               :err :write
                               :err-file err-log}
                              (if windows? "cmd" "sh")
                              (if windows? "/c" "-c")
                              shell-cmd)
        pid (.pid (:proc process))]
    ;; Save PID immediately
    (spit (str (fs/path dir "pid")) (str pid))
    ;; Wait for nREPL
    (if-let [port (wait-for-nrepl id (:nrepl-timeout-ms config))]
      (do
        (spit (str (fs/path dir "port")) (str port))
        (safe-println "JVM" id "ready on port" port)
        {:id id :pid pid :port port :started (System/currentTimeMillis)})
      (do
        (safe-println "Failed to start JVM" id)
        (proc/destroy-tree process)
        (fs/delete-tree dir)
        nil))))

(defn kill-process-tree!
  "Kill a process and all its descendants (cross-platform)"
  [pid]
  (try
    (let [handle-opt (ProcessHandle/of pid)]
      (when (.isPresent handle-opt)
        (let [handle (.get handle-opt)]
          ;; Kill descendants first (children, grandchildren, etc.)
          (doseq [child (iterator-seq (.iterator (.descendants handle)))]
            (.destroyForcibly child))
          ;; Kill the main process
          (.destroyForcibly handle))))
    (catch Exception e
      (println "Warning: could not kill pid" pid "-" (.getMessage e)))))

(defn kill-jvm!
  "Kill a JVM by ID, cleanup its directory"
  [id]
  (let [dir (jvm-dir id)
        pid-file (fs/path dir "pid")]
    (when (fs/exists? pid-file)
      (let [pid (parse-long (str/trim (slurp (str pid-file))))]
        (println (str "Killing JVM " id " (pid " pid ")"))
        (kill-process-tree! pid)))
    ;; Cleanup directory (ignore if already deleted by concurrent process)
    (try
      (when (fs/exists? dir)
        (fs/delete-tree dir))
      (catch Exception _))))

(defn validate-jvm
  "Check if a JVM record is still valid (process alive)"
  [jvm]
  (when (and jvm (process-alive? (:pid jvm)))
    jvm))

(defn cleanup-dead-jvms!
  "Remove dead JVMs from state"
  []
  (update-state!
   (fn [state]
     (let [valid-idle (filterv validate-jvm (:idle state))
           valid-active (validate-jvm (:active state))
           ;; Cleanup directories for dead JVMs
           dead-idle (remove validate-jvm (:idle state))]
       (doseq [jvm dead-idle]
         (when (:id jvm)
           (let [dir (jvm-dir (:id jvm))]
             (when (fs/exists? dir)
               (fs/delete-tree dir)))))
       (assoc state
              :idle valid-idle
              :active valid-active)))))

;; ============================================================================
;; nREPL Client (with bencode parsing)
;; ============================================================================

(defn- read-byte [^java.io.InputStream in]
  (let [b (.read in)]
    (when (neg? b) (throw (ex-info "Unexpected EOF" {})))
    b))

(defn- read-bytes [^java.io.InputStream in n]
  (let [buf (byte-array n)
        read (.read in buf 0 n)]
    (when (not= read n) (throw (ex-info "Short read" {:expected n :got read})))
    (String. buf "UTF-8")))

(defn- read-until [^java.io.InputStream in terminator]
  (loop [acc []]
    (let [b (read-byte in)]
      (if (= b (int terminator))
        (apply str (map char acc))
        (recur (conj acc b))))))

(defn- parse-bencode
  "Parse bencode from input stream. Returns [value remaining-stream]"
  [^java.io.InputStream in]
  (let [b (read-byte in)
        c (char b)]
    (cond
      ;; Dictionary: d...e
      (= c \d)
      (loop [m {}]
        (let [peek (.read in)]
          (if (= peek (int \e))
            m
            (let [_ (.unread (java.io.PushbackInputStream. in) peek)
                  ;; Bencode dict keys are always strings
                  key-len (read-until in \:)
                  key (read-bytes in (parse-long key-len))
                  val (parse-bencode in)]
              (recur (assoc m (keyword key) val))))))

      ;; List: l...e
      (= c \l)
      (loop [l []]
        (let [peek (.read in)]
          (if (= peek (int \e))
            l
            (do
              ;; Can't unread easily, so we handle inline
              (let [val (if (= peek (int \e))
                          nil
                          (do
                            ;; Put byte back conceptually by parsing from it
                            (parse-bencode (java.io.SequenceInputStream.
                                            (java.io.ByteArrayInputStream. (byte-array [peek]))
                                            in))))]
                (recur (conj l val)))))))

      ;; Integer: i...e
      (= c \i)
      (parse-long (read-until in \e))

      ;; String: N:...
      (Character/isDigit c)
      (let [len-str (str c (read-until in \:))
            len (parse-long len-str)]
        (read-bytes in len))

      :else
      (throw (ex-info "Unknown bencode type" {:char c})))))

(defn- read-all-responses
  "Read bencode responses until 'done' or 'error' status (blocking read, no polling)"
  [^java.io.InputStream in]
  (loop [responses []]
    (let [resp (parse-bencode in)
          status-set (set (:status resp))]
      (if (or (status-set "done") (status-set "error"))
        (conj responses resp)
        (recur (conj responses resp))))))

(defn send-nrepl-command!
  "Send a command to JVM via nREPL (blocking, with long timeout).
   Returns {:success bool :value ... :error ...}
   Use inside a future for async operation."
  [port code]
  (try
    (let [msg (str "d4:code" (count code) ":" code "2:op4:evale")
          socket (java.net.Socket. "localhost" port)
          _ (.setSoTimeout socket 30000)  ;; 30s - we control wait time externally via deref timeout
          out (.getOutputStream socket)
          in (.getInputStream socket)]
      (.write out (.getBytes msg))
      (.flush out)
      ;; Read response - may take a while for blocking commands
      (try
        (let [responses (read-all-responses in)
              combined (apply merge responses)
              has-error? (some #{"error"} (:status combined))
              stdout (:out combined)
              stderr (:err combined)
              value (:value combined)
              ex (:ex combined)]
          (.close socket)
          ;; Return result (printing happens in caller)
          {:success (not has-error?)
           :value value
           :out stdout
           :err stderr
           :ex ex})
        (catch java.net.SocketTimeoutException _
          ;; Socket timeout - command is blocking (like open)
          (.close socket)
          {:success true :value nil :blocking true})))
    (catch Exception e
      {:success false :error (.getMessage e)})))

;; ============================================================================
;; Pool Operations
;; ============================================================================

(defn ensure-pool-size!
  "Adjust idle pool to target size - spawn if needed, kill excess if too many.
   Target size = idle JVMs to maintain (active JVMs don't count).
   Uses fine-grained locking: only holds lock during state reads/writes,
   not during slow JVM spawn operations."
  [target-idle]
  ;; Phase 1: Read state under lock to determine what's needed
  (let [{:keys [needed config excess-jvms keep-jvms]}
        (with-state-lock
          (cleanup-dead-jvms!)
          (let [state (load-state)
                config (:config state)
                current-idle (count (:idle state))
                needed (- target-idle current-idle)]
            (cond
              (pos? needed)
              {:needed needed :config config}

              (neg? needed)
              (let [excess (- needed)
                    idle-jvms (:idle state)]
                {:needed 0
                 :excess-jvms (take excess idle-jvms)
                 :keep-jvms (vec (drop excess idle-jvms))})

              :else {:needed 0})))]
    ;; Phase 2: Spawn/kill JVMs OUTSIDE the lock (slow operations)
    (cond
      (pos? needed)
      (do
        (println "Spawning" needed "JVM(s) in parallel...")
        (let [futures (doall (for [_ (range needed)]
                               (future (spawn-jvm! config))))
              jvms (->> futures (map deref) (filter some?) vec)]
          ;; Phase 3: Update state under lock (brief)
          (when (seq jvms)
            (with-state-lock
              (update-state! update :idle into jvms))
            (println "Pool ready:" (count jvms) "JVM(s) added"))))

      (seq excess-jvms)
      (do
        (println "Killing" (count excess-jvms) "excess JVM(s)...")
        (doseq [jvm excess-jvms]
          (kill-jvm! (:id jvm)))
        ;; Update state under lock (brief)
        (with-state-lock
          (update-state! assoc :idle keep-jvms))
        (println "Pool trimmed.")))))

(defn acquire-jvm!
  "Take an idle JVM and mark it as active. Returns nil if none available or already active."
  []
  (cleanup-dead-jvms!)
  (let [state (load-state)]
    (cond
      ;; Already have an active JVM
      (:active state)
      nil

      ;; Take first idle JVM
      (first (:idle state))
      (let [jvm (first (:idle state))]
        (update-state! (fn [s]
                         (-> s
                             (assoc :active jvm)
                             (update :idle #(vec (rest %))))))
        jvm)

      ;; No idle JVMs
      :else nil)))

(defn release-jvm!
  "Kill active JVM"
  []
  (let [state (load-state)]
    (when-let [jvm (:active state)]
      (kill-jvm! (:id jvm))
      (update-state! assoc :active nil))))

;; ============================================================================
;; Commands
;; ============================================================================

(defn cmd-start [{:keys [spare cmd]}]
  ;; Phase 1: Setup config under lock (brief)
  (let [initial-count
        (with-state-lock
          (ensure-dirs!)
          (let [raw-spare (or spare 2)
                pool-size (if (< raw-spare 1)
                            (do
                              (println "Warning: --spare must be at least 1. Using 1.")
                              1)
                            raw-spare)
                jvm-cmd (or cmd (:cmd default-config))
                initial-count (inc pool-size)]  ;; spare + 1 for open
            (println "Starting JVM pool with" initial-count "idle JVM(s)")
            (println "Command:" jvm-cmd)
            (println "Platform:" (if windows? "Windows" "Unix"))
            (update-state! assoc-in [:config :pool-size] pool-size)
            (update-state! assoc-in [:config :cmd] jvm-cmd)
            (update-state! assoc-in [:config :project-dir] (str (fs/cwd)))
            initial-count))]
    ;; Phase 2: Spawn JVMs (ensure-pool-size! manages its own locking)
    (ensure-pool-size! initial-count)
    (println "\nPool ready. Use 'bb pool.clj open' to start your app.")))

(defn cmd-stop []
  (with-state-lock
    (println "Stopping pool...")
    (let [state (load-state)]
      ;; Kill active
      (when-let [jvm (:active state)]
        (kill-jvm! (:id jvm)))
      ;; Kill all idle
      (doseq [jvm (:idle state)]
        (kill-jvm! (:id jvm)))
      ;; Remove active port file
      (when (fs/exists? active-port-file)
        (fs/delete active-port-file))
      ;; Reset state
      (save-state! {:config (:config state) :idle [] :active nil})
      (println "Pool stopped."))))

(defn- print-nrepl-result
  "Print nREPL command result (stdout, stderr, errors)"
  [result]
  (when-let [out (:out result)]
    (when-not (str/blank? out)
      (println "Output:" (str/trim out))))
  (when-let [err (:err result)]
    (when-not (str/blank? err)
      (println "Error output:" (str/trim err))))
  (when-let [ex (:ex result)]
    (println "Exception:" ex)))

(defn cmd-open []
  ;; Critical section: kill active + acquire must be atomic
  (let [{:keys [jvm pool-size]}
        (with-state-lock
          (let [state (load-state)
                pool-size (get-in state [:config :pool-size] 2)]
            ;; Kill active JVM if exists (idempotent)
            (when (:active state)
              (let [active-id (get-in state [:active :id])]
                (kill-jvm! active-id)
                (update-state! assoc :active nil)))
            ;; Acquire from pool
            (cleanup-dead-jvms!)
            (let [jvm (acquire-jvm!)]
              {:jvm jvm :pool-size pool-size})))]
    ;; Outside lock: send command + replenish (can run concurrently with other processes)
    (if jvm
      (do
        (println "Acquired JVM" (:id jvm) "on port" (:port jvm))
        (println "Sending (open) + replenishing in parallel...")
        (let [start-time (System/currentTimeMillis)
              cmd-future (future (send-nrepl-command! (:port jvm) "(open)"))
              ;; ensure-pool-size! uses fine-grained locking internally,
              ;; so this won't block other open commands (lock only held briefly)
              replenish-future (future (ensure-pool-size! pool-size))]
          ;; Must wait for replenish, otherwise process exits and future is killed
          @replenish-future
          (let [elapsed (- (System/currentTimeMillis) start-time)
                remaining (max 0 (- 100 elapsed))
                result (deref cmd-future remaining {:success true :blocking true})]
            (print-nrepl-result result)
            (if (:success result)
              (do
                ;; Write active port for watchexec/scripts to read
                (spit (str active-port-file) (str (:port jvm)))
                (println "\nApp started successfully!")
                (println "Connect REPL: clj -M:connect --port" (:port jvm)))
              ;; Check if JVM was killed by another open command
              (if (not (process-alive? (:pid jvm)))
                (println "\nJVM was killed (likely by another 'open' command running in parallel).")
                (println "\nFailed to start app:" (:error result)))))))
      ;; No idle JVMs - user hasn't run start
      (println "No idle JVMs available. Run 'bb scripts/pool.clj start' first."))))

(defn cmd-close []
  ;; Phase 1: Release JVM under lock (brief)
  (let [{:keys [was-active pool-size]}
        (with-state-lock
          (let [state (load-state)
                pool-size (get-in state [:config :pool-size] 2)]
            (if (:active state)
              (do
                (release-jvm!)
                ;; Remove active port file
                (when (fs/exists? active-port-file)
                  (fs/delete active-port-file))
                (println "Closed. Replenishing pool...")
                {:was-active true :pool-size pool-size})
              (do
                (println "No active JVM to close.")
                {:was-active false}))))]
    ;; Phase 2: Replenish outside lock (ensure-pool-size! manages its own locking)
    (when was-active
      (ensure-pool-size! (inc pool-size))  ;; spare + 1 for next open
      (println "Pool ready."))))

(defn cmd-status []
  (ensure-dirs!)
  (cleanup-dead-jvms!)
  (let [state (load-state)
        idle (:idle state)
        active (:active state)
        total (+ (count idle) (if active 1 0))]
    (println "JVM Pool Status")
    (println "===============")
    (println "Project:" (get-in state [:config :project-dir] "not set"))
    (println "Idle JVMs:" (get-in state [:config :pool-size] "not set"))
    (println "Total JVMs:" total (str "(" (count idle) " idle, " (if active 1 0) " active)"))
    (println)
    (when active
      (println "ACTIVE:")
      (println "  " (:id active) "port:" (:port active) "pid:" (:pid active)))
    (when (seq idle)
      (println "IDLE:")
      (doseq [jvm idle]
        (println "  " (:id jvm) "port:" (:port jvm) "pid:" (:pid jvm))))
    (when (zero? total)
      (println "No JVMs running. Use 'bb pool.clj start' to create pool."))))

(defn cmd-connect []
  (let [state (load-state)
        active (:active state)]
    (if active
      (do
        (println "Connect to active JVM:")
        (println "  clj -M:connect --port" (:port active))
        (println "\nOr with your editor's nREPL client on port" (:port active))
        (println "\nFor watchexec auto-reload:")
        (if windows?
          (println "  watchexec -qnrc -e clj -w src -w dev -- scripts\\reload.cmd")
          (println "  watchexec -qnrc -e clj -w src -w dev -- ./scripts/reload.sh")))
      (println "No active JVM. Use 'bb pool.clj open' first."))))

(defn cmd-help []
  (println "JVM Pool Manager - Disposable Process Sandbox Pool")
  (println)
  (println "Usage: bb pool.clj <command> [options]")
  (println)
  (println "Commands:")
  (println "  start    Start pool, warm up JVMs")
  (println "  stop     Kill all JVMs, cleanup")
  (println "  open     Open app (restarts if already running)")
  (println "  close    Close app")
  (println "  status   Show pool state")
  (println "  connect  Print connection info for active JVM")
  (println "  help     Show this help")
  (println)
  (println "Options for 'start':")
  (println "  --spare N  Idle JVMs to keep ready (default: 2, minimum: 1)")
  (println "  --cmd CMD  Command to start JVM (default: auto-detected)")
  (println)
  (println "Detected platform:" (if windows? "Windows" "Unix"))
  (println "Default command:" (:cmd default-config)))

;; ============================================================================
;; CLI Entry Point
;; ============================================================================

(defn parse-args [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (let [[arg & rest] args]
        (cond
          (= "--spare" arg)
          (recur (next rest) (assoc opts :spare (parse-long (first rest))))

          (= "--cmd" arg)
          (recur (next rest) (assoc opts :cmd (first rest)))

          :else
          (recur rest (assoc opts :command arg)))))))

(defn -main [& args]
  (let [opts (parse-args args)]
    (case (:command opts)
      "start" (cmd-start opts)
      "stop" (cmd-stop)
      "open" (cmd-open)
      "close" (cmd-close)
      "status" (cmd-status)
      "connect" (cmd-connect)
      "help" (cmd-help)
      nil (cmd-help)
      (do
        (println "Unknown command:" (:command opts))
        (cmd-help)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
