(ns vtranslate.cli.engine
  "Driving adapter to the engine subprocess: build a job spec, shell the engine
   (`clojure -M:run` in the sibling repo), feed the spec as EDN on stdin, and
   parse the single EDN Result it prints. The engine owns all domain logic; this
   ns only marshals in and out."
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.cli.engine-classpath :as classpath]))

(defn engine-dir
  "Directory of the engine project. Override with VTRANSLATE_ENGINE_DIR; defaults
   to the sibling ../vtranslate-engine relative to the CLI's working directory."
  []
  (or (not-empty (System/getenv "VTRANSLATE_ENGINE_DIR")) "../vtranslate-engine"))

(defn- parse-result
  "Parse the engine subprocess output into a Result. The engine prints exactly
   one EDN Result via prn on stdout and exits 0/1; tagged records are read as
   their map payload. A parsed (r/err ...) passes through; a parsed (r/ok ...)
   under a zero exit passes through; anything else (unparsable stdout, or a value
   contradicting a non-zero exit) becomes an (r/err ...) carrying the subprocess
   :exit / :out / :err. => (r/ok ...) | (r/err ...)."
  [out err exit]
  (let [line   (->> (str/split-lines (str out)) (remove str/blank?) last)
        parsed (try (edn/read-string {:default (fn [_tag value] value)} line)
                    (catch Exception _ nil))]
    (cond
      (and (map? parsed) (r/err? parsed))             parsed
      (and (zero? exit) (map? parsed) (r/ok? parsed)) parsed
      (nil? parsed) (r/err :error/engine-unparsable
                           {:exit exit :out (str out) :err (str err)})
      :else         (r/err :error/engine-failed
                           {:exit exit :out (str out) :err (str err)}))))

(defn run-job
  "Shell the engine in a JVM subprocess, feed `spec` as EDN on stdin, parse the
   printed Result. The subprocess stderr is threaded into any failure Result.
   => (r/ok ...) | (r/err ...)."
  [spec]
  (let [{:keys [out err exit]}
        (apply p/shell
               {:dir (engine-dir) :in (pr-str spec)
                :out :string :err :string :continue true}
               (classpath/engine-command spec))]
    (parse-result out err exit)))