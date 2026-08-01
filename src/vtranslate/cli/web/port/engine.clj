(ns vtranslate.cli.web.port.engine
  "Port (DIP) for running one job through the engine.

   The panel must be exercisable without a JVM, a model, or a network, so the
   thing that actually runs a job is a COLLABORATOR the server is given, never a
   function it reaches for. A test injects a record; production injects the
   subprocess adapter."
  (:require [hive-dsl.result :as r]))

(defprotocol IEngineRunner
  "Run one validated JobSpec to completion."
  (run-spec [this spec log!]
    "Run `spec`, calling `log!` with each diagnostic line the run emits as it
     arrives. Blocks until the run finishes.
     => (r/ok {:rendered str ...}) | (r/err ...)."))

(defn runner?
  "True when `x` satisfies the port."
  [x]
  (satisfies? IEngineRunner x))

(defrecord FailingRunner [error]
  IEngineRunner
  (run-spec [_ _ log!]
    (log! "no engine runner configured")
    (r/err :error/no-engine-runner {:reason error})))

(def unavailable
  "A runner that refuses every job. The correct default when nothing has been
   injected — a job fails loud instead of appearing to succeed. A record, not a
   reify: babashka's `satisfies?` does not recognise a reify'd implementation."
  (->FailingRunner "no engine runner was injected"))

(defn checked-runner
  "Smart ctor: `x` if it satisfies the port.
   => (r/ok runner) | (r/err :error/invalid-engine-runner {...})."
  [x]
  (if (runner? x)
    (r/ok x)
    (r/err :error/invalid-engine-runner
           {:reason "value does not satisfy IEngineRunner" :type (str (type x))})))
