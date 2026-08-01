(ns vtranslate.cli.main
  "Argv adapter (the only entry point): parse subcommands -> call config/engine ->
   print a hive-dsl Result for humans, exit 0 (ok) / 1 (err). All arguments are
   POSITIONAL (no flags). Subcommands:
     config  path | init | show [raw] | get <dotted.key> | set <dotted.key> <edn>
     provider use <asr|mt> <name> | list [asr|mt]
     run <source> <target-lang> [source-lang|auto] [format]
   Switch providers with `provider use` (persisted to the config the engine reads)."
  (:require [babashka.cli :as cli]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.cli.config :as config]
            [vtranslate.cli.engine :as engine]
            [vtranslate.cli.job-spec :as js]
            [vtranslate.cli.output :as out]))

(defn- emit
  "Print a Result for humans; return an exit code (0 ok / 1 err). A non-nil ok
   value is printed (strings raw, data pretty). On err any carried engine :err
   stream is echoed raw, then the structured Result (sans :err) is printed — both
   to stderr."
  [res]
  (if (r/ok? res)
    (do (when-let [v (:ok res)] (if (string? v) (println v) (pp/pprint v))) 0)
    (binding [*out* *err*]
      (when-let [e (:err res)]
        (when-not (str/blank? e) (println (str/trimr e))))
      (println "error:" (pr-str (dissoc res :err)))
      1)))

(defn- env-set? [v] (boolean (not-empty (System/getenv (str v)))))

(defn- secret-label
  "How a provider's key resolves, in the precedence the engine applies: a
   resolvable pass path beats the env var. `pass` is nil for a provider that is
   not the active one, since the pass path is configured per PORT."
  [spec pass]
  (let [sec (:secret-env spec)]
    (if-not (or sec pass)
      ""
      (case (config/secret-source sec pass)
        :pass (str "  [pass " pass "]")
        :env  (str "  [" sec " set]")
        (str "  [" (or sec pass) " UNSET]")))))

;; --- config subcommands -----------------------------------------------------

(defn- cmd-config-path [_] (println (config/config-path)) 0)
(defn- cmd-config-init [_] (emit (config/init!)))

(defn- cmd-config-show [m]
  (let [[mode] (:args m)]
    (emit (if (= mode "raw") (config/read-user) (config/effective)))))

(defn- cmd-config-get [m]
  (let [[dotted] (:args m)]
    (emit (r/let-ok [cfg (config/effective)]
            (r/ok (get-in cfg (mapv keyword (str/split (str dotted) #"\."))))))))

(defn- cmd-config-set [m]
  (let [[dotted v] (:args m)]
    (emit (config/set-key! dotted (edn/read-string v)))))

;; --- provider subcommands ---------------------------------------------------

(defn- cmd-provider-use [m]
  (let [[port-word provider] (:args m)
        port (config/resolve-port port-word)]
    (if port
      (emit (config/use-provider! port (keyword provider)))
      (emit (r/err :error/unknown-port {:port port-word :known ["asr" "mt" "digest"]})))))

(defn- cmd-provider-key
  "provider key <asr|mt|digest> <pass-path> — point the port's ACTIVE provider at
   a pass entry. Every other port on the same provider is pointed at it too,
   because the key belongs to the provider, not the port."
  [m]
  (let [[port-word path] (:args m)
        port (config/resolve-port port-word)]
    (cond
      (nil? port)
      (emit (r/err :error/unknown-port {:port port-word :known ["asr" "mt" "digest"]}))

      (str/blank? (str path))
      (emit (r/err :error/missing-pass-path
                   {:hint "usage: vtranslate provider key <asr|mt|digest> <pass-path>"}))

      :else
      (emit (r/let-ok [cfg (config/effective)]
              (config/set-provider-secret! (get-in cfg [:providers port]) path))))))

(defn- print-port-providers [cfg port]
  (println (str "\n" (name port) "  (active: " (get-in cfg [:providers port]) ")"))
  (let [active (get-in cfg [:providers port])
        pass   (get-in cfg [(keyword (str (name port) "-opts")) :secret-pass])]
    (doseq [[pid spec] (get-in cfg [:registry port])]
      (let [active? (= pid active)]
        (println (format "  %s %-16s %s%s"
                         (if active? "*" " ")
                         (name pid)
                         (or (:api-url spec) (when (:offline spec) "(offline)") "")
                         (secret-label spec (when active? pass))))))))

(defn- cmd-provider-list [m]
  (let [[port-word] (:args m)]
    (emit (r/let-ok [cfg (config/effective)]
            (let [ports (if-let [p (config/resolve-port port-word)]
                          [p] [:transcriber :translator :comprehender])]
              (run! #(print-port-providers cfg %) ports)
              (r/ok nil))))))

(defn- cmd-doctor [_]
  (emit (r/let-ok [report (config/doctor)]
          (let [inv (engine/engine-invocation {:config {:addons (:addons report)}})]
            (r/ok (assoc report
                         :engine-dir (:dir inv)
                         :engine-command (:command inv)))))))

;; --- run (positional: source target [source-lang|auto] [format]) ----------------

(defn- cmd-run [m]
  (let [[source target source-lang fmt output] (:args m)
        fmt   (or fmt "srt")
        mux   (some-> (get-in m [:opts :mux]) keyword)
        mux?  (and mux (not= :none mux))
        usage "usage: vtranslate run <source> <target-lang> [source-lang|auto] [format] [output] [--mux soft|hard|both]"]
    (cond
      (nil? source) (emit (r/err :error/missing-source {:hint usage}))
      (nil? target) (emit (r/err :error/missing-target {:hint usage}))
      :else
      (let [sub-out   (out/subtitle-path source target fmt output)
            video-out (when mux? (out/video-path source target))]
        (emit
         (r/let-ok [spec (js/validate
                          (js/build-job-spec {:job-id          (str "cli-" (System/currentTimeMillis))
                                              :source          source
                                              :target          target
                                              :source-language source-lang
                                              :format          fmt
                                              :mux             (when mux? mux)
                                              :output          video-out}))]
           (out/write-rendered (engine/run-job spec) sub-out)))))))

;; --- dispatch ---------------------------------------------------------------

(defn- cmd-help [_]
  (println "vtranslate — turn multi-source-language video/subs into one-language subtitles\n")
  (println "  config path                      print the user config path (XDG)")
  (println "  config init                      create the config from defaults (0600)")
  (println "  config show [raw]                show effective (or raw user) config")
  (println "  config get <dotted.key>          read a value, e.g. providers.translator")
  (println "  config set <dotted.key> <edn>    set a value, e.g. providers.translator :deepl")
  (println "  provider use <asr|mt|digest> <name>  select a provider (validated, persisted)")
  (println "  provider key <asr|mt|digest> <pass-path>")
  (println "                                   read that provider's API key from `pass`, e.g.")
  (println "                                   provider key mt Venice/key — applied to every")
  (println "                                   port using the provider, since the key is its own")
  (println "  provider list [asr|mt|digest]        list providers; * = active, key source shown")
  (println "  doctor                           show providers, models, keys, engine aliases")
  (println "  run <source> <target-lang> [source-lang|auto] [format] [output] [--mux soft|hard|both]")
  (println "                                   translate; format = srt|vtt, output defaults beside source.")
  (println "                                   --mux hard burns subs in; soft embeds a selectable track (both .mp4)")
  (println "                                   --mux both writes soft+hard variants: <out>.soft.mp4, <out>.hard.mp4")
  0)

(def ^:private table
  [{:cmds ["config" "path"]   :fn cmd-config-path}
   {:cmds ["config" "init"]   :fn cmd-config-init}
   {:cmds ["config" "show"]   :fn cmd-config-show}
   {:cmds ["config" "get"]    :fn cmd-config-get}
   {:cmds ["config" "set"]    :fn cmd-config-set}
   {:cmds ["provider" "use"]  :fn cmd-provider-use}
   {:cmds ["provider" "key"]  :fn cmd-provider-key}
   {:cmds ["provider" "list"] :fn cmd-provider-list}
   {:cmds ["doctor"]          :fn cmd-doctor}
   {:cmds ["run"]             :fn cmd-run
    :spec {:mux {:desc "attach translated subs to the video: soft (embed) | hard (burn-in) | both (two files)"}}}
   {:cmds []                  :fn cmd-help}])

(defn -main [& args]
  (System/exit (or (cli/dispatch table (vec args)) 0)))