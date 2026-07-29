(ns vtranslate.cli.engine-test
  "Classpath planning against a STUB IAddonClasspath. No test names a sibling
   repo or asserts a machine-local path: the stub supplies fictional addon ids
   and paths, so these assert the PLANNING RULES rather than one machine's
   configuration."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [vtranslate.cli.engine-classpath :as sut]
            [vtranslate.cli.port.addon-classpath :as port]))

;; --- the stub ---------------------------------------------------------------

(defrecord StubClasspath [catalog presets]
  port/IAddonClasspath
  (aliases-for [_ addon-id] (vec (get catalog addon-id)))
  (preset-for [_ alias] (get presets alias)))

(def stub
  (->StubClasspath {:acme/demo     [:addon-demo]
                    :acme/two-part [:addon-one :addon-two]}
                   {:addon-demo {:extra-paths ["stub/demo/src"]}
                    :addon-one  {:extra-paths ["stub/one/src"]}
                    :addon-two  {:extra-deps {'acme/two {:mvn/version "1.2.3"}}}}))

(defn- no-env [f]
  (with-redefs [sut/engine-alias-env (constantly nil)] (f)))

;; --- the port's own contract ------------------------------------------------

(deftest the-stub-satisfies-the-port
  (is (port/source? stub))
  (is (port/source? port/empty-source))
  (is (not (port/source? {:catalog {}})) "a bare map is not a source")
  (is (= :error/invalid-addon-classpath-source
         (:error (port/checked-source {:catalog {}})))
      "the smart ctor refuses a non-source loudly"))

(deftest empty-source-contributes-nothing
  (is (= [] (port/aliases-for port/empty-source :acme/demo)))
  (is (nil? (port/preset-for port/empty-source :addon-demo))
      "an unknown addon yields no path rather than a guessed one"))

;; --- planning ---------------------------------------------------------------

(deftest engine-aliases-preserve-default
  (no-env #(is (= ":ffmpeg:whisper-jni:run" (sut/engine-aliases stub {})))))

(deftest an-addon-id-contributes-its-aliases
  (no-env
   (fn []
     (testing "a single-alias addon"
       (is (= ":ffmpeg:whisper-jni:addon-demo:run"
              (sut/engine-aliases stub {:config {:addons [:acme/demo]}}))))
     (testing "an addon may contribute several aliases, in order"
       (is (= ":ffmpeg:whisper-jni:addon-one:addon-two:run"
              (sut/engine-aliases stub {:config {:addons [:acme/two-part]}}))))
     (testing "duplicates across addons collapse"
       (is (= ":ffmpeg:whisper-jni:addon-demo:run"
              (sut/engine-aliases stub {:config {:addons [:acme/demo :acme/demo]}})))))))

(deftest an-unknown-addon-contributes-nothing
  (no-env
   #(is (= ":ffmpeg:whisper-jni:run"
           (sut/engine-aliases stub {:config {:addons [:acme/never-heard-of-it]}}))
        "no source entry => no alias, never an invented path")))

(deftest an-explicit-alias-on-the-addon-map-wins-over-the-source
  (no-env
   #(is (= ":ffmpeg:whisper-jni:custom-addon:run"
           (sut/engine-aliases stub {:config {:addons [{:id :acme/demo
                                                        :classpath/aliases [:custom-addon]}]}}))
        "a spec that states its own aliases does not consult the source")))

(deftest selected-aliases-become-sdeps-presets
  (no-env
   (fn []
     (let [cmd   (sut/engine-command stub {:config {:addons [:acme/demo]}})
           sdeps (edn/read-string (nth cmd 2))]
       (is (= "-Sdeps" (nth cmd 1)))
       (is (= {:addon-demo {:extra-paths ["stub/demo/src"]}} (:aliases sdeps))
           "only the selected alias's preset is emitted, verbatim from the source"))
     (testing "a preset may carry :extra-deps rather than :extra-paths"
       (let [sdeps (edn/read-string
                    (nth (sut/engine-command stub {:config {:addons [:acme/two-part]}}) 2))]
         (is (= {'acme/two {:mvn/version "1.2.3"}}
                (get-in sdeps [:aliases :addon-two :extra-deps]))))))))

(deftest no-addons-means-no-sdeps-flag
  (no-env
   (fn []
     (is (nil? (sut/engine-sdeps stub {})))
     (is (= ["clojure" "-M:ffmpeg:whisper-jni:run"] (sut/engine-command stub {}))
         "the -Sdeps flag is omitted entirely when nothing contributes"))))

(deftest planning-defaults-to-contributing-nothing
  (no-env
   #(is (= ["clojure" "-M:ffmpeg:whisper-jni:run"]
           (sut/engine-command {:config {:addons [:acme/demo]}}))
        "the no-source arity plans as if no addon were configured")))

(deftest pinned-engine-command-resolves-engine-from-git
  (let [cmd   (sut/pinned-engine-command)
        sdeps (edn/read-string (nth cmd 2))]
    (is (= ["clojure" "-Sdeps"] (vec (take 2 cmd))))
    (is (= "-M:vtranslate-engine" (nth cmd 3)))
    (is (= {:git/tag "v0.1.0" :git/sha "cd7477e"}
           (get-in sdeps [:aliases :vtranslate-engine :extra-deps
                          'io.github.BuddhiLW/vtranslate-engine])))
    (is (contains? (get-in sdeps [:aliases :vtranslate-engine :extra-deps])
                   'io.github.givimad/whisper-jni))
    (is (= ["-m" "vtranslate.engine.main"]
           (get-in sdeps [:aliases :vtranslate-engine :main-opts])))))
