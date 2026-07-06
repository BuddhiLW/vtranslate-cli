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
            [babashka.fs :as fs]))

(defn- emit
  "Print a Result for humans; return an exit code (0 ok / 1 err). A non-nil ok
   value is printed (strings raw, data pretty)."
  [res]
  (if (r/ok? res)
    (do (when-let [v (:ok res)] (if (string? v) (println v) (pp/pprint v))) 0)
    (do (binding [*out* *err*] (println "error:" (pr-str res))) 1)))

(defn- env-set? [v] (boolean (not-empty (System/getenv (str v)))))

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
      (emit (r/err :error/unknown-port {:port port-word :known ["asr" "mt"]})))))

(defn- print-port-providers [cfg port]
  (println (str "\n" (name port) "  (active: " (get-in cfg [:providers port]) ")"))
  (doseq [[pid spec] (get-in cfg [:registry port])]
    (let [active? (= pid (get-in cfg [:providers port]))
          sec     (:secret-env spec)]
      (println (format "  %s %-16s %s%s"
                       (if active? "*" " ")
                       (name pid)
                       (or (:api-url spec) (when (:offline spec) "(offline)") "")
                       (if sec
                         (str "  [" sec (if (env-set? sec) " set]" " UNSET]"))
                         ""))))))

(defn- cmd-provider-list [m]
  (let [[port-word] (:args m)]
    (emit (r/let-ok [cfg (config/effective)]
            (let [ports (if-let [p (config/resolve-port port-word)]
                          [p] [:transcriber :translator])]
              (run! #(print-port-providers cfg %) ports)
              (r/ok nil))))))

;; --- run (positional: source target [source-lang|auto] [format]) ----------------

(defn- output-path [source target fmt output]
  (or output
      (let [p      (fs/path source)
            parent (fs/parent p)
            stem   (fs/strip-ext (fs/file-name p))]
        (str (fs/path (or parent (fs/path ".")) (str stem "." target "." fmt))))))

(defn- write-rendered [result output]
  (if (r/err? result)
    result
    (let [rendered (get-in result [:ok :rendered])]
      (if (string? rendered)
        (do
          (when-let [parent (fs/parent (fs/path output))]
            (fs/create-dirs parent))
          (spit output rendered)
          (r/ok {:output output
                 :job    (select-keys (get-in result [:ok :job])
                                      [:id :state :target-language :subtitle-id])}))
        (r/err :error/no-rendered-subtitle {:output output})))))

(defn- cmd-run [m]
  (let [[source target source-lang fmt output] (:args m)
        fmt   (or fmt "srt")
        usage "usage: vtranslate run <source> <target-lang> [source-lang|auto] [format] [output]"]
    (cond
      (nil? source) (emit (r/err :error/missing-source {:hint usage}))
      (nil? target) (emit (r/err :error/missing-target {:hint usage}))
      :else
      (let [out (output-path source target fmt output)]
        (emit
         (write-rendered
          (engine/run-job
           {:job-id          (str "cli-" (System/currentTimeMillis))
            :source          source
            :source-language (or source-lang "auto")
            :target-language target
            :format          (keyword "format" fmt)
            :config          {}})
          out))))))

;; --- dispatch ---------------------------------------------------------------

(defn- cmd-help [_]
  (println "vtranslate — turn multi-source-language video/subs into one-language subtitles\n")
  (println "  config path                      print the user config path (XDG)")
  (println "  config init                      create the config from defaults (0600)")
  (println "  config show [raw]                show effective (or raw user) config")
  (println "  config get <dotted.key>          read a value, e.g. providers.translator")
  (println "  config set <dotted.key> <edn>    set a value, e.g. providers.translator :deepl")
  (println "  provider use <asr|mt> <name>     select a provider (validated, persisted)")
  (println "  provider list [asr|mt]           list providers; * = active, secret-env status")
  (println "  run <source> <target-lang> [source-lang|auto] [format] [output]")
  (println "                                   translate; format = srt|vtt, output defaults beside source")
  0)

(def ^:private table
  [{:cmds ["config" "path"]   :fn cmd-config-path}
   {:cmds ["config" "init"]   :fn cmd-config-init}
   {:cmds ["config" "show"]   :fn cmd-config-show}
   {:cmds ["config" "get"]    :fn cmd-config-get}
   {:cmds ["config" "set"]    :fn cmd-config-set}
   {:cmds ["provider" "use"]  :fn cmd-provider-use}
   {:cmds ["provider" "list"] :fn cmd-provider-list}
   {:cmds ["run"]             :fn cmd-run}
   {:cmds []                  :fn cmd-help}])

(defn -main [& args]
  (System/exit (or (cli/dispatch table (vec args)) 0)))
