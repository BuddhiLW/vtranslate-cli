(ns vtranslate.cli.config
  "Read / merge / mutate the user config that selects providers. The ENGINE
   resolves routing from this SAME file (vtranslate.engine.providers.config); the
   CLI owns editing it. The ACTIVE selection lives at [:providers <port>] — a bare
   provider keyword — which is exactly what the engine reads. `show` reveals
   baked-defaults <- user-file; the engine layers env/flag overrides on top at run
   time. Mutations write 0600."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-di.file :as edn-file]
            [hive-dsl.result :as r]))

(defn config-path
  "XDG-correct user-config path: $XDG_CONFIG_HOME/vtranslate/config.edn else
   ~/.config/vtranslate/config.edn. MUST mirror the engine's config-path."
  []
  (let [base (or (not-empty (System/getenv "XDG_CONFIG_HOME"))
                 (str (System/getProperty "user.home") "/.config"))]
    (str base "/vtranslate/config.edn")))

(defn default-config
  "Baked provider registry + sane defaults (resources/default-config.edn)."
  []
  (some-> (io/resource "default-config.edn") slurp edn/read-string))

(defn- deep-merge [a b]
  (cond (and (map? a) (map? b)) (merge-with deep-merge a b)
        (some? b)               b
        :else                   a))

(defn read-user
  "Read the user config file => (r/ok map|nil) | (r/err :io/edn-read ...)."
  []
  (edn-file/read-edn (config-path)))

(defn effective
  "Baked defaults <- user file, deep-merged. => (r/ok map) | (r/err ...)."
  []
  (r/let-ok [user (read-user)]
    (r/ok (deep-merge (default-config) (or user {})))))

;; --- friendly CLI words -> engine port keys --------------------------------

(def ^:private port-aliases
  {"asr" :transcriber "transcriber" :transcriber
   "mt"  :translator  "translator"  :translator "translate" :translator})

(defn resolve-port
  "Normalize a friendly port word (asr/mt/...) to the engine port key, or nil."
  [word]
  (get port-aliases (some-> word str str/lower-case)))

;; --- mutation: read -> assoc-in -> write 0600 ------------------------------

(defn- write-user! [data]
  (edn-file/write-edn! (config-path) data {:secret? true}))

(defn init!
  "Create the user config from baked defaults if absent (idempotent).
   => (r/ok message) | (r/err :io/edn-write ...)."
  []
  (r/let-ok [user (read-user)]
    (if user
      (r/ok (str "exists: " (config-path)))
      (r/let-ok [_ (write-user! (default-config))]
        (r/ok (str "created: " (config-path)))))))

(defn set-path!
  "Set the key-vector `ks` to `value` in the user file (read -> assoc-in -> write).
   => (r/ok new-user-config) | (r/err ...)."
  [ks value]
  (r/let-ok [user (read-user)]
    (write-user! (assoc-in (or user {}) ks value))))

(defn set-key!
  "Set a dotted key (e.g. \"providers.translator\") to an EDN value.
   => (r/ok message) | (r/err ...)."
  [dotted value]
  (r/let-ok [_ (set-path! (mapv keyword (str/split dotted #"\.")) value)]
    (r/ok (str "set " dotted " = " (pr-str value)))))

(defn known-providers
  "Provider keys registered for `port` in the effective registry."
  [cfg port]
  (vec (keys (get-in cfg [:registry port]))))

(defn use-provider!
  "Select `provider` for `port` (writes [:providers port]) after validating it
   against the registry. => (r/ok message) | (r/err :error/unknown-provider {...})."
  [port provider]
  (r/let-ok [cfg (effective)]
    (let [known (known-providers cfg port)]
      (if (some #{provider} known)
        (r/let-ok [_ (set-path! [:providers port] provider)]
          (r/ok (str (name port) " -> " provider)))
        (r/err :error/unknown-provider
               {:port port :provider provider :known known})))))
