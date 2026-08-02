(ns vtranslate.cli.web.job
  "Pure job promoters: one function, one lifecycle decision. Every transition
   takes the current time as an argument, so nothing here reads a clock and the
   whole lifecycle is exercisable without one.
     make      - a submitted request becomes a queued Job.
     start     - queued -> running.
     succeed   - running -> succeeded, carrying the run summary.
     fail      - running -> failed, carrying the error.
     log       - append one diagnostic line.
     terminal? - has the job stopped moving?
     spec-of   - a Job's request as the engine's JobSpec inputs."
  (:require [clojure.string :as str]))

(def ^:private max-log-lines
  "Cap on retained diagnostic lines per job. A run that loops on a warning must
   not grow the store without bound; the newest lines are the useful ones."
  500)

(defn make
  "A queued Job for `request`, identified by `id`, submitted at `now`."
  [id request now]
  {:id           id
   :status       :queued
   :request      request
   :log          []
   :submitted-at now
   :started-at   nil
   :finished-at  nil
   :result       nil
   :error        nil})

(defn start
  "Mark `job` running as of `now`."
  [job now]
  (assoc job :status :running :started-at now))

(defn succeed
  "Mark `job` succeeded as of `now`, carrying `result` (the write summary)."
  [job now result]
  (assoc job :status :succeeded :finished-at now :result result))

(defn fail
  "Mark `job` failed as of `now`, carrying `error`."
  [job now error]
  (assoc job :status :failed :finished-at now :error error))

(defn log
  "Append `line` to `job`'s diagnostic log, keeping only the newest
   `max-log-lines`. A blank line is dropped."
  [job line]
  (if (str/blank? (str line))
    job
    (update job :log (fn [lines]
                       (let [appended (conj (or lines []) (str/trimr (str line)))
                             overflow (- (count appended) max-log-lines)]
                         (if (pos? overflow)
                           (subvec appended overflow)
                           appended))))))

(defn terminal?
  "True when `job` has stopped moving."
  [job]
  (contains? #{:succeeded :failed} (:status job)))

(defn duration-ms
  "How long `job` has been running, or ran for. nil before it started."
  [job now]
  (when-let [started (:started-at job)]
    (- (or (:finished-at job) now) started)))

(defn mux-of
  "`job`'s requested mux mode as a keyword, or nil when it asked for none."
  [job]
  (let [mux (get-in job [:request :mux])]
    (when-not (str/blank? (str mux)) (keyword mux))))

(defn format-of
  "`job`'s requested subtitle format, defaulted the way the CLI defaults it."
  [job]
  (or (get-in job [:request :format]) "srt"))

(defn spec-inputs
  "The `build-job-spec` inputs `job` describes. `video-output` is the muxed sink
   the caller resolved; it is carried only when a mux was actually requested, so
   this stays a pure restatement of the request."
  [job video-output]
  (let [{:keys [source target source-language mux-langs]} (:request job)
        mux (mux-of job)]
    (cond-> {:job-id          (:id job)
             :source          source
             :target          target
             :source-language source-language
             :format          (format-of job)
             :mux             mux}
      (not (str/blank? (str mux-langs))) (assoc :mux-langs mux-langs)
      (and mux video-output) (assoc :output video-output))))
