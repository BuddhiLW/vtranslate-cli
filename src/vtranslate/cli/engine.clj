(ns vtranslate.cli.engine
  "Driving adapter to the engine subprocess: build a job spec, shell the engine,
   feed the spec as EDN on stdin, and parse the single EDN Result it prints.
   Invocation resolves by precedence: VTRANSLATE_ENGINE_DIR > sibling
   ../vtranslate-engine (`clojure -M:run` in that checkout) > pinned git
   coordinate via `clojure -Sdeps` (packaged distribution). Spawning uses raw
   babashka.process — hive-system.process is not bb-compatible. The engine owns
   all domain logic; this ns only marshals in and out."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.cli.adapters.addon-classpath.local-deps :as local-deps]
            [vtranslate.cli.engine-classpath :as classpath]))

(def ^:private sibling-engine-dir "../vtranslate-engine")

(defn resolve-engine-dir
  "Local engine checkout dir by precedence: `env-override`, then the sibling
   ../vtranslate-engine when it exists on disk, else nil (caller falls back to
   the pinned git coordinate)."
  [env-override sibling-exists?]
  (cond
    (not-empty env-override) env-override
    sibling-exists?          sibling-engine-dir
    :else                    nil))

(defn engine-dir
  "Directory of the engine project when resolvable locally; nil when the
   pinned git fallback applies. See resolve-engine-dir for precedence."
  []
  (resolve-engine-dir (System/getenv "VTRANSLATE_ENGINE_DIR")
                      (fs/directory? sibling-engine-dir)))

(defn engine-invocation
  "Resolved engine invocation for `spec`: {:dir <local checkout> :command cmd}
   for a local checkout, or {:dir nil :command <pinned -Sdeps cmd>} for the
   packaged distribution. Addon classpath contributions come from `source`
   (default: the machine-local local.deps.edn)."
  ([spec] (engine-invocation (local-deps/from-file) spec))
  ([source spec]
   (if-let [dir (engine-dir)]
     {:dir dir :command (classpath/engine-command source spec)}
     {:dir nil :command (classpath/pinned-engine-command)})))

(defn parse-result
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

(defn- shell-engine
  "Shell `cmd` (in `dir` when non-nil), feed `spec` as EDN on stdin, parse the
   printed Result. => (r/ok ...) | (r/err ...)."
  [dir cmd spec]
  (let [{:keys [out err exit]}
        (apply p/shell
               (cond-> {:in (pr-str spec) :out :string :err :string :continue true}
                 dir (assoc :dir dir))
               cmd)]
    (parse-result out err exit)))

(defn run-job
  "Shell the engine in a JVM subprocess, feed `spec` as EDN on stdin, parse the
   printed Result. The subprocess stderr is threaded into any failure Result.
   => (r/ok ...) | (r/err ...)."
  [spec]
  (let [{:keys [dir command]} (engine-invocation spec)]
    (shell-engine dir command spec)))