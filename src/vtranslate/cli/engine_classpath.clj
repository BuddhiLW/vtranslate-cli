(ns vtranslate.cli.engine-classpath
  "Pure classpath planning for the engine subprocess. Addon contributions are
   resolved through port.addon-classpath/IAddonClasspath, injected by the
   caller — machine-local checkout locations never appear here."
  (:require [clojure.string :as str]
            [vtranslate.cli.port.addon-classpath :as port]))

(def ^:private base-engine-aliases [:ffmpeg :whisper-jni])

(def ^:private run-engine-aliases [:run])

(defn- addon-id [addon]
  (cond
    (keyword? addon) addon
    (map? addon) (or (:id addon) (:addon/id addon) (:catalog addon)
                     (when (keyword? (:addon addon)) (:addon addon)))
    :else nil))

(defn- explicit-aliases [addon]
  (when (map? addon)
    (or (:classpath/aliases addon)
        (:engine/aliases addon)
        (:aliases addon))))

(defn- addon-aliases [source addon]
  (or (seq (explicit-aliases addon))
      (some->> (addon-id addon) (port/aliases-for source) seq)))

(defn- spec-addon-aliases [source spec]
  (->> (get-in spec [:config :addons])
       (mapcat #(addon-aliases source %))
       distinct
       vec))

(defn- alias-name [alias]
  (let [s (if (keyword? alias) (name alias) (str alias))]
    (if (str/starts-with? s ":") (subs s 1) s)))

(defn- alias-token [alias]
  (str ":" (alias-name alias)))

(defn- alias-key [alias]
  (keyword (alias-name alias)))

(defn- alias-string [aliases]
  (apply str (map alias-token aliases)))

(defn engine-alias-env []
  (not-empty (System/getenv "VTRANSLATE_ENGINE_ALIASES")))

(defn- engine-alias-list [source spec]
  (vec (distinct (concat base-engine-aliases
                         (spec-addon-aliases source spec)
                         run-engine-aliases))))

(defn engine-aliases
  "Clojure CLI aliases for the engine subprocess. Env override wins; otherwise
   addon specs may add classpath aliases, e.g. :vtranslate/context ->
   :addon-context."
  ([] (engine-aliases port/empty-source nil))
  ([spec] (engine-aliases port/empty-source spec))
  ([source spec]
   (or (engine-alias-env)
       (alias-string (engine-alias-list source spec)))))

(defn- selected-classpath-presets [source aliases]
  (into {}
        (keep (fn [alias]
                (let [k (alias-key alias)]
                  (when-let [preset (port/preset-for source k)]
                    [k preset]))))
        aliases))

(defn engine-sdeps
  "-Sdeps EDN for the addon aliases `spec` selects, or nil when none contribute."
  ([spec] (engine-sdeps port/empty-source spec))
  ([source spec]
   (let [aliases (selected-classpath-presets source (engine-alias-list source spec))]
     (when (seq aliases)
       (pr-str {:aliases aliases})))))

(defn engine-command
  "argv running the engine from a sibling checkout, with addon aliases resolved
   through `source`."
  ([spec] (engine-command port/empty-source spec))
  ([source spec]
   (cond-> ["clojure"]
     (engine-sdeps source spec) (into ["-Sdeps" (engine-sdeps source spec)])
     true (conj (str "-M" (engine-aliases source spec))))))

(def pinned-engine-coord
  "Pinned git coordinate of the engine for the packaged distribution; mirrors
   io.github.BuddhiLW/vtranslate-engine in deps.edn (managed by bb-depsolve)."
  '{io.github.BuddhiLW/vtranslate-engine {:git/tag "v0.1.0" :git/sha "cd7477e"}})

(def ^:private pinned-backend-deps
  "Backend deps of the engine repo's :ffmpeg/:whisper-jni aliases, restated
   because -Sdeps aliases cannot reference a dependency's own aliases."
  '{io.github.givimad/whisper-jni       {:mvn/version "1.7.1"}
    org.bytedeco/javacv                 {:mvn/version "1.5.10"}
    org.bytedeco/ffmpeg$linux-x86_64    {:mvn/version "6.1.1-1.5.10"}
    org.bytedeco/javacpp$linux-x86_64   {:mvn/version "1.5.10"}})

(defn pinned-engine-sdeps
  "-Sdeps EDN string resolving the engine from the pinned git coordinate, with
   the engine's subprocess entrypoint as :main-opts."
  []
  (pr-str {:aliases {:vtranslate-engine
                     {:extra-deps (merge pinned-engine-coord pinned-backend-deps)
                      :main-opts  ["-m" "vtranslate.engine.main"]}}}))

(defn pinned-engine-command
  "Clojure CLI command running the engine from the pinned git coordinate
   (packaged distribution; no local checkout required)."
  []
  ["clojure" "-Sdeps" (pinned-engine-sdeps) "-M:vtranslate-engine"])
