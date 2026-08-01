(ns vtranslate.cli.config-provider-secret-test
  "A key belongs to the PROVIDER, but the engine and the context addon each read
   it per port. These pin the sharing rules that hide that split — all pure, over
   config maps, so nothing touches the user's real config file."
  (:require [clojure.test :refer [deftest is testing]]
            [vtranslate.cli.config :as sut]))

(def ^:private venice-on-both
  {:providers {:translator :venice :comprehender :venice :transcriber :whisper-local}
   :translator-opts {:secret-pass "Venice/key"}})

(deftest opts-key-matches-what-the-engine-reads
  (is (= :translator-opts (sut/opts-key :translator)))
  (is (= :comprehender-opts (sut/opts-key :comprehender)))
  (is (= :transcriber-opts (sut/opts-key :transcriber))))

(deftest ports-using-finds-every-port-on-a-provider
  (is (= [:translator :comprehender] (sut/ports-using venice-on-both :venice)))
  (is (= [:transcriber] (sut/ports-using venice-on-both :whisper-local)))
  (testing "a provider nothing is routed to"
    (is (= [] (sut/ports-using venice-on-both :deepl)))))

(deftest a-provider-key-is-found-through-any-port-using-it
  ;; This is what lets the digest port inherit the translator's Venice key.
  (is (= "Venice/key" (sut/provider-secret-pass venice-on-both :venice)))
  (testing "only from a port actually using that provider"
    (is (nil? (sut/provider-secret-pass venice-on-both :whisper-local)))
    (is (nil? (sut/provider-secret-pass venice-on-both :deepl))))
  (testing "nil when no port has configured one"
    (is (nil? (sut/provider-secret-pass
               {:providers {:translator :venice}} :venice)))))

(deftest the-key-is-read-from-the-port-that-has-it-not-guessed
  ;; provider-secret-pass answers "what key does this provider use", so it must
  ;; not report a key configured on a port pointed at a DIFFERENT provider.
  (let [cfg {:providers {:translator :openrouter :comprehender :venice}
             :translator-opts {:secret-pass "openrouter/key"}}]
    (is (= "openrouter/key" (sut/provider-secret-pass cfg :openrouter)))
    (is (nil? (sut/provider-secret-pass cfg :venice))
        "venice must not borrow openrouter's entry")))
