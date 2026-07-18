(ns vtranslate.cli.config-doctor-test
  (:require [clojure.test :refer [deftest is]]
            [vtranslate.cli.config :as sut]))

(def cfg
  {:providers {:transcriber :openai-whisper
               :translator :venice
               :comprehender :none
               :composer :hard}
   :transcriber-opts {:model "whisper-1" :model-path "models/whisper.bin"}
   :translator-opts {:model "gemma-4-uncensored"}
   :addons [{:addon :vtranslate/context}]
   :registry {:transcriber {:openai-whisper {:api-url "https://api.openai.com/v1/audio/transcriptions"
                                             :secret-env "OPENAI_API_KEY"
                                             :default-model "whisper-1"}}
              :translator {:venice {:api-url "https://api.venice.ai/api/v1/chat/completions"
                                    :secret-env "VENICE_API_KEY"
                                    :default-model "default-model"}}
              :comprehender {:none {:offline true}}
              :composer {:hard {:offline true}}}})

(deftest provider-diagnostic-reports-model-path-and-secret-state
  (with-redefs [sut/env-set? (fn [env-name] (= "VENICE_API_KEY" env-name))]
    (let [asr (sut/provider-diagnostic cfg :transcriber)
          mt (sut/provider-diagnostic cfg :translator)]
      (is (= :openai-whisper (:active asr)))
      (is (= "whisper-1" (:model asr)))
      (is (= "models/whisper.bin" (:model-path asr)))
      (is (false? (:secret-set? asr)))
      (is (= "gemma-4-uncensored" (:model mt)))
      (is (true? (:secret-set? mt))))))

(deftest doctor-report-summarizes-provider-surface
  (with-redefs [sut/env-set? (constantly false)
                sut/config-path (constantly "/tmp/vtranslate/config.edn")]
    (let [report (sut/doctor-report cfg)
          composer (last (:providers report))]
      (is (= "/tmp/vtranslate/config.edn" (:config-path report)))
      (is (= [{:addon :vtranslate/context}] (:addons report)))
      (is (= [:transcriber :translator :comprehender :composer]
             (mapv :port (:providers report))))
      (is (= [:none :soft :hard] (:known composer)))
      (is (:configured? composer)))))
