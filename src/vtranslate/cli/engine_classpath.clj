(ns vtranslate.cli.engine-classpath
  "Pure classpath planning for the engine subprocess."
  (:require [clojure.string :as str]))

(def ^:private base-engine-aliases [:ffmpeg :whisper-jni])

(def ^:private run-engine-aliases [:run])

(def ^:private addon-alias-catalog
  {:vtranslate/context [:addon-context]})

(def ^:private addon-classpath-presets
  {:addon-context {:extra-paths ["../addon-context/src"]}})

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

(defn- addon-aliases [addon]
  (or (seq (explicit-aliases addon))
      (some-> (addon-id addon) addon-alias-catalog)))

(defn- spec-addon-aliases [spec]
  (->> (get-in spec [:config :addons])
       (mapcat addon-aliases)
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

(defn- engine-alias-list [spec]
  (vec (distinct (concat base-engine-aliases
                         (spec-addon-aliases spec)
                         run-engine-aliases))))

(defn engine-aliases
  "Clojure CLI aliases for the engine subprocess. Env override wins; otherwise
   addon specs may add classpath aliases, e.g. :vtranslate/context ->
   :addon-context."
  ([] (engine-aliases nil))
  ([spec]
   (or (engine-alias-env)
       (alias-string (engine-alias-list spec)))))

(defn- selected-classpath-presets [aliases]
  (into {}
        (keep (fn [alias]
                (let [k (alias-key alias)]
                  (when-let [preset (addon-classpath-presets k)]
                    [k preset]))))
        aliases))

(defn engine-sdeps [spec]
  (let [aliases (selected-classpath-presets (engine-alias-list spec))]
    (when (seq aliases)
      (pr-str {:aliases aliases}))))

(defn engine-command [spec]
  (cond-> ["clojure"]
    (engine-sdeps spec) (into ["-Sdeps" (engine-sdeps spec)])
    true (conj (str "-M" (engine-aliases spec)))))
