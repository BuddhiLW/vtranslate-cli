(ns vtranslate.cli
  "Babashka driving adapter over vtranslate-engine (boundary). Resolves argv
   against a Go-Bonzai command tree (vtranslate.cli.command), binds + validates
   arguments, then runs the leaf: for `translate` it marshals an EDN job spec,
   shells out to the engine JVM subprocess, and prints the engine's EDN Result.

   Process-boundary transport: the engine needs the JVM + native ffmpeg and
   cannot load into babashka/SCI, so this CLI INVOKES it (`clojure -M:ffmpeg:run`)
   rather than depending on it as a classpath library. The engine owns ALL domain
   types; this CLI owns none."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [vtranslate.cli.command :as cmd]))

;; --- environment ------------------------------------------------------------

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

;; --- engine subprocess (the only effect) ------------------------------------

(defn- run-engine
  "Shell the engine: `clojure -M:ffmpeg:run '<edn-spec>'` in engine-dir. The
   engine prints one EDN Result to stdout and exits 0/1 — re-emit both streams,
   propagate the exit code."
  [spec]
  (let [{:keys [out err exit]}
        (p/sh {:dir engine-dir} "clojure" "-M:ffmpeg:run" (pr-str spec))]
    (when (seq err) (binding [*out* *err*] (print err) (flush)))
    (when (seq out) (print out) (flush))
    exit))

;; --- command behaviors ------------------------------------------------------

(defn- do-translate [{:keys [args opts]}]
  (let [{:keys [source target-lang]} args
        {:keys [source-lang format]} opts]
    (if-not (fs/exists? source)
      (do (binding [*out* *err*] (println "error: source not found:" source)) 2)
      (run-engine
       {:job-id          (str "cli-" (fs/file-name source))
        :source          (str (fs/absolutize source))
        :source-language source-lang
        :target-language target-lang
        :asset-kind      :media/video
        :format          (keyword "format" format)}))))

;; --- command tree -----------------------------------------------------------

(declare root-command)

(def root-command
  {:name "vtranslate"
   :summary "subtitle translation CLI (driver over vtranslate-engine)"
   :commands
   [{:name "translate" :aliases #{"tr"}
     :summary "translate a media file's audio into subtitles"
     :usage   "vtranslate translate <source> <target-lang> [--source-lang en] [--format srt|vtt]"
     :args    [{:name :source      :desc "path to the media file"}
               {:name :target-lang :desc "BCP-47 target language, e.g. pt-BR"}]
     :opts    {:source-lang {:default "en"  :desc "source language (BCP-47)"}
               :format      {:default "srt" :enum #{"srt" "vtt"} :desc "subtitle format"}}
     :run     do-translate}
    {:name "version" :summary "print the CLI version"
     :run (fn [_] (println (read-version)) 0)}
    {:name "help" :summary "show top-level help"
     :run (fn [_] (println (cmd/help-text root-command ["vtranslate"])) 0)}]})

;; --- entrypoint -------------------------------------------------------------

(defn -main [& argv]
  (let [{:keys [cmd path rest]} (cmd/resolve-command root-command (vec argv))
        help? (some #{"help" "--help" "-h"} rest)]
    (System/exit
     (cond
       help?             (do (println (cmd/help-text cmd path)) 0)
       (nil? (:run cmd)) (do (println (cmd/help-text cmd path)) 0)  ; bare branch node
       :else
       (let [parsed (cmd/parse cmd rest)]
         (if (cmd/ok? parsed)
           ((:run cmd) (:ok parsed))
           (do (binding [*out* *err*] (println "error:" (:error parsed)) (println))
               (println (cmd/help-text cmd path)) 2)))))))

;; Allow direct `bb src/vtranslate/cli.clj ...` runs (no-op under `-m`).
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
