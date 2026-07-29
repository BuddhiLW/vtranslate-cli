(ns vtranslate.cli.adapters.addon-classpath.local-deps
  "IAddonClasspath backed by the GITIGNORED local.deps.edn.

   Shape read from that file:

     {:vtranslate.cli/addon-aliases {<addon-id> [<alias> ...]}
      :aliases {<alias> {:extra-paths [...]}}}

   A missing or unreadable file yields a source that contributes nothing, so a
   clean checkout plans a classpath with no addons rather than failing."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [vtranslate.cli.port.addon-classpath :as port]))

(def default-file "local.deps.edn")

(def addon-aliases-key :vtranslate.cli/addon-aliases)

(defn read-local-deps
  "Parse `path` as EDN. => map (possibly empty). Never throws: an absent,
   unreadable or malformed file is indistinguishable from no configuration."
  [path]
  (try
    (if (and path (fs/exists? path))
      (let [parsed (edn/read-string (slurp (fs/file path)))]
        (if (map? parsed) parsed {}))
      {})
    (catch Exception _ {})))

(defrecord LocalDepsClasspath [catalog presets]
  port/IAddonClasspath
  (aliases-for [_ addon-id]
    (vec (get catalog addon-id)))
  (preset-for [_ alias]
    (get presets alias)))

(defn make-source
  "Build the source from an already-parsed local.deps.edn `m`."
  [m]
  (->LocalDepsClasspath (get m addon-aliases-key {}) (get m :aliases {})))

(defn from-file
  "Build the source from the local.deps.edn at `path` (default `default-file`)."
  ([] (from-file default-file))
  ([path] (make-source (read-local-deps path))))
