(ns vtranslate.cli.engine-boundary-test
  (:require [clojure.test :refer [deftest testing is]]
            [hive-dsl.result :as r]
            [vtranslate.cli.engine :as engine]))

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