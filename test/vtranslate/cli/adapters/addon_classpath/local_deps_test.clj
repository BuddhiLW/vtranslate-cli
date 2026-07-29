(ns vtranslate.cli.adapters.addon-classpath.local-deps-test
  "The adapter is exercised over TEMP files it writes itself — it never reads
   the developer's own local.deps.edn, so the suite is identical on every
   machine and in CI where that file does not exist."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [vtranslate.cli.adapters.addon-classpath.local-deps :as sut]
            [vtranslate.cli.port.addon-classpath :as port]))

(defn- with-temp-edn
  "Write `content` to a temp file, call `f` with its path, always delete it."
  [content f]
  (let [file (fs/create-temp-file {:prefix "local-deps" :suffix ".edn"})]
    (try
      (spit (fs/file file) content)
      (f (str file))
      (finally (fs/delete-if-exists file)))))

(def sample
  (pr-str {:deps {'acme/lib {:local/root "../somewhere"}}
           :aliases {:addon-demo {:extra-paths ["stub/demo/src"]}}
           :vtranslate.cli/addon-aliases {:acme/demo [:addon-demo]}}))

(deftest reads-catalog-and-presets-from-the-file
  (with-temp-edn sample
    (fn [path]
      (let [source (sut/from-file path)]
        (is (port/source? source) "the adapter satisfies the port")
        (is (= [:addon-demo] (port/aliases-for source :acme/demo)))
        (is (= {:extra-paths ["stub/demo/src"]} (port/preset-for source :addon-demo)))
        (is (= [] (port/aliases-for source :acme/unknown)))
        (is (nil? (port/preset-for source :addon-unknown)))))))

(deftest a-missing-file-yields-a-source-that-contributes-nothing
  (let [source (sut/from-file "definitely/not/a/real/local.deps.edn")]
    (is (port/source? source))
    (is (= [] (port/aliases-for source :acme/demo))
        "a clean checkout plans with no addons rather than failing")
    (is (nil? (port/preset-for source :addon-demo)))))

(deftest malformed-content-is-treated-as-no-configuration
  (testing "unparseable EDN"
    (with-temp-edn "{:aliases {:x " ; deliberately truncated
      (fn [path]
        (is (= {} (sut/read-local-deps path)))
        (is (= [] (port/aliases-for (sut/from-file path) :acme/demo))))))
  (testing "valid EDN that is not a map"
    (with-temp-edn "[1 2 3]"
      (fn [path] (is (= {} (sut/read-local-deps path)))))))

(deftest the-file-carries-the-private-values-so-source-need-not
  (with-temp-edn sample
    (fn [path]
      (is (contains? (sut/read-local-deps path) sut/addon-aliases-key)
          "the addon catalog is data in a gitignored file, not a def in src"))))
