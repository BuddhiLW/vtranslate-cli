(ns vtranslate.cli.config-threads-test
  "The CPU knob. A value written here is read by the engine as
   [:transcriber-opts :threads], so the CLI must never persist one the engine
   would have to defend itself against."
  (:require [clojure.test :refer [deftest is testing]]
            [vtranslate.cli.config :as config]))

(deftest auto-clears-the-setting
  (testing "nil, :auto and \"auto\" all mean 'let the adapter decide'"
    (is (nil? (config/clamp-threads nil 22)))
    (is (nil? (config/clamp-threads :auto 22)))
    (is (nil? (config/clamp-threads "auto" 22)))))

(deftest max-takes-every-core
  (is (= 22 (config/clamp-threads "max" 22)))
  (is (= 22 (config/clamp-threads :max 22))))

(deftest a-number-is-honoured-when-it-fits
  (is (= 8 (config/clamp-threads 8 22)))
  (is (= 8 (config/clamp-threads "8" 22)) "argv arrives as a string")
  (is (= 8 (config/clamp-threads " 8 " 22)) "and may carry whitespace"))

(deftest a-number-is-clamped-to-the-machine
  (testing "over the top"
    (is (= 22 (config/clamp-threads 999 22)))
    (is (= 22 (config/clamp-threads "999" 22))))
  (testing "at or below zero — a request that would never finish"
    (is (= 1 (config/clamp-threads 0 22)))
    (is (= 1 (config/clamp-threads -4 22)))))

(deftest garbage-is-rejected-rather-than-silently-defaulted
  (is (nil? (config/clamp-threads "lots" 22)))
  (is (nil? (config/clamp-threads "" 22)))
  (testing "and the caller can tell that apart from an explicit auto"
    (is (nil? (config/clamp-threads "auto" 22)))))

(deftest available-cores-is-positive
  (is (pos? (config/available-cores))))
