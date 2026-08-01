(ns vtranslate.cli.config-secret-test
  "Where a provider's API key resolves from. The pass probe is INJECTED, so the
   precedence is decidable without a pass store, a `pass` binary, or a real
   secret — the suite is identical on every machine and in CI."
  (:require [clojure.test :refer [deftest is testing]]
            [vtranslate.cli.config :as sut]))

(def ^:private no-pass (constantly false))
(defn- pass-has [& paths] (comp boolean (set paths)))

(def ^:private set-env
  "An env var this process really does export, so env-set? sees it."
  "PATH")

(def ^:private unset-env "VTRANSLATE_DEFINITELY_UNSET_KEY")

(deftest a-resolvable-pass-path-wins-over-the-env-var
  ;; The reason this exists: a stale key left in the environment must not
  ;; shadow the real one in the store, so the panel and the engine agree.
  (is (= :pass (sut/secret-source set-env "Venice/key" (pass-has "Venice/key")))))

(deftest the-env-var-is-the-fallback
  (testing "a pass path that does not resolve falls through"
    (is (= :env (sut/secret-source set-env "Venice/missing" no-pass))))
  (testing "no pass path configured at all"
    (is (= :env (sut/secret-source set-env nil no-pass)))))

(deftest nothing-resolvable-is-nil-not-a-guess
  (is (nil? (sut/secret-source unset-env nil no-pass)))
  (is (nil? (sut/secret-source unset-env "Venice/missing" no-pass)))
  (is (nil? (sut/secret-source nil nil no-pass))))

(deftest a-pass-path-alone-is-enough
  (testing "a provider with no env var at all still reports a key"
    (is (= :pass (sut/secret-source nil "Venice/key" (pass-has "Venice/key"))))))

(deftest the-probe-never-returns-the-secret
  ;; pass-entry? answers a yes/no question; a missing entry (or a missing
  ;; `pass`) must be false rather than a throw.
  (is (false? (sut/pass-entry? nil)))
  (is (false? (sut/pass-entry? "")))
  (is (false? (sut/pass-entry? "   ")))
  (is (boolean? (sut/pass-entry? "vtranslate/definitely-not-a-real-entry"))))

(deftest the-diagnostic-reports-a-pass-backed-key-as-set
  ;; The bug this replaced: a working pass-backed key was reported "unset"
  ;; because only the env var was consulted.
  (let [cfg {:providers {:translator :venice}
             :translator-opts {:secret-pass "Venice/key"}
             :registry {:translator {:venice {:secret-env unset-env
                                              :api-url "https://example"}}}}
        d   (with-redefs [sut/pass-entry? (pass-has "Venice/key")]
              (sut/provider-diagnostic cfg :translator))]
    (is (= :pass (:secret-source d)))
    (is (true? (:secret-set? d)))
    (is (= "Venice/key" (:secret-pass d))))
  (testing "and still reports a genuinely absent key as unset"
    (let [cfg {:providers {:translator :venice}
               :registry {:translator {:venice {:secret-env unset-env}}}}
          d   (with-redefs [sut/pass-entry? no-pass]
                (sut/provider-diagnostic cfg :translator))]
      (is (nil? (:secret-source d)))
      (is (false? (:secret-set? d))))))
