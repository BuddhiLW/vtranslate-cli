(ns vtranslate.cli.web.runner
  "Boundary: take a queued job to a terminal state. Everything effectful the run
   needs — the engine, the clock, the writer — arrives in the deps map, so the
   whole path is exercisable with records and no subprocess.
     run-job! - synchronous; drives one job to succeeded or failed.
     submit!  - queue a request and run it off the caller's thread."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.cli.job-spec :as js]
            [vtranslate.cli.output :as out]
            [vtranslate.cli.web.job :as job]
            [vtranslate.cli.web.port.engine :as port]
            [vtranslate.cli.web.store :as store]))

(defn- log! [store id line]
  (store/apply! store id job/log line))

(defn- outputs-for
  "Resolve where `j`'s artifacts go. => {:subtitle path :video path-or-nil}."
  [j]
  (let [{:keys [source target]} (:request j)
        fmt (job/format-of j)]
    {:subtitle (out/subtitle-path source target fmt nil)
     :video    (when (job/mux-of j) (out/video-path source target))}))

(defn- run-validated
  "Run `spec` through the injected engine and write what it renders.
   => (r/ok summary) | (r/err ...)."
  [{:keys [engine]} store id spec subtitle-path]
  (-> (port/run-spec engine spec #(log! store id %))
      (out/write-rendered subtitle-path)))

(defn run-job!
  "Drive the job stored under `id` to a terminal state. Blocks.
   => the terminal job."
  [{:keys [clock] :as deps} store id]
  (let [j       (store/fetch store id)
        {:keys [subtitle video]} (outputs-for j)
        _       (store/apply! store id job/start (clock))
        outcome (r/let-ok [spec (js/validate (js/build-job-spec (job/spec-inputs j video)))]
                  (run-validated deps store id spec subtitle))]
    (if (r/ok? outcome)
      (store/apply! store id job/succeed (clock) (:ok outcome))
      (store/apply! store id job/fail (clock) (dissoc outcome :err)))))

(defn- guarded-run!
  "run-job! with a Throwable guard, so a crash inside the worker thread still
   moves the job to :failed instead of leaving it running forever."
  [{:keys [clock] :as deps} store id]
  (try
    (run-job! deps store id)
    (catch Throwable t
      (log! store id (str "runner crashed: " (.getMessage t)))
      (store/apply! store id job/fail (clock)
                    (r/err :error/runner-crashed {:message (.getMessage t)})))))

(defn submit!
  "Queue `request` as a new job and start it on its own thread.
   `next-id` supplies the id so the caller controls identity in tests.
   => the queued job (already stored, probably not yet started)."
  [{:keys [clock next-id spawn] :as deps} store request]
  (let [id     (next-id)
        queued (store/put! store (job/make id request (clock)))
        start  (or spawn #(future (%)))]
    (start (fn [] (guarded-run! deps store id)))
    queued))

(defn describe
  "A job rendered for the wire: the stored map plus its elapsed duration and a
   log joined for display. Errors are stringified — a Result carries keywords
   and exception data that must not decide the shape of the JSON."
  [j now]
  (-> j
      (assoc :duration-ms (job/duration-ms j now))
      (update :error #(when % (pr-str %)))
      (update :log #(str/join "\n" %))))
