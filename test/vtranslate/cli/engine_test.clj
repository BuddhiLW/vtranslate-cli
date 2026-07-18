(ns vtranslate.cli.engine-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [vtranslate.cli.engine-classpath :as sut]))

(deftest engine-aliases-preserve-default
  (with-redefs [sut/engine-alias-env (constantly nil)]
    (is (= ":ffmpeg:whisper-jni:run" (sut/engine-aliases {})))))

(deftest addon-keyword-adds-classpath-preset
  (with-redefs [sut/engine-alias-env (constantly nil)]
    (is (= ":ffmpeg:whisper-jni:addon-context:run"
           (sut/engine-aliases {:config {:addons [:vtranslate/context]}})))))

(deftest addon-map-can-declare-classpath-aliases
  (with-redefs [sut/engine-alias-env (constantly nil)]
    (is (= ":ffmpeg:whisper-jni:custom-addon:run"
           (sut/engine-aliases {:config {:addons [{:ns 'private.addon
                                                   :classpath/aliases [:custom-addon]}]}})))))

(deftest addon-keyword-defines-sdeps-alias
  (with-redefs [sut/engine-alias-env (constantly nil)]
    (let [cmd (sut/engine-command {:config {:addons [:vtranslate/context]}})
          sdeps (edn/read-string (nth cmd 2))]
      (is (= "-Sdeps" (nth cmd 1)))
      (is (= {:addon-context {:extra-paths ["../addon-context/src"]}}
             (:aliases sdeps))))))