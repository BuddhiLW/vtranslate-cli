(ns vtranslate.cli.engine
  "Driving adapter to the engine subprocess: build a job spec, shell the engine
   (`clojure -M:run` in the sibling repo), feed the spec as EDN on stdin, and
   parse the single EDN Result it prints. The engine owns all domain logic; this
   ns only marshals in and out."
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [hive-dsl.result :as r]))

(defn engine-dir
  "Directory of the engine project. Override with VTRANSLATE_ENGINE_DIR; defaults
   to the sibling ../vtranslate-engine relative to the CLI's working directory."
  []
  (or (not-empty (System/getenv "VTRANSLATE_ENGINE_DIR")) "../vtranslate-engine"))

(defn- parse-result
  "The engine prints exactly one EDN Result via prn. Parse the last non-blank
   stdout line; on anything unexpected, wrap a structured err."
  [out exit]
  (let [line (->> (str/split-lines (str out)) (remove str/blank?) last)]
    (or (try (edn/read-string line) (catch Exception _ nil))
        (r/err :error/engine-unparsable {:exit exit :out (str out)}))))

(defn run-job
  "Shell `clojure -M:run` in the engine dir, feed `spec` as EDN on stdin, parse
   the printed Result. => (r/ok ...) | (r/err ...)."
  [spec]
  (let [{:keys [out exit]}
        (p/shell {:dir (engine-dir) :in (pr-str spec)
                  :out :string :err :string :continue true}
                 "clojure" "-M:run")]
    (parse-result out exit)))
