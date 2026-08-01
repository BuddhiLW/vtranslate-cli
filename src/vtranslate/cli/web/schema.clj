(ns vtranslate.cli.web.schema
  "Malli value objects for the control panel. The schemas are the contract the
   HTTP boundary validates against, and the single source the job promoters and
   their tests agree on.
     SubmitRequest - what the browser may post to start a job.
     JobStatus     - the lifecycle states a job moves through.
     Job           - a stored job, including its live stderr log.
     ConfigPatch   - what the browser may change about provider routing."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [hive-dsl.result :as r]))

(def NonBlankString
  [:and [:string {:min 1}]
   [:fn {:error/message "must not be blank"} (complement str/blank?)]])

(def SubtitleFormat
  [:enum "srt" "vtt"])

(def MuxMode
  [:enum "soft" "hard" "both"])

(def SubmitRequest
  "A job the browser asks for. Permissive at the boundary: everything the CLI
   defaults is optional here too, so the form can post only what it knows."
  [:map {:closed true}
   [:source          NonBlankString]
   [:target          NonBlankString]
   [:source-language {:optional true} [:maybe :string]]
   [:format          {:optional true} [:maybe SubtitleFormat]]
   [:mux             {:optional true} [:maybe MuxMode]]])

(def JobStatus
  [:enum :queued :running :succeeded :failed])

(def Job
  "A job as the store holds it. Times are epoch millis supplied by the caller —
   no promoter reads a clock, which is what makes the transitions testable."
  [:map {:closed true}
   [:id           NonBlankString]
   [:status       JobStatus]
   [:request      SubmitRequest]
   [:log          [:vector :string]]
   [:submitted-at :int]
   [:started-at   [:maybe :int]]
   [:finished-at  [:maybe :int]]
   [:result       [:maybe :any]]
   [:error        [:maybe :any]]])

(def ConfigPatch
  "What the panel may change. Either a provider re-route — `port` is the CLI's
   port word (asr | mt | digest) and `provider` is validated against the registry
   by config/use-provider! — or an ASR thread budget, clamped by
   config/set-asr-threads!."
  [:multi {:dispatch (fn [m] (if (contains? m :threads) :threads :provider))}
   [:threads  [:map {:closed true}
               [:threads [:or :int NonBlankString]]]]
   [:provider [:map {:closed true}
               [:port     NonBlankString]
               [:provider NonBlankString]]]])

(defn conform
  "`value` if it validates against `schema`, else a humanized err.
   => (r/ok value) | (r/err :error/invalid-request {:explain ... :value ...})."
  [schema value]
  (if (m/validate schema value)
    (r/ok value)
    (r/err :error/invalid-request
           {:explain (me/humanize (m/explain schema value))
            :value   value})))
