(ns vtranslate.cli.port.addon-classpath
  "Port (DIP) for resolving an addon's engine classpath contribution.

   Addon checkouts are machine-local, so their locations are a COLLABORATOR of
   classpath planning, never a literal in this repo. Planning depends on this
   protocol only; a concrete source supplies the values."
  (:require [hive-dsl.result :as r]))

(defprotocol IAddonClasspath
  "Resolve addon ids to engine aliases, and aliases to their -Sdeps preset."
  (aliases-for [this addon-id]
    "Engine aliases contributed by `addon-id`.
     => [alias-keyword ...] — empty when the source knows no such addon.")
  (preset-for [this alias]
    "The -Sdeps alias map for `alias`.
     => {:extra-paths [...]} | {:extra-deps {...}} | nil when unknown."))

(defrecord EmptyClasspath []
  IAddonClasspath
  (aliases-for [_ _] [])
  (preset-for [_ _] nil))

(def empty-source
  "A source that contributes nothing. The correct default for any build with no
   machine-local addon configuration — an unknown addon yields no aliases rather
   than a guessed path. A record, not a reify: babashka's `satisfies?` does not
   recognise a reify'd protocol implementation."
  (->EmptyClasspath))

(defn source?
  "True when `x` satisfies the port."
  [x]
  (satisfies? IAddonClasspath x))

(defn checked-source
  "Smart ctor: `x` if it satisfies the port.
   => (r/ok source) | (r/err :error/invalid-addon-classpath-source {...})."
  [x]
  (if (source? x)
    (r/ok x)
    (r/err :error/invalid-addon-classpath-source
           {:reason "value does not satisfy IAddonClasspath" :type (str (type x))})))
