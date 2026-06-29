(ns vtranslate.cli
  "Babashka driving adapter over vtranslate-engine. Marshals argv (Bonzai-style
   positional commands) into an EDN job spec, shells out to the engine JVM
   subprocess, and prints the engine's EDN Result verbatim.

   Process-boundary transport: the engine needs the JVM + native ffmpeg and
   cannot load into babashka/SCI, so this CLI never depends on it as a classpath
   library — it INVOKES it (`clojure -M:ffmpeg:run` in the engine checkout).

   The engine owns ALL domain types; this CLI owns none."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def ^:private repo-root
  (-> *file* fs/absolutize fs/parent fs/parent fs/parent str))

(defn- read-version []
  (try (str/trim (slurp (str (fs/path repo-root "VERSION"))))
       (catch Exception _ "dev")))

(def ^:private engine-dir
  "Where the engine subprocess runs. Override with VTRANSLATE_ENGINE_DIR;
   defaults to the sibling checkout under the vtranslate workspace."
  (or (System/getenv "VTRANSLATE_ENGINE_DIR")
      (str (fs/path (fs/parent repo-root) "vtranslate-engine"))))

(defn- usage []
  (println
   (str/join \newline
     ["vtranslate — subtitle translation CLI (driver over vtranslate-engine)"
      ""
      "USAGE:"
      "  vtranslate translate <source> <target-lang> [opts]   translate a media file"
      "  vtranslate version                                   print version"
      "  vtranslate help                                      this help"
      ""
      "TRANSLATE OPTS:"
      "  --source-lang <code>   source language (default: en)"
      "  --format <srt|vtt>     subtitle format (default: srt)"
      "  --job-id <id>          job id (default: derived from source filename)"
      ""
      "ENV:"
      "  VTRANSLATE_ENGINE_DIR  engine checkout dir (default: ../vtranslate-engine)"])))

(defn- parse-args
  "Split argv into positional args + --opt value pairs."
  [args]
  (loop [args args pos [] opts {}]
    (if-let [a (first args)]
      (if (str/starts-with? a "--")
        (recur (drop 2 args) pos (assoc opts (keyword (subs a 2)) (second args)))
        (recur (rest args) (conj pos a) opts))
      {:pos pos :opts opts})))

(defn- run-engine
  "Shell the engine: `clojure -M:ffmpeg:run '<edn-spec>'` in engine-dir. The
   engine prints one EDN Result to stdout and exits 0/1 — re-emit both streams."
  [spec]
  (let [{:keys [out err exit]}
        (p/sh {:dir engine-dir} "clojure" "-M:ffmpeg:run" (pr-str spec))]
    (when (seq err) (binding [*out* *err*] (print err) (flush)))
    (when (seq out) (print out) (flush))
    exit))

(defn- cmd-translate [pos opts]
  (let [[source target-lang] pos]
    (cond
      (not (and source target-lang))
      (do (binding [*out* *err*]
            (println "error: translate needs <source> <target-lang>"))
          (usage) 2)

      (not (fs/exists? source))
      (do (binding [*out* *err*] (println "error: source not found:" source)) 2)

      :else
      (run-engine
       {:job-id          (or (:job-id opts) (str "cli-" (fs/file-name source)))
        :source          (str (fs/absolutize source))
        :source-language (or (:source-lang opts) "en")
        :target-language target-lang
        :asset-kind      :media/video
        :format          (keyword "format" (or (:format opts) "srt"))}))))

(defn -main [& argv]
  (let [{:keys [pos opts]} (parse-args argv)
        [cmd & more] pos]
    (System/exit
     (case cmd
       "translate"  (cmd-translate (vec more) opts)
       "version"    (do (println (read-version)) 0)
       ("help" nil) (do (usage) 0)
       (do (binding [*out* *err*] (println "unknown command:" (pr-str cmd)))
           (usage) 1)))))

;; Allow direct `bb src/vtranslate/cli.clj ...` runs (no-op under `-m`).
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
