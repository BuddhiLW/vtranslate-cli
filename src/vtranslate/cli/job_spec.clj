(ns vtranslate.cli.job-spec
  "Transport contract for the cross-process JobSpec the CLI marshals to the engine
   subprocess (argv-derived data -> EDN on stdin, one map). Owns the malli schema,
   a pure builder, and a boundary validator.
     JobSpec        - the closed transport map the engine's -main reads.
     build-job-spec - pure: argv-derived inputs -> a JobSpec map (unvalidated).
     validate       - JobSpec -> (r/ok spec) | (r/err :error/invalid-job-spec ...)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [hive-dsl.result :as r]))

(def ^:private NonBlankString
  [:and [:string {:min 1}]
   [:fn {:error/message "must not be blank"} (complement str/blank?)]])

(def MediaKind
  [:enum :media/video :media/subtitle])

(def SubtitleFormat
  [:and :keyword
   [:fn {:error/message "must be a :format/* keyword"}
    (fn [k] (and (qualified-keyword? k) (= "format" (namespace k))))]])

(def ConfigSpec
  [:map [:composer {:optional true} [:enum :soft :hard :both]]])

(def JobSpec
  [:map {:closed true}
   [:job-id          NonBlankString]
   [:source          NonBlankString]
   [:source-language NonBlankString]
   [:target-language NonBlankString]
   [:format          SubtitleFormat]
   [:config          ConfigSpec]
   [:asset-kind {:optional true} MediaKind]
   [:output     {:optional true} NonBlankString]])

(defn build-job-spec
  "Pure: argv-derived inputs -> a JobSpec transport map (unvalidated). `format` is
   a plain string promoted to a :format/* keyword. `mux` is nil|:soft|:hard|:both;
   when set it fills :config's :composer and, together with `output`, an :output
   video sink. Leaves :asset-kind unset."
  [{:keys [job-id source target source-language format mux output]}]
  (cond-> {:job-id          job-id
           :source          source
           :source-language (or source-language "auto")
           :target-language target
           :format          (keyword "format" format)
           :config          (if mux {:composer mux} {})}
    (and mux output) (assoc :output output)))

(defn validate
  "JobSpec -> (r/ok spec) when it conforms to JobSpec, else
   (r/err :error/invalid-job-spec {:explain <humanized-errors> :spec spec})."
  [spec]
  (if (m/validate JobSpec spec)
    (r/ok spec)
    (r/err :error/invalid-job-spec
           {:explain (me/humanize (m/explain JobSpec spec))
            :spec    spec})))
