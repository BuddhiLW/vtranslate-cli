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
            [hive-dsl.result :as r]
            [hive-di.pass :as hive-pass]))

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
   "mt"  :translator  "translator"  :translator "translate" :translator
   "digest" :comprehender "comprehender" :comprehender "motive" :comprehender})

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

(def ^:private composer-registry
  {:none {:offline true :note "no video muxing"}
   :soft {:offline true :note "embed selectable subtitle track"}
   :hard {:offline true :note "burn subtitles into video"}})

(defn- registry-for [cfg port]
  (if (= :composer port)
    (merge composer-registry (get-in cfg [:registry port]))
    (or (get-in cfg [:registry port]) {})))

(defn known-providers
  "Provider keys registered for `port` in the effective registry."
  [cfg port]
  (vec (keys (registry-for cfg port))))

(defn opts-key
  "The per-port options key the engine (and the context addon) read, e.g.
   :translator -> :translator-opts."
  [port]
  (keyword (str (name port) "-opts")))

(def provider-ports [:transcriber :translator :comprehender :composer])

(defn env-set? [env-name]
  (boolean (not-empty (System/getenv (str env-name)))))

(defn pass-entry?
  "Whether `path` resolves in the pass store, WITHOUT retaining the secret —
   presence is the only question a diagnostic may ask. Delegates to hive-di,
   which owns pass-store reads for :source/pass."
  [path]
  (hive-pass/present? path))

(defn ports-using
  "Ports whose ACTIVE provider is `provider`. An API key belongs to the provider,
   but the engine reads it per port ([<port>-opts :secret-pass]), so one Venice
   account has to be written to every port currently pointed at Venice."
  [cfg provider]
  (filterv #(= provider (get-in cfg [:providers %])) provider-ports))

(defn provider-secret-pass
  "The pass path already configured for `provider` on any port using it, so a
   second port on the same provider can inherit the key instead of asking for
   it again. => path | nil"
  [cfg provider]
  (some (fn [port] (get-in cfg [(opts-key port) :secret-pass]))
        (ports-using cfg provider)))

(defn set-provider-secret!
  "Point every port currently using `provider` at pass `path`.

   A key belongs to the PROVIDER — one Venice account serves translation and
   the digest alike — but the engine and the context addon each read it per
   port ([<port>-opts :secret-pass]). Writing every using port keeps the file
   honest: a panel that showed the key as shared while only one port carried it
   would be claiming something the engine does not do.
   => (r/ok message) | (r/err :error/provider-not-active {...})."
  [provider path]
  (r/let-ok [cfg  (effective)
             user (read-user)]
    (let [ports (ports-using cfg provider)]
      (if (empty? ports)
        (r/err :error/provider-not-active
               {:provider provider
                :hint "no port is using this provider — select it first (provider use)"})
        (r/let-ok [_ (write-user! (reduce (fn [u port]
                                            (assoc-in u [(opts-key port) :secret-pass] path))
                                          (or user {})
                                          ports))]
          (r/ok (str provider " key <- pass " path
                     "  (" (str/join ", " (map name ports)) ")")))))))

(defn secret-source
  "Where this port's API key actually comes from, mirroring the precedence the
   engine applies in adapters.support.secrets/resolve-key: a RESOLVABLE pass
   path wins over the env var, so a stale env key cannot shadow the real one.
   `pass-exists?` is injected so this is decidable without a pass store.
   => :pass | :env | nil"
  ([secret-env secret-pass] (secret-source secret-env secret-pass pass-entry?))
  ([secret-env secret-pass pass-exists?]
   (cond
     (and secret-pass (pass-exists? secret-pass)) :pass
     (and secret-env (env-set? secret-env))       :env
     :else                                        nil)))

(defn provider-diagnostic [cfg port]
  (let [active (get-in cfg [:providers port])
        registry (registry-for cfg port)
        spec (get registry active)
        opts (get cfg (keyword (str (name port) "-opts")))
        secret-env (:secret-env spec)
        secret-pass (:secret-pass opts)
        source (secret-source secret-env secret-pass)]
    {:port port
     :active active
     :known (known-providers cfg port)
     :configured? (boolean spec)
     :offline? (boolean (:offline spec))
     :api-url (:api-url spec)
     :binary (:binary spec)
     :model (or (:model opts) (:default-model spec))
     :model-path (:model-path opts)
     :secret-env secret-env
     :secret-pass secret-pass
     :secret-source source
     ;; a key resolvable from ANY source, not just the env var — reporting
     ;; "unset" for a working pass-backed key is what this replaced.
     :secret-set? (when (or secret-env secret-pass) (some? source))
     :opts opts
     :note (:note spec)}))

(defn doctor-report [cfg]
  {:config-path (config-path)
   :addons (:addons cfg)
   :providers (mapv #(provider-diagnostic cfg %) provider-ports)})

(defn doctor []
  (r/let-ok [cfg (effective)]
    (r/ok (doctor-report cfg))))

(defn use-provider!
  "Select `provider` for `port` (writes [:providers port]) after validating it
   against the registry. A port switching onto a provider INHERITS the pass path
   another port already uses for it, so the same account is not configured twice.
   => (r/ok message) | (r/err :error/unknown-provider {...})."
  [port provider]
  (r/let-ok [cfg (effective)]
    (let [known (known-providers cfg port)]
      (if-not (some #{provider} known)
        (r/err :error/unknown-provider
               {:port port :provider provider :known known})
        (r/let-ok [_ (set-path! [:providers port] provider)]
          (let [inherited (when-not (get-in cfg [(opts-key port) :secret-pass])
                            (provider-secret-pass cfg provider))]
            (r/let-ok [_ (if inherited
                           (set-path! [(opts-key port) :secret-pass] inherited)
                           (r/ok nil))]
              (r/ok (cond-> (str (name port) " -> " provider)
                      inherited (str "  (key <- pass " inherited ")"))))))))))