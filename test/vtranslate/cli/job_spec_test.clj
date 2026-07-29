(ns vtranslate.cli.job-spec-test
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [hive-dsl.result :as r]
            [vtranslate.cli.job-spec :as js]
            [vtranslate.cli.engine-classpath :as classpath]))

(def ^:private plain-inputs
  {:job-id "cli-42" :source "/v/movie.mkv" :target "pt-BR"
   :source-language nil :format "srt" :mux nil :output nil})

(deftest build-job-spec-plain-golden
  (testing "a plain (no-mux) run builds exactly this transport map"
    (is (= {:job-id          "cli-42"
            :source          "/v/movie.mkv"
            :source-language "auto"
            :target-language "pt-BR"
            :format          :format/srt
            :config          {}}
           (js/build-job-spec plain-inputs))))
  (testing "an explicit source-language wins over the auto default"
    (is (= "en" (:source-language (js/build-job-spec (assoc plain-inputs :source-language "en")))))))

(deftest build-job-spec-mux-golden
  (testing "a --mux run carries the composer in :config and the mp4 sink in :output"
    (is (= {:job-id          "cli-42"
            :source          "/v/movie.mkv"
            :source-language "en"
            :target-language "pt-BR"
            :format          :format/vtt
            :config          {:composer :soft}
            :output          "/v/movie.pt-BR.mp4"}
           (js/build-job-spec {:job-id "cli-42" :source "/v/movie.mkv" :target "pt-BR"
                               :source-language "en" :format "vtt"
                               :mux :soft :output "/v/movie.pt-BR.mp4"}))))
  (testing "--mux both carries :both through unchanged"
    (is (= {:composer :both}
           (:config (js/build-job-spec (assoc plain-inputs :mux :both :output "/v/movie.pt-BR.mp4")))))))

(deftest build-job-spec-omits-asset-kind
  (testing "the CLI never sets :asset-kind; the engine infers media kind from the source"
    (is (not (contains? (js/build-job-spec plain-inputs) :asset-kind)))
    (is (not (contains? (js/build-job-spec (assoc plain-inputs :mux :hard :output "x.mp4"))
                        :asset-kind)))))

(deftest validate-passes-a-good-spec-through
  (testing "validate returns (r/ok spec), unwrapping to the same map"
    (let [spec (js/build-job-spec plain-inputs)
          res  (js/validate spec)]
      (is (r/ok? res))
      (is (= spec (:ok res))))))

(deftest validate-rejects-malformed-specs
  (let [good (js/build-job-spec plain-inputs)]
    (testing "a disallowed key => invalid-job-spec (closed transport map)"
      (let [res (js/validate (assoc good :bogus 1))]
        (is (r/err? res))
        (is (= :error/invalid-job-spec (:error res)))
        (is (= {:bogus ["disallowed key"]} (:explain res)))))
    (testing "a blank required field is rejected"
      (is (r/err? (js/validate (assoc good :target-language "  ")))))
    (testing "a non :format/* format is rejected"
      (is (r/err? (js/validate (assoc good :format :srt))))
      (is (r/err? (js/validate (assoc good :format "srt")))))
    (testing "an unknown composer is rejected"
      (is (r/err? (js/validate (assoc good :config {:composer :bogus})))))
    (testing ":both is an accepted composer"
      (is (r/ok? (js/validate (assoc good :config {:composer :both})))))))

(deftest asset-kind-is-optional-and-constrained
  (let [good (js/build-job-spec plain-inputs)]
    (testing "an explicit MediaKind is accepted"
      (is (r/ok? (js/validate (assoc good :asset-kind :media/video))))
      (is (r/ok? (js/validate (assoc good :asset-kind :media/subtitle)))))
    (testing "a bogus asset-kind is rejected"
      (is (r/err? (js/validate (assoc good :asset-kind :media/audio)))))))

(deftest jobspec-schema-classification-table
  (let [good (js/build-job-spec plain-inputs)]
    (doseq [[label valid? spec]
            [["minimal plain spec"        true  good]
             ["with mux composer"         true  (assoc good :config {:composer :hard} :output "a.mp4")]
             ["with both composer"        true  (assoc good :config {:composer :both} :output "a.mp4")]
             ["explicit video asset-kind" true  (assoc good :asset-kind :media/video)]
             ["missing :config"           false (dissoc good :config)]
             ["missing :target-language"  false (dissoc good :target-language)]
             ["blank :source"             false (assoc good :source "")]
             ["string :format"            false (assoc good :format "srt")]
             ["extra key"                 false (assoc good :bogus 1)]]]
      (testing label
        (is (= valid? (m/validate js/JobSpec spec)))))))

(deftest engine-command-argv-for-built-spec
  (testing "a built + validated JobSpec is shelled as `clojure -M<engine aliases>`, no -Sdeps"
    (let [spec (:ok (js/validate (js/build-job-spec plain-inputs)))]
      (is (= ["clojure" "-M:ffmpeg:whisper-jni:run"] (classpath/engine-command spec)))
      (is (nil? (classpath/engine-sdeps spec)))))
  (testing "a --mux job shells the same argv (the composer is engine-side, not a classpath alias)"
    (let [spec (:ok (js/validate (js/build-job-spec (assoc plain-inputs :mux :soft :output "a.mp4"))))]
      (is (= ["clojure" "-M:ffmpeg:whisper-jni:run"] (classpath/engine-command spec))))))
