(ns vtranslate.cli.test-runner
  (:require [clojure.java.io :as io] [clojure.test :as t]))
(defn -main [& _]
  (let [nses (->> (file-seq (io/file "test"))
                  (filter #(and (.isFile %) (.endsWith (.getName %) "_test.clj")))
                  (map #(with-open [r (java.io.PushbackReader. (io/reader %))] (second (read r))))
                  sort vec)]
    (doseq [n nses] (require n))
    (let [result (apply t/run-tests nses)]
      (shutdown-agents)
      (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))))
