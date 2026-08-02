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
   [:target-languages {:optional true} [:vector NonBlankString]]
   [:mux-languages    {:optional true} [:vector NonBlankString]]
   [:asset-kind {:optional true} MediaKind]
   [:output     {:optional true} NonBlankString]])

(defn- split-langs
  "A comma-separated language operand -> a vector of non-blank codes."
  [s]
  (into [] (comp (map str/trim) (remove str/blank?)) (str/split (str s) #",")))

(defn build-job-spec
  "Pure: argv-derived inputs -> a JobSpec transport map (unvalidated). `format` is
   a plain string promoted to a :format/* keyword. `mux` is nil|:soft|:hard|:both;
   when set it fills :config's :composer and, together with `output`, an :output
   video sink. `target` and `mux-langs` accept comma-separated lists: several
   targets become :target-languages (the first stays :target-language), and
   :mux-languages names which of them get a video. Leaves :asset-kind unset."
  [{:keys [job-id source target source-language format mux mux-langs output]}]
  (let [targets (split-langs target)
        muxes   (split-langs mux-langs)]
    (cond-> {:job-id          job-id
             :source          source
             :source-language (or source-language "auto")
             :target-language (first targets)
             :format          (keyword "format" format)
             :config          (if mux {:composer mux} {})}
      (> (count targets) 1) (assoc :target-languages targets)
      (seq muxes)          (assoc :mux-languages muxes)
      (and mux output)     (assoc :output output))))

(defn validate
  "JobSpec -> (r/ok spec) when it conforms to JobSpec, else
   (r/err :error/invalid-job-spec {:explain <humanized-errors> :spec spec})."
  [spec]
  (if (m/validate JobSpec spec)
    (r/ok spec)
    (r/err :error/invalid-job-spec
           {:explain (me/humanize (m/explain JobSpec spec))
            :spec    spec})))
