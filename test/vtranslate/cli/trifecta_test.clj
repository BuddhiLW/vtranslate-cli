(ns vtranslate.cli.trifecta-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.cli.schema :as s]
            [vtranslate.cli.config :as config]
            [vtranslate.cli.job-spec :as spec]
            [vtranslate.cli.output :as output]
            [vtranslate.cli.engine :as engine]
            [vtranslate.cli.engine-classpath :as cp]
            [vtranslate.cli.adapters.addon-classpath.local-deps :as local]
            [vtranslate.cli.port.addon-classpath :as ac]
            [vtranslate.cli.web.port.engine :as ep]
            [vtranslate.cli.web.port.filepicker :as fp]
            [vtranslate.cli.web.job :as job]
            [vtranslate.cli.web.schema :as web]
            [vtranslate.cli.web.routes :as routes]
            [vtranslate.cli.web.dialog :as dialog]
            [vtranslate.cli.web.runner :as runner]
            [vtranslate.cli.web.adapters.native-dialog :as native]))

;; Every subject retains its input so the property checks a relation, not only
;; an output type. The macro expands to one deftrifecta per public pure API.
(defmacro contract [label subject schema cases generator relation]
  (let [wrapper (symbol (str label "-observation"))]
    `(do
       (defn ~wrapper [args#] {:in args# :out (apply ~subject args#)})
       (deftrifecta ~label ~wrapper
         {:golden-path ~(str "test/golden/" label ".edn")
          :cases ~cases :gen ~generator
          :pred (fn [{in# :in out# :out}]
                  (and (m/validate ~schema out#) (~relation in# out#)))}))))

(def request {:source "film.mp4" :target "pt"})
(def queued (job/make "j" request 10))
(def running (job/start queued 20))
(def gen-time (gen/choose 20 100000))
(def gen-job (gen/elements [queued running (job/succeed running 30 {}) (job/fail running 30 {:error :x})]))

(contract make job/make s/Job
  {:queued ["j" request 10]} (gen/fmap #(vector "j" request %) gen-time)
  (fn [[id req now] out] (and (= id (:id out)) (= req (:request out)) (= now (:submitted-at out)) (= :queued (:status out)) (empty? (:log out)))))
(contract start job/start s/Job
  {:start [queued 20]} (gen/fmap #(vector queued %) gen-time)
  (fn [[j now] out] (= out (assoc j :status :running :started-at now))))
(contract succeed job/succeed s/Job
  {:done [running 30 {:output "film.pt.srt"}]} (gen/fmap #(vector running % {:output "film.pt.srt"}) gen-time)
  (fn [[j now result] out] (= out (assoc j :status :succeeded :finished-at now :result result))))
(contract fail job/fail s/Job
  {:failed [running 30 {:error :x}]} (gen/fmap #(vector running % {:error :x}) gen-time)
  (fn [[j now error] out] (= out (assoc j :status :failed :finished-at now :error error))))
(contract log job/log s/Job
  {:blank [queued "  "] :line [queued "hello  "] :overflow [(assoc queued :log (vec (repeat 500 "old"))) "new"]}
  (gen/tuple gen-job gen/string-alphanumeric)
  (fn [[j line] out] (if (str/blank? line) (= j out) (and (= (str/trimr line) (last (:log out))) (= (min 500 (inc (count (:log j)))) (count (:log out)))))))
(contract terminal job/terminal? :boolean
  {:queued [queued] :success [(job/succeed running 30 {})] :failed [(job/fail running 30 {})]}
  (gen/fmap vector gen-job)
  (fn [[j] out] (= out (boolean (#{:succeeded :failed} (:status j))))))
(contract duration job/duration-ms [:maybe :int]
  {:queued [queued 50] :running [running 50] :done [(job/succeed running 30 {}) 50]}
  (gen/tuple gen-job gen-time)
  (fn [[j now] out] (= out (when (:started-at j) (- (or (:finished-at j) now) (:started-at j))))))
(contract mux job/mux-of [:maybe :keyword]
  {:none [queued] :soft [(assoc-in queued [:request :mux] "soft")]}
  (gen/fmap #(vector (assoc-in queued [:request :mux] %)) (gen/elements [nil "" "soft" "hard" "both"]))
  (fn [[j] out] (= out (when (seq (get-in j [:request :mux])) (keyword (get-in j [:request :mux]))))))
(contract format job/format-of :string
  {:default [queued] :vtt [(assoc-in queued [:request :format] "vtt")]}
  (gen/fmap #(vector (assoc-in queued [:request :format] %)) (gen/elements [nil "srt" "vtt"]))
  (fn [[j] out] (= out (or (get-in j [:request :format]) "srt"))))
(contract spec-inputs job/spec-inputs :map
  {:plain [queued nil] :mux [(assoc-in queued [:request :mux] "soft") "out.mp4"]}
  (gen/tuple gen-job (gen/elements [nil "out.mp4"]))
  (fn [[j video] out] (and (= (:id j) (:job-id out)) (= (get-in j [:request :source]) (:source out)) (= (when (job/mux-of j) video) (:output out)))))
(contract describe runner/describe s/JobView
  {:queued [queued 50] :failed [(job/fail running 30 {:error :x}) 50]}
  (gen/tuple gen-job gen-time)
  (fn [[j now] out] (and (= (:id j) (:id out)) (= (job/duration-ms j now) (:duration-ms out)) (= (str/join "\n" (:log j)) (:log out)))))

(contract resolve-port config/resolve-port [:maybe s/Port]
  {:asr ["ASR"] :mt ["mt"] :unknown ["no"] :nil [nil]}
  (gen/fmap vector (gen/elements [nil "ASR" "mt" "translate" "digest" "no"]))
  (fn [[word] out] (= out (get {"asr" :transcriber "mt" :translator "translate" :translator "digest" :comprehender} (some-> word str/lower-case)))))
(contract opts-key config/opts-key :keyword
  {:mt [:translator]} (gen/fmap vector (gen/elements config/provider-ports))
  (fn [[port] out] (= (name out) (str (name port) "-opts"))))
(contract clamp config/clamp-threads [:maybe :int]
  {:auto [:auto 8] :max ["max" 8] :low [0 8] :high [20 8] :bad ["bad" 8]}
  (gen/tuple (gen/one-of [gen/int (gen/elements [nil :auto "auto" :max "max" "bad"])]) (gen/choose 1 128))
  (fn [[n cores] out] (cond (contains? #{nil :auto "auto" "bad"} n) (nil? out) (#{:max "max"} n) (= cores out) :else (= out (max 1 (min n cores))))))
(def cfg {:providers {:translator :venice :comprehender :venice} :translator-opts {:secret-pass "key"} :registry {:translator {:venice {:offline false}}}})
(contract providers config/known-providers [:vector :keyword]
  {:mt [cfg :translator] :composer [{} :composer]}
  (gen/fmap #(vector cfg %) (gen/elements config/provider-ports))
  (fn [[c p] out] (= (set out) (if (= p :composer) (into #{:none :soft :hard} (keys (get-in c [:registry p]))) (set (keys (get-in c [:registry p])))))))
(contract ports config/ports-using [:vector s/Port]
  {:shared [cfg :venice] :absent [cfg :unknown]}
  (gen/fmap #(vector cfg %) (gen/elements [:venice :unknown]))
  (fn [[c provider] out] (= out (filterv #(= provider (get-in c [:providers %])) config/provider-ports))))
(contract secret-pass config/provider-secret-pass [:maybe :string]
  {:shared [cfg :venice] :absent [cfg :unknown]}
  (gen/fmap #(vector cfg %) (gen/elements [:venice :unknown]))
  (fn [[c provider] out] (= out (some #(get-in c [(config/opts-key %) :secret-pass]) (config/ports-using c provider)))))

(def inputs {:job-id "j" :source "film.mp4" :target "pt" :format "srt"})
(contract build spec/build-job-spec s/JobSpec
  {:single [inputs] :many [(assoc inputs :target " pt , en " :mux :soft :mux-langs "pt" :output "out.mp4")]}
  (gen/fmap #(vector (assoc inputs :target %)) (gen/elements ["pt" "en" "pt,en"]))
  (fn [[i] out] (and (= (:job-id i) (:job-id out)) (= (:source i) (:source out)) (= (first (str/split (:target i) #",")) (:target-language out)) (= "auto" (:source-language out)))))
(contract validate spec/validate s/Result
  {:valid [(spec/build-job-spec inputs)] :invalid [{}]}
  (gen/fmap vector (gen/elements [{} (spec/build-job-spec inputs)]))
  (fn [[i] out] (= (m/validate s/JobSpec i) (contains? out :ok))))
(defn conform-observation [value] {:in value :out (web/conform web/SubmitRequest value)})
(deftrifecta conform conform-observation
  {:golden-path "test/golden/conform.edn" :cases {:valid request :invalid {}}
   :gen (gen/elements [request {}])
   :pred (fn [{:keys [in out]}] (and (m/validate s/Result out) (= (m/validate s/SubmitRequest in) (contains? out :ok))))})
(contract subtitle output/subtitle-path :string
  {:default ["/tmp/film.mp4" "pt" "srt" nil] :override ["film.mp4" "pt" "vtt" "custom.vtt"]}
  (gen/fmap #(vector "/tmp/film.mp4" % "srt" nil) gen/string-alphanumeric)
  (fn [[_ lang fmt override] out] (= out (or override (str "/tmp/film." lang "." fmt)))) )
(contract video output/video-path :string
  {:video ["/tmp/film.mp4" "pt"]}
  (gen/fmap #(vector "/tmp/film.mp4" %) gen/string-alphanumeric)
  (fn [[_ lang] out] (= out (str "/tmp/film." lang ".mp4"))))
(contract resolve-dir engine/resolve-engine-dir [:maybe :string]
  {:env ["/engine" false] :sibling [nil true] :none [nil false]}
  (gen/tuple (gen/elements [nil "" "/engine"]) gen/boolean)
  (fn [[env exists] out] (= out (or (not-empty env) (when exists "../vtranslate-engine")))) )
(contract parse-result engine/parse-result s/Result
  {:ok ["{:ok {:rendered \"text\"}}" "" 0] :error ["{:error :x}" "" 1] :bad ["not edn" "oops" 2]}
  (gen/fmap #(vector (pr-str {:ok %}) "" 0) gen/string-alphanumeric)
  (fn [[stdout _ exit] out] (if (zero? exit) (= (edn/read-string stdout) out) (contains? out :error))))
(contract route routes/match s/Route
  {:root [:get "/"] :show [:get "/api/jobs/a"] :subtitle [:get "/api/jobs/a/subtitle"] :missing [:delete "/api/jobs"]}
  (gen/fmap #(vector :get (str "/api/jobs/" %)) (gen/such-that seq gen/string-alphanumeric))
  (fn [[method path] out] (if (and (= method :get) (str/starts-with? path "/api/jobs/")) (= (nth (str/split path #"/") 3) (get-in out [:params :id])) (or (nil? out) (keyword? (:action out))))))
(contract selection dialog/parse-selection [:maybe :string]
  {:nil [nil] :empty [""] :many [" /tmp/a |/tmp/b\n"]}
  (gen/fmap #(vector (str " /tmp/" % " |other\n")) gen/string-alphanumeric)
  (fn [[stdout] out] (= out (some-> stdout str/split-lines first (str/split #"\|") first str/trim not-empty))))
(contract interpret dialog/interpret s/Result
  {:chosen [{:exit 0 :out "/tmp/a\n" :err ""}] :empty [{:exit 0 :out "" :err ""}] :cancel [{:exit 1 :out "" :err ""}] :failure [{:exit 2 :out "" :err "bad"}]}
  (gen/fmap #(vector {:exit % :out "/tmp/a\n" :err "bad"}) (gen/choose 0 5))
  (fn [[p] out] (= (and (zero? (:exit p)) (some? (dialog/parse-selection (:out p)))) (contains? out :ok))))

;; Higher-order APIs use serializable input data to build injected predicates.
(defn available-observation [names] {:in names :out (mapv :id (dialog/available (set names)))})
(deftrifecta available available-observation
  {:golden-path "test/golden/available.edn" :cases {:none [] :all ["zenity" "kdialog" "yad" "osascript"]}
   :gen (gen/vector (gen/elements ["zenity" "kdialog" "yad" "osascript"]))
   :pred (fn [{:keys [in out]}] (and (m/validate [:vector :keyword] out) (= out (mapv :id (filter #((set in) (:binary %)) dialog/dialogs)))))})
(defn choose-observation [names] {:in names :out (:id (dialog/choose (set names)))})
(deftrifecta choose choose-observation
  {:golden-path "test/golden/choose.edn" :cases {:none [] :prefer ["yad" "zenity"]}
   :gen (gen/vector (gen/elements ["zenity" "kdialog" "yad"]))
   :pred (fn [{:keys [in out]}] (and (m/validate [:maybe :keyword] out) (= out (:id (first (filter #((set in) (:binary %)) dialog/dialogs))))))})
(defn command-observation [[id opts]] {:in [id opts] :out (dialog/command (first (filter #(= id (:id %)) dialog/dialogs)) opts)})
(deftrifecta command command-observation
  {:golden-path "test/golden/command.edn" :cases {:zenity [:zenity {}] :kdialog [:kdialog {:title " Hello " :dir "/tmp"}]}
   :gen (gen/tuple (gen/elements [:zenity :kdialog :yad :osascript]) (gen/return {}))
   :pred (fn [{[id _] :in out :out}] (and (m/validate [:vector :string] out) (= (name id) (first out)) (some #(str/includes? % dialog/default-title) out)))})
(contract pinned-sdeps cp/pinned-engine-sdeps :string
  {:pinned []} (gen/return [])
  (fn [_ out] (= ["-m" "vtranslate.engine.main"] (get-in (edn/read-string out) [:aliases :vtranslate-engine :main-opts]))))
(contract pinned-command cp/pinned-engine-command [:vector :string]
  {:pinned []} (gen/return [])
  (fn [_ out] (and (= "clojure" (first out)) (= (cp/pinned-engine-sdeps) (nth out 2)) (= "-M:vtranslate-engine" (last out)))))

(defn source-observation [catalog]
  (let [source (local/make-source {:vtranslate.cli/addon-aliases {:demo/addon catalog} :aliases {:addon {:extra-paths ["addon/src"]}}})]
    {:in catalog :out {:source? (ac/source? source) :checked? (contains? (ac/checked-source source) :ok) :aliases (ac/aliases-for source :demo/addon) :preset (ac/preset-for source :addon) :sdeps (some-> (cp/engine-sdeps source {:config {:addons [:demo/addon]}}) edn/read-string)}}))

(defn port-observation [[port valid?]]
  (let [[value predicate check] (case port
                                :classpath [ac/empty-source ac/source? ac/checked-source]
                                :engine [ep/unavailable ep/runner? ep/checked-runner]
                                :picker [fp/unavailable fp/picker? fp/checked-picker])
        x (when valid? value)]
    {:in [port valid?] :out {:satisfies? (predicate x) :ok? (contains? (check x) :ok)}}))
(deftrifecta port-checks port-observation
  {:golden-path "test/golden/port-checks.edn"
   :cases {:classpath [:classpath true] :engine [:engine true] :picker [:picker true] :invalid [:engine false]}
   :gen (gen/tuple (gen/elements [:classpath :engine :picker]) gen/boolean)
   :pred (fn [{[_ valid?] :in out :out}] (and (m/validate [:map [:satisfies? :boolean] [:ok? :boolean]] out) (= valid? (:satisfies? out) (:ok? out))))})
(defn native-observation [names]
  (let [present? (set names)]
    {:in names :out {:id (native/describe present?) :picker? (fp/picker? (native/make-picker present?))}}))
(deftrifecta native-contract native-observation
  {:golden-path "test/golden/native.edn" :cases {:none [] :zenity ["zenity"]}
   :gen (gen/vector (gen/elements ["zenity" "kdialog" "yad"]))
   :pred (fn [{:keys [in out]}] (and (m/validate [:map [:id [:maybe :keyword]] [:picker? :boolean]] out) (:picker? out) (= (:id out) (:id (dialog/choose (set in))))))})
(defn planning-observation [aliases]
  (with-redefs [cp/engine-alias-env (constantly nil)]
    (let [source (local/make-source {:vtranslate.cli/addon-aliases {:demo/a aliases} :aliases {:addon {:extra-paths ["addon/src"]}}})
          spec {:config {:addons [:demo/a]}}]
      {:in aliases :out {:aliases (cp/engine-aliases source spec) :sdeps (cp/engine-sdeps source spec) :command (cp/engine-command source spec)}})))
(deftrifecta planning planning-observation
  {:golden-path "test/golden/planning.edn" :cases {:none [] :addon [:addon] :duplicate [:addon :addon]}
   :gen (gen/vector (gen/return :addon) 0 5)
   :pred (fn [{:keys [in out]}] (and (m/validate [:map [:aliases :string] [:sdeps [:maybe :string]] [:command [:vector :string]]] out) (= (:aliases out) (if (seq in) ":ffmpeg:whisper-jni:addon:run" ":ffmpeg:whisper-jni:run")) (= (last (:command out)) (str "-M" (:aliases out))) (= (boolean (seq in)) (some? (:sdeps out)))))})
(deftrifecta classpath-source source-observation
  {:golden-path "test/golden/classpath-source.edn" :cases {:empty [] :addon [:addon]}
   :gen (gen/elements [[] [:addon]])
   :pred (fn [{:keys [in out]}] (and (m/validate [:map [:source? :boolean] [:checked? :boolean] [:aliases [:vector :keyword]] [:preset s/ClasspathPreset] [:sdeps [:maybe :map]]] out) (:source? out) (:checked? out) (= in (:aliases out)) (= (seq in) (some-> out :sdeps :aliases keys seq))))})

(deftest stub-contracts
  (let [lines (atom []) result (ep/run-spec ep/unavailable {} #(swap! lines conj %))]
    (is (m/validate s/Result result))
    (is (= :error/no-engine-runner (:error result)))
    (is (= ["no engine runner configured"] @lines)))
  (is (= :error/no-file-picker (:error (fp/pick fp/unavailable {})))))
