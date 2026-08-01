(ns vtranslate.cli.web.server
  "HTTP boundary for the control panel. Owns encoding, the action dispatch, and
   the http-kit lifecycle; every decision it makes is delegated to a pure ns or
   an injected collaborator.

   Binds to loopback only. This is a local panel over a local engine — it runs
   commands and reads files on the host, so it must not be reachable off it."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [org.httpkit.server :as http]
            [vtranslate.cli.config :as config]
            [vtranslate.cli.web.adapters.subprocess-engine :as subprocess]
            [vtranslate.cli.web.job :as job]
            [vtranslate.cli.web.routes :as routes]
            [vtranslate.cli.web.runner :as runner]
            [vtranslate.cli.web.schema :as schema]
            [vtranslate.cli.web.store :as store]))

(def ^:private json-headers {"Content-Type" "application/json; charset=utf-8"})

(defn- ok-json [body]  {:status 200 :headers json-headers :body (json/generate-string body)})
(defn- bad-json [body] {:status 400 :headers json-headers :body (json/generate-string body)})
(defn- not-found []    {:status 404 :headers json-headers :body (json/generate-string {:error "not found"})})

(defn- read-json-body
  "Parse a request body as JSON with keyword keys. => map | nil."
  [request]
  (when-let [body (:body request)]
    (try (json/parse-string (slurp body) true)
         (catch Exception _ nil))))

(defn- result->http
  "A hive-dsl Result becomes a response: ok payload at 200, err payload at 400."
  [res]
  (if (r/ok? res)
    (ok-json (:ok res))
    (bad-json (dissoc res :err))))

;; --- config -----------------------------------------------------------------

(defn- config-view
  "What the panel needs to render and edit routing: the active provider per port
   and the registry it may choose from."
  []
  (r/let-ok [cfg (config/effective)]
    (r/ok {:providers (:providers cfg)
           :registry  (into {}
                            (map (fn [[port entries]]
                                   [port (mapv (fn [[pid spec]]
                                                 {:id         pid
                                                  :api-url    (:api-url spec)
                                                  :offline    (boolean (:offline spec))
                                                  :secret-env (:secret-env spec)
                                                  :secret-set (when-let [e (:secret-env spec)]
                                                                (config/env-set? e))})
                                               entries)]))
                            (:registry cfg))
           :config-path (str (config/config-path))})))

(defn- patch-config!
  "Re-route one port from the panel. => Result<config-view>."
  [request]
  (r/let-ok [patch (schema/conform schema/ConfigPatch
                                   (select-keys (read-json-body request) [:port :provider]))
             port  (if-let [p (config/resolve-port (:port patch))]
                     (r/ok p)
                     (r/err :error/unknown-port {:port (:port patch)
                                                 :known ["asr" "mt" "digest"]}))
             _     (config/use-provider! port (keyword (:provider patch)))]
    (config-view)))

;; --- jobs -------------------------------------------------------------------

(def ^:private submit-keys [:source :target :source-language :format :mux])

(defn- create-job!
  "Validate a submission and queue it. => Result<job-view>."
  [{:keys [store deps]} request]
  (r/let-ok [submitted (schema/conform
                        schema/SubmitRequest
                        (select-keys (read-json-body request) submit-keys))
             _         (if (fs/exists? (:source submitted))
                         (r/ok :present)
                         (r/err :error/source-not-found {:source (:source submitted)}))]
    (r/ok (runner/describe (runner/submit! deps store submitted)
                           ((:clock deps))))))

(defn- serve-subtitle
  "Serve the subtitle a finished job wrote, as a download."
  [store id]
  (let [j    (store/fetch store id)
        path (get-in j [:result :output])]
    (cond
      (nil? j)                 (not-found)
      (not (job/terminal? j))  (bad-json {:error "job has not finished" :status (:status j)})
      (nil? path)              (bad-json {:error "job produced no subtitle"})
      (not (fs/exists? path))  (bad-json {:error "subtitle file is gone" :path path})
      :else {:status  200
             :headers {"Content-Type"        "text/plain; charset=utf-8"
                       "Content-Disposition" (str "attachment; filename=\""
                                                  (fs/file-name path) "\"")}
             :body    (slurp path)})))

;; --- dispatch ---------------------------------------------------------------

(defn- index-page []
  (if-let [page (io/resource "public/index.html")]
    {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body (slurp page)}
    {:status 500 :body "index.html missing from resources/public"}))

(defn- dispatch
  "Route `request` to its action and run it."
  [{:keys [store deps] :as ctx} request]
  (let [{:keys [action params]} (routes/match (:request-method request) (:uri request))
        now (fn [] ((:clock deps)))]
    (case action
      :ui/index      (index-page)
      :health/show   (ok-json {:engine (subprocess/available?)
                               :active (store/active? store)})
      :config/show   (result->http (config-view))
      :config/patch  (result->http (patch-config! request))
      :jobs/list     (ok-json (mapv #(runner/describe % (now)) (store/recent store 50)))
      :jobs/create   (result->http (create-job! ctx request))
      :jobs/show     (if-let [j (store/fetch store (:id params))]
                       (ok-json (runner/describe j (now)))
                       (not-found))
      :jobs/subtitle (serve-subtitle store (:id params))
      (not-found))))

(defn make-handler
  "A ring handler over `store` and `deps`. Any escaping throwable becomes a 500
   carrying its message — a browser must never see a dropped connection."
  [ctx]
  (fn [request]
    (try
      (dispatch ctx request)
      (catch Throwable t
        {:status 500
         :headers json-headers
         :body    (json/generate-string {:error (or (.getMessage t) (str (type t)))})}))))

(defn default-deps
  "Production collaborators: the subprocess engine, the wall clock, and
   monotonic job ids."
  []
  (let [counter (atom 0)]
    {:engine  (subprocess/make-runner)
     :clock   #(System/currentTimeMillis)
     :next-id #(str "web-" (System/currentTimeMillis) "-" (swap! counter inc))}))

(defn start!
  "Start the panel on `port`, bound to loopback. => {:stop fn :port int}."
  [{:keys [port] :or {port 7777}}]
  (let [store (store/make-store)
        deps  (default-deps)
        stop  (http/run-server (make-handler {:store store :deps deps})
                               {:port port :ip "127.0.0.1"})]
    (println (str "vtranslate panel  http://127.0.0.1:" port))
    (let [{:keys [dir]} (subprocess/available?)]
      (println (str "engine            " (or dir "pinned git coordinate")))
      (when-not dir
        (println "                  (no local checkout — first run resolves deps, expect a wait)")))
    {:stop stop :port port :store store}))

(defn -main
  "Entry point: `bb web [port]`."
  [& args]
  (let [port (or (some-> (first args) str/trim parse-long) 7777)]
    (start! {:port port})
    @(promise)))
