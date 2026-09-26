(ns vtranslate.cli.engine-boundary-test
  (:require [clojure.test :refer [deftest testing is]]
            [hive-dsl.result :as r]
            [vtranslate.cli.engine :as engine]
            [vtranslate.cli.engine-classpath :as classpath]))

(def ^:private parse-result @#'engine/parse-result)

(deftest parse-result-passes-engine-ok-through
  (testing "a well-formed (r/ok ...) on stdout under exit 0 passes through"
    (let [res (parse-result (pr-str (r/ok {:rendered "SUB"})) "" 0)]
      (is (r/ok? res))
      (is (= "SUB" (get-in res [:ok :rendered]))))))

(deftest parse-result-passes-engine-err-through
  (testing "the engine's own (r/err ...) passes through despite stderr noise"
    (let [res (parse-result (pr-str (r/err :error/domain {:why 1})) "log noise" 1)]
      (is (r/err? res))
      (is (= :error/domain (:error res))))))

(deftest parse-result-threads-stderr-on-crash
  (testing "empty stdout + nonzero exit => engine-unparsable carrying :err (not dropped)"
    (let [diag "Execution error: engine dir not found\n"
          res  (parse-result "" diag 127)]
      (is (r/err? res))
      (is (= :error/engine-unparsable (:error res)))
      (is (= 127 (:exit res)))
      (is (= diag (:err res))))))

(deftest addon-warnings-are-picked-out-of-the-engines-stderr
  ;; A run succeeds without an addon that did not load; before this, a
  ;; successful Result dropped stderr, so the only trace of that was timing.
  (let [err (str "SLF4J: no binding\n"
                 "[vtranslate] addon :acme/private-addon did not load: :addon/unknown-id: ...\n"
                 "[vtranslate] 1 core adapter(s) unavailable on this classpath: x\n"
                 "[vtranslate] addon acme.other.addon did not load: :addon/load-failed\n")]
    (is (= ["[vtranslate] addon :acme/private-addon did not load: :addon/unknown-id: ..."
            "[vtranslate] addon acme.other.addon did not load: :addon/load-failed"]
           (engine/addon-warnings err))))
  (is (= [] (engine/addon-warnings nil)) "no stderr, no warnings"))

(deftest parse-result-flags-ok-contradicting-nonzero-exit
  (testing "a parsable ok but nonzero exit => engine-failed carrying :err"
    (let [res (parse-result (pr-str (r/ok {:x 1})) "partial crash" 3)]
      (is (r/err? res))
      (is (= :error/engine-failed (:error res)))
      (is (= "partial crash" (:err res))))))

(deftest parse-result-unwraps-tagged-engine-records
  (testing "a tagged record nested in the engine's ok payload is read as its plain map"
    (let [line "{:ok {:rendered \"SUB\" :job #vtranslate.engine.domain.job.TranslationJob{:id \"j1\" :state :job/completed}}}"
          res  (parse-result line "" 0)]
      (is (r/ok? res))
      (is (= "SUB" (get-in res [:ok :rendered])))
      (is (= {:id "j1" :state :job/completed} (get-in res [:ok :job]))))))

(deftest resolve-engine-dir-prefers-env-override
  (testing "env override wins even when the sibling checkout exists"
    (is (= "/opt/engine" (engine/resolve-engine-dir "/opt/engine" true)))))

(deftest resolve-engine-dir-falls-back-to-existing-sibling
  (testing "no env override + existing sibling => sibling dir"
    (is (= "../vtranslate-engine" (engine/resolve-engine-dir nil true)))
    (is (= "../vtranslate-engine" (engine/resolve-engine-dir "" true)))))

(deftest resolve-engine-dir-yields-nil-without-local-checkout
  (testing "no env override + missing sibling => nil (pinned git fallback)"
    (is (nil? (engine/resolve-engine-dir nil false)))))

(deftest engine-invocation-uses-local-checkout-when-resolvable
  (with-redefs [engine/engine-dir (constantly "/opt/engine")
                classpath/engine-alias-env (constantly nil)]
    (let [{:keys [dir command]} (engine/engine-invocation {})]
      (is (= "/opt/engine" dir))
      (is (= "-M:ffmpeg:whisper-jni:run" (last command))))))

(deftest engine-invocation-falls-back-to-pinned-coordinate
  (with-redefs [engine/engine-dir (constantly nil)]
    (let [{:keys [dir command]} (engine/engine-invocation {})]
      (is (nil? dir))
      (is (= "-Sdeps" (nth command 1)))
      (is (= "-M:vtranslate-engine" (last command))))))