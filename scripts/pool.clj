#!/usr/bin/env bb
;; JVM Pool Manager - Disposable Process Sandbox Pool
;; Usage: bb pool.clj <command>
;;
;; Commands:
;;   start [--size N]  Start pool, warm up N JVMs (default: 3)
;;   stop              Kill all JVMs, cleanup
;;   open              Acquire idle JVM, run (open)
;;   close             Kill active JVM, replenish pool
;;   restart           Close + open (instant!)
;;   status            Show pool state
;;   connect           Print connection info for active JVM

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
  {:pool-size 3
   :cmd (str "clj -A:" (detect-platform-alias))
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
    ;; Cleanup directory
    (when (fs/exists? dir)
      (fs/delete-tree dir))))

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
  "Spawn JVMs until pool reaches target size (in parallel)"
  [target-size]
  (cleanup-dead-jvms!)
  (let [state (load-state)
        config (:config state)
        current-idle (count (:idle state))
        needed (- target-size current-idle)]
    (when (pos? needed)
      (println "Spawning" needed "JVM(s) in parallel...")
      ;; Spawn all JVMs in parallel using futures
      (let [futures (doall (for [_ (range needed)]
                             (future (spawn-jvm! config))))
            ;; Wait for all to complete, collect successful ones
            jvms (->> futures
                      (map deref)
                      (filter some?))]
        ;; Add all to state at once
        (when (seq jvms)
          (update-state! update :idle into jvms)
          (println "Pool ready:" (count jvms) "JVM(s) added"))))))

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

(defn cmd-start [{:keys [size cmd]}]
  (ensure-dirs!)
  (let [pool-size (or size 3)
        jvm-cmd (or cmd (:cmd default-config))]
    (println "Starting JVM pool with size" pool-size)
    (println "Command:" jvm-cmd)
    (println "Platform:" (if windows? "Windows" "Unix"))
    (update-state! assoc-in [:config :pool-size] pool-size)
    (update-state! assoc-in [:config :cmd] jvm-cmd)
    (update-state! assoc-in [:config :project-dir] (str (fs/cwd)))
    (ensure-pool-size! pool-size)
    (println "\nPool ready. Use 'bb pool.clj open' to start your app.")))

(defn cmd-stop []
  (println "Stopping pool...")
  (let [state (load-state)]
    ;; Kill active
    (when-let [jvm (:active state)]
      (kill-jvm! (:id jvm)))
    ;; Kill all idle
    (doseq [jvm (:idle state)]
      (kill-jvm! (:id jvm)))
    ;; Reset state
    (save-state! {:config (:config state) :idle [] :active nil})
    (println "Pool stopped.")))

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
  (ensure-dirs!)
  (if-let [jvm (acquire-jvm!)]
    (let [pool-size (get-in (load-state) [:config :pool-size] 3)]
      (println "Acquired JVM" (:id jvm) "on port" (:port jvm))
      (println "Sending (open) + replenishing in parallel...")
      ;; Run nREPL command AND replenishment in parallel
      (let [cmd-future (future (send-nrepl-command! (:port jvm) "(open)"))
            replenish-future (future (ensure-pool-size! pool-size))]
        ;; Wait for replenishment to complete
        @replenish-future
        ;; Check nREPL result (give extra 100ms after replenishment)
        (let [result (deref cmd-future 100 {:success true :blocking true})]
          (print-nrepl-result result)
          (if (:success result)
            (do
              (println "\nApp started successfully!")
              (println "Connect REPL: clj -M:nrepl -m nrepl.cmdline --connect --port" (:port jvm)))
            (println "\nFailed to start app:" (:error result))))))
    ;; Check why we couldn't acquire
    (let [state (load-state)]
      (cond
        (:active state)
        (println "Already have an active JVM. Use 'close' or 'restart' first.")

        (empty? (:idle state))
        (println "No idle JVMs available. Run 'bb scripts/pool.clj start' first.")

        :else
        (println "Could not acquire JVM.")))))

(defn cmd-close []
  (let [state (load-state)
        pool-size (get-in state [:config :pool-size] 3)]
    (if (:active state)
      (do
        (release-jvm!)
        (println "Closed. Replenishing pool...")
        (ensure-pool-size! pool-size)
        (println "Pool ready."))
      (println "No active JVM to close."))))

(defn cmd-restart []
  (let [state (load-state)
        pool-size (get-in state [:config :pool-size] 3)]
    ;; Kill active JVM
    (when (:active state)
      (let [active-id (get-in state [:active :id])]
        (kill-jvm! active-id)
        (update-state! assoc :active nil)))
    ;; Acquire new JVM from pool
    (ensure-dirs!)
    (if-let [jvm (acquire-jvm!)]
      (do
        (println "Acquired JVM" (:id jvm) "on port" (:port jvm))
        (println "Sending (open) + replenishing in parallel...")
        ;; Run nREPL command AND replenishment in parallel
        (let [cmd-future (future (send-nrepl-command! (:port jvm) "(open)"))
              replenish-future (future (ensure-pool-size! pool-size))]
          ;; Wait for replenishment to complete (this is the longer operation)
          @replenish-future
          ;; Check nREPL result (give extra 100ms after replenishment)
          (let [result (deref cmd-future 100 {:success true :blocking true})]
            (print-nrepl-result result)
            (if (:success result)
              (do
                (println "\nApp started successfully!")
                (println "Connect REPL: clj -M:nrepl -m nrepl.cmdline --connect --port" (:port jvm)))
              (println "\nFailed to start app:" (:error result))))))
      (println "No idle JVMs available. Run 'bb scripts/pool.clj start' first."))))

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
    (println "Target size:" (get-in state [:config :pool-size] "not set"))
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
        (println "  clj -M:nrepl -m nrepl.cmdline --connect --port" (:port active))
        (println "\nOr with your editor's nREPL client on port" (:port active)))
      (println "No active JVM. Use 'bb pool.clj open' first."))))

(defn cmd-help []
  (println "JVM Pool Manager - Disposable Process Sandbox Pool")
  (println)
  (println "Usage: bb pool.clj <command> [options]")
  (println)
  (println "Commands:")
  (println "  start    Start pool, warm up JVMs")
  (println "  stop     Kill all JVMs, cleanup")
  (println "  open     Acquire idle JVM, run (open)")
  (println "  close    Kill active JVM, replenish pool")
  (println "  restart  Close + open (instant!)")
  (println "  status   Show pool state")
  (println "  connect  Print connection info for active JVM")
  (println "  help     Show this help")
  (println)
  (println "Options for 'start':")
  (println "  --size N   Number of JVMs in pool (default: 3)")
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
          (= "--size" arg)
          (recur (next rest) (assoc opts :size (parse-long (first rest))))

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
      "restart" (cmd-restart)
      "status" (cmd-status)
      "connect" (cmd-connect)
      "help" (cmd-help)
      nil (cmd-help)
      (do
        (println "Unknown command:" (:command opts))
        (cmd-help)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
