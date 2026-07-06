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
     :usage   "vtranslate translate <source> <target-lang> [--source-lang auto] [--format srt|vtt]"
     :args    [{:name :source      :desc "path to the media file"}
               {:name :target-lang :desc "BCP-47 target language, e.g. pt-BR"}]
     :opts    {:source-lang {:default "auto" :desc "source language (BCP-47 or auto)"}
               :format      {:default "srt" :enum #{"srt" "vtt"} :desc "subtitle format"}}
     :run     do-translate}
    {:name "version" :summary "print the CLI version"
     :run (fn [_] (println (read-version)) 0)}
    {:name "help" :summary "show top-level help"
     :run (fn [_] (println (cmd/help-text root-command ["vtranslate"])) 0)}]})

;; --- entrypoint -------------------------------------------------------------
;; Two levels kept apart (Stratified / CPPB boundary): `interpret` is a PURE
;; argv -> action decision (no IO); the `perform-*` fns are the ONLY effects.
;; `-main` reads top-down: resolve intent, then carry it out.

(defn- help-requested? [tokens]
  (boolean (some #{"help" "--help" "-h"} tokens)))

(defn- interpret
  "Pure: resolve argv against the command tree and name the action to take.
   => {:action :help|:run|:usage-error, :cmd node, :path [names], ...}."
  [root argv]
  (let [{:keys [cmd path rest]} (cmd/resolve-command root (vec argv))]
    (cond
      (help-requested? rest) {:action :help, :cmd cmd, :path path}
      (nil? (:run cmd))      {:action :help, :cmd cmd, :path path} ; bare branch node
      :else
      (let [parsed (cmd/parse cmd rest)]
        (if (cmd/ok? parsed)
          {:action :run,         :cmd cmd, :input (:ok parsed)}
          {:action :usage-error, :cmd cmd, :path path, :message (:error parsed)})))))

(defn- perform-help [{:keys [cmd path]}]
  (println (cmd/help-text cmd path))
  0)

(defn- perform-run [{:keys [cmd input]}]
  ((:run cmd) input))

(defn- perform-usage-error [{:keys [cmd path message]}]
  (binding [*out* *err*]
    (println "error:" message)
    (newline))
  (println (cmd/help-text cmd path))
  2)

(def ^:private actions
  "Action tag -> effect handler. Widen the CLI's control surface by adding a key
   here plus an :action in `interpret` (OCP) — never by growing a cond."
  {:help        perform-help
   :run         perform-run
   :usage-error perform-usage-error})

(defn -main [& argv]
  (let [action  (interpret root-command argv)
        perform (actions (:action action))]
    (System/exit (perform action))))

;; Allow direct `bb src/vtranslate/cli.clj ...` runs (no-op under `-m`).
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
