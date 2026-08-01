(ns vtranslate.cli.main-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [babashka.cli :as cli]
            [hive-dsl.result :as r]
            [vtranslate.cli.main :as main]
            [vtranslate.cli.output :as out]))

(def ^:private table @#'main/table)
(def ^:private emit @#'main/emit)

;; Path derivation moved to vtranslate.cli.output when the HTTP adapter began
;; sharing it; the assertions below are unchanged from when it lived in main.
(def ^:private output-path out/subtitle-path)
(def ^:private video-output-path out/video-path)

(defn- dispatch
  "Route argv through the SHIPPED babashka.cli table; returns the exit code the
   handler hands to System/exit, with stdout/stderr swallowed."
  [argv]
  (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)]
    (cli/dispatch table (vec argv))))

(deftest output-path-derives-beside-source-when-omitted
  (testing "an explicit output path wins verbatim"
    (is (= "/tmp/out.srt" (output-path "/v/movie.mp4" "pt-BR" "srt" "/tmp/out.srt"))))
  (testing "otherwise derived beside the source as <stem>.<target>.<fmt>"
    (is (= "/v/movie.pt-BR.srt" (output-path "/v/movie.mp4" "pt-BR" "srt" nil)))
    (is (= "./movie.en.vtt" (output-path "movie.mp4" "en" "vtt" nil)))))

(deftest video-output-path-is-mp4-beside-source
  (is (= "/v/movie.pt-BR.mp4" (video-output-path "/v/movie.mp4" "pt-BR")))
  (is (= "./clip.es.mp4" (video-output-path "clip.mkv" "es"))))

(deftest emit-maps-result-to-exit-code
  (testing "ok string prints raw, exit 0"
    (let [out (java.io.StringWriter.)]
      (is (= 0 (binding [*out* out] (emit (r/ok "hello")))))
      (is (= "hello\n" (str out)))))
  (testing "ok nil prints nothing, exit 0"
    (let [out (java.io.StringWriter.)]
      (is (= 0 (binding [*out* out] (emit (r/ok nil)))))
      (is (= "" (str out)))))
  (testing "ok data pretty-prints, exit 0"
    (binding [*out* (java.io.StringWriter.)]
      (is (= 0 (emit (r/ok {:a 1}))))))
  (testing "err => exit 1, structured Result on stderr"
    (let [err (java.io.StringWriter.)]
      (is (= 1 (binding [*err* err] (emit (r/err :error/boom {:why 1})))))
      (is (str/includes? (str err) ":error/boom")))))

(deftest emit-echoes-engine-stderr-raw
  (testing "a carried :err stream is echoed raw, not only escaped inside the map"
    (let [err  (java.io.StringWriter.)
          code (binding [*err* err]
                 (emit (r/err :error/engine-unparsable
                              {:exit 127 :out "" :err "Execution error: boom"})))]
      (is (= 1 code))
      (is (str/includes? (str err) "Execution error: boom"))
      (is (not (str/includes? (str err) ":err "))))))

(deftest dispatch-guards-run-before-shelling-the-engine
  (testing "run with no source => exit 1 (missing-source, no subprocess)"
    (is (= 1 (dispatch ["run"]))))
  (testing "run with a source but no target => exit 1 (missing-target)"
    (is (= 1 (dispatch ["run" "movie.mp4"])))))

(deftest dispatch-routes-help-and-unknown-to-help
  (testing "bare invocation => help handler, exit 0"
    (is (= 0 (dispatch []))))
  (testing "an unknown command falls through to the [] catch-all help, exit 0"
    (is (= 0 (dispatch ["totally" "bogus"])))))

(deftest dispatch-reaches-config-path-handler
  (testing "config path prints a non-empty user-config path, exit 0"
    (let [out  (java.io.StringWriter.)
          code (binding [*out* out *err* (java.io.StringWriter.)]
                 (cli/dispatch table ["config" "path"]))]
      (is (= 0 code))
      (is (str/ends-with? (str/trim (str out)) "vtranslate/config.edn")))))
