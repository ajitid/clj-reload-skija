#!/usr/bin/env bb
;; Build Skija from sibling directory and copy JARs to .jars/
;;
;; Usage:
;;   export JAVA_HOME=$(/usr/libexec/java_home)  # macOS
;;   bb scripts/build-skija.clj
;;
;; Prerequisites:
;;   - JAVA_HOME must be set before running this script
;;   - Python 3 must be installed
;;   - Skija source must exist at ../Skija (sibling directory)
;;
;; See README.md for full setup instructions.

(ns build-skija
  (:require [babashka.process :refer [shell]]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def project-root (fs/parent (fs/parent *file*)))
(def skija-dir (fs/path (fs/parent project-root) "Skija"))
(def jars-dir (fs/path project-root ".jars"))

(defn print-step [msg]
  (println (str "\n\033[1;34m==>\033[0m \033[1m" msg "\033[0m")))

(defn print-success [msg]
  (println (str "\033[1;32m✓\033[0m " msg)))

(defn print-error [msg]
  (println (str "\033[1;31m✗ Error:\033[0m " msg)))

(defn print-info [msg]
  (println (str "    " msg)))

(defn command-exists? [cmd]
  (try
    (-> (shell {:out :string :err :string :continue true} "which" cmd)
        :exit
        zero?)
    (catch Exception _ false)))

(defn detect-platform []
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (System/getProperty "os.arch")]
    (cond
      (and (str/includes? os "mac") (= arch "aarch64")) "macos-arm64"
      (and (str/includes? os "mac") (str/includes? arch "x86")) "macos-x64"
      (str/includes? os "windows") "windows-x64"
      (str/includes? os "linux") "linux-x64"
      :else (do (print-error (str "Unknown platform: " os " / " arch))
                (System/exit 1)))))

(defn check-prerequisites []
  (print-step "Checking prerequisites")

  ;; Check JAVA_HOME first - must be set in environment
  (let [java-home (System/getenv "JAVA_HOME")]
    (when (or (nil? java-home) (str/blank? java-home))
      (print-error "JAVA_HOME environment variable is not set")
      (print-info "")
      (print-info "Please set JAVA_HOME before running this script:")
      (print-info "")
      (print-info "  # macOS")
      (print-info "  export JAVA_HOME=$(/usr/libexec/java_home)")
      (print-info "")
      (print-info "  # Linux")
      (print-info "  export JAVA_HOME=/usr/lib/jvm/java-11-openjdk")
      (print-info "")
      (print-info "  # Windows (PowerShell)")
      (print-info "  $env:JAVA_HOME = \"C:\\Program Files\\Eclipse Adoptium\\jdk-21\"")
      (print-info "")
      (print-info "Then run this script again.")
      (System/exit 1))
    (when-not (fs/exists? java-home)
      (print-error (str "JAVA_HOME directory does not exist: " java-home))
      (System/exit 1))
    (print-success (str "JAVA_HOME: " java-home)))

  ;; Check Skija directory
  (when-not (fs/exists? skija-dir)
    (print-error (str "Skija directory not found: " skija-dir))
    (print-info "")
    (print-info "Please clone Skija as a sibling directory:")
    (print-info "  cd ..")
    (print-info "  git clone https://github.com/HumbleUI/Skija.git")
    (System/exit 1))
  (print-success (str "Skija directory: " skija-dir))

  ;; Check Python 3
  (when-not (command-exists? "python3")
    (print-error "Python 3 not found")
    (print-info "")
    (print-info "Please install Python 3:")
    (print-info "  brew install python3  # macOS")
    (print-info "  apt install python3   # Ubuntu/Debian")
    (System/exit 1))
  (print-success "Python 3 found")

  ;; Check for Skia binaries
  (let [platform (detect-platform)
        os-part (cond
                  (str/starts-with? platform "macos") "macos"
                  (str/starts-with? platform "windows") "windows"
                  (str/starts-with? platform "linux") "linux"
                  :else platform)
        platform-dir (fs/path skija-dir "platform")]
    (when-not (some #(and (fs/directory? %)
                          (str/starts-with? (fs/file-name %) "Skia-")
                          (str/includes? (fs/file-name %) os-part))
                    (fs/list-dir platform-dir))
      (print-error (str "Skia binaries not found for " platform))
      (print-info "")
      (print-info "Please download Skia binaries:")
      (print-info (str "  cd " skija-dir "/platform"))
      (print-info "  python3 script/checkout.py")
      (print-info "")
      (print-info "Or download manually from:")
      (print-info "  https://github.com/HumbleUI/Skija/releases")
      (System/exit 1))
    (print-success (str "Skia binaries found for " platform))))

(defn clean-build-dir []
  (let [platform (detect-platform)
        native-dir (fs/path skija-dir "platform" "target" platform "native")]
    (when (fs/exists? native-dir)
      (fs/delete-tree native-dir)
      (print-info "Cleaned stale build directory"))))

(defn build-skija []
  (print-step "Building Skija native library")
  (clean-build-dir)
  (let [result (shell {:dir (str skija-dir) :continue true}
                      "python3" "script/build.py")]
    (when-not (zero? (:exit result))
      (print-error "Skija build failed")
      (print-info "Check the output above for details")
      (System/exit 1)))
  (print-success "Native build complete"))

(defn package-skija []
  (print-step "Packaging Skija JARs")
  (let [result1 (shell {:dir (str skija-dir) :continue true}
                       "python3" "script/package_shared.py")]
    (when-not (zero? (:exit result1))
      (print-error "Failed to package skija-shared")
      (System/exit 1)))

  (let [result2 (shell {:dir (str skija-dir) :continue true}
                       "python3" "script/package_platform.py")]
    (when-not (zero? (:exit result2))
      (print-error "Failed to package platform JAR")
      (System/exit 1)))
  (print-success "Packaging complete"))

(defn copy-jars []
  (print-step "Copying JARs to .jars/")

  (fs/create-dirs jars-dir)

  (let [platform (detect-platform)
        skija-target (fs/path skija-dir "target")
        shared-jar (fs/path skija-target "skija-shared-0.0.0-SNAPSHOT.jar")
        platform-jar (fs/path skija-target (str "skija-" platform "-0.0.0-SNAPSHOT.jar"))]

    (when-not (fs/exists? shared-jar)
      (print-error (str "Shared JAR not found: " shared-jar))
      (System/exit 1))
    (when-not (fs/exists? platform-jar)
      (print-error (str "Platform JAR not found: " platform-jar))
      (System/exit 1))

    (fs/copy shared-jar (fs/path jars-dir "skija-shared.jar") {:replace-existing true})
    (print-success "Copied skija-shared.jar")

    (fs/copy platform-jar (fs/path jars-dir (str "skija-" platform ".jar")) {:replace-existing true})
    (print-success (str "Copied skija-" platform ".jar"))))

(defn print-summary []
  (let [platform (detect-platform)]
    (print-step "Build complete!")
    (println)
    (println "  JARs copied to .jars/:")
    (println "    - skija-shared.jar")
    (println (str "    - skija-" platform ".jar"))
    (println)
    (println "  You can now run:")
    (println (str "    clj -A:dev:" platform))))

(defn -main [& _args]
  (println)
  (println "╔═══════════════════════════════════════╗")
  (println "║       Skija Build Script              ║")
  (println "╚═══════════════════════════════════════╝")

  (check-prerequisites)
  (build-skija)
  (package-skija)
  (copy-jars)
  (print-summary))

(-main)
