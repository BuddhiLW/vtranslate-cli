(ns vtranslate.cli.command-test
  "Unit tests for the pure Bonzai command-tree core: resolution, argument
   binding/validation, and help rendering. No IO — fast, runs on babashka."
  (:require [clojure.test :refer [deftest is testing]]
            [vtranslate.cli.command :as cmd]))

(def tree
  {:name "vtranslate"
   :summary "test root"
   :commands [{:name "translate" :aliases #{"tr"}
               :summary "translate"
               :args [{:name :source :desc "src"} {:name :target-lang :desc "lang"}]
               :opts {:source-lang {:default "en" :desc "src lang"}
                      :format {:default "srt" :enum #{"srt" "vtt"} :desc "fmt"}}
               :run (fn [_] 0)}
              {:name "version" :summary "version" :run (fn [_] 0)}]})

(def translate (first (:commands tree)))

(deftest resolve-command-test
  (testing "resolves a subcommand by name, returns the trailing tokens"
    (let [{:keys [cmd path rest]} (cmd/resolve-command tree ["translate" "a.mp4" "pt"])]
      (is (= "translate" (:name cmd)))
      (is (= ["vtranslate" "translate"] path))
      (is (= ["a.mp4" "pt"] rest))))
  (testing "resolves by alias"
    (is (= "translate" (-> (cmd/resolve-command tree ["tr" "a.mp4"]) :cmd :name))))
  (testing "an unknown leading token leaves resolution at the current node"
    (let [{:keys [cmd rest]} (cmd/resolve-command tree ["bogus"])]
      (is (= "vtranslate" (:name cmd)))
      (is (= ["bogus"] rest))))
  (testing "no args resolves to the root branch node"
    (is (= "vtranslate" (-> (cmd/resolve-command tree []) :cmd :name)))))

(deftest parse-test
  (testing "binds positionals and applies opt defaults"
    (let [r (cmd/parse translate ["a.mp4" "pt-BR"])]
      (is (cmd/ok? r))
      (is (= {:source "a.mp4" :target-lang "pt-BR"} (:args (:ok r))))
      (is (= {:source-lang "en" :format "srt"} (:opts (:ok r))))))
  (testing "options override defaults"
    (is (= "vtt" (get-in (cmd/parse translate ["a" "b" "--format" "vtt"]) [:ok :opts :format])))
    (is (= "es"  (get-in (cmd/parse translate ["a" "b" "--source-lang" "es"]) [:ok :opts :source-lang]))))
  (testing "missing required positional is an error naming the arg"
    (let [r (cmd/parse translate ["only-one"])]
      (is (:error r))
      (is (re-find #"target-lang" (:error r)))))
  (testing "too many positionals is an error"
    (is (:error (cmd/parse translate ["a" "b" "c"]))))
  (testing "a value outside an :enum is an error"
    (is (:error (cmd/parse translate ["a" "b" "--format" "ass"]))))
  (testing "an undeclared option is an error"
    (is (:error (cmd/parse translate ["a" "b" "--frobnicate" "x"])))))

(deftest help-text-test
  (testing "branch help lists subcommands"
    (is (re-find #"COMMANDS:" (cmd/help-text tree ["vtranslate"]))))
  (testing "leaf help lists args + options with defaults"
    (let [h (cmd/help-text translate ["vtranslate" "translate"])]
      (is (re-find #"ARGS:" h))
      (is (re-find #"OPTIONS:" h))
      (is (re-find #"default: srt" h)))))
