(ns vtranslate.cli.web.adapters.subprocess-engine
  "IEngineRunner over the engine subprocess. Unlike the argv adapter, which only
   needs the final Result, the panel shows a job while it runs — so stderr is
   consumed LINE BY LINE as the engine emits it and handed to `log!`, while
   stdout is collected whole for the single EDN Result the engine prints."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.cli.engine :as engine]
            [vtranslate.cli.web.port.engine :as port]))

(defn- pump-lines!
  "Read `reader` to EOF, calling `f` per line. Returns the lines joined, so a
   failure Result can still carry the whole stderr stream."
  [reader f]
  (let [collected (volatile! [])]
    (with-open [rdr (io/reader reader)]
      (doseq [line (line-seq rdr)]
        (vswap! collected conj line)
        (f line)))
    (str/join "\n" @collected)))

(defrecord SubprocessEngine []
  port/IEngineRunner
  (run-spec [_ spec log!]
    (let [{:keys [dir command]} (engine/engine-invocation spec)
          proc (apply p/process
                      (cond-> {:in (pr-str spec) :out :string :err :stream}
                        dir (assoc :dir dir))
                      command)
          ;; stderr must be drained on THIS thread while the process runs: a
          ;; full pipe buffer would otherwise block the engine forever.
          err  (pump-lines! (:err proc) log!)
          {:keys [out exit]} @proc]
      (engine/parse-result out err exit))))

(defn make-runner
  "The production runner: shells the engine per job."
  []
  (->SubprocessEngine))

(defn available?
  "Whether an engine is resolvable at all — a local checkout or the pinned git
   coordinate. => {:dir <checkout or nil> :command [...]}."
  []
  (engine/engine-invocation {:config {}}))

(defn checked
  "The production runner, verified against the port.
   => (r/ok runner) | (r/err ...)."
  []
  (r/let-ok [runner (port/checked-runner (make-runner))]
    (r/ok runner)))
