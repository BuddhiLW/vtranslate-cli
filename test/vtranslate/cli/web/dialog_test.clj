(ns vtranslate.cli.web.dialog-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.cli.web.adapters.native-dialog :as native]
            [vtranslate.cli.web.dialog :as sut]
            [vtranslate.cli.web.port.filepicker :as port]))

(defn- installed
  "A `present?` predicate over a fixed set of binaries."
  [& names]
  (comp (set names) str))

;; --- choosing ---------------------------------------------------------------

(deftest choose-takes-the-first-installed-dialog-in-preference-order
  (is (= :zenity  (:id (sut/choose (installed "zenity" "kdialog")))))
  (is (= :kdialog (:id (sut/choose (installed "kdialog" "yad")))))
  (is (= :yad     (:id (sut/choose (installed "yad")))))
  (is (= :osascript (:id (sut/choose (installed "osascript"))))))

(deftest choose-is-nil-on-a-host-with-no-dialog
  (is (nil? (sut/choose (installed))))
  (is (empty? (sut/available (installed)))))

(deftest every-registered-dialog-builds-an-argv-starting-with-its-binary
  (doseq [d sut/dialogs]
    (testing (str (:id d))
      (let [argv (sut/command d {})]
        (is (= (:binary d) (first argv)) "argv[0] is the binary the registry claims")
        (is (every? string? argv) "argv is all strings — a process takes no keywords")
        (is (some #(re-find #"Choose a video" %) argv) "the default title reaches the argv")))))

;; --- argv -------------------------------------------------------------------

(deftest a-starting-directory-reaches-the-argv-when-the-dialog-takes-one
  (is (some #{"--filename=/home/x/clips/"} (sut/command (first sut/dialogs) {:dir "/home/x/clips"})))
  (is (some #{"/home/x/clips"} (sut/command (second sut/dialogs) {:dir "/home/x/clips"}))))

(deftest a-blank-directory-is-omitted-rather-than-passed-empty
  (let [argv (sut/command (first sut/dialogs) {:dir ""})]
    (is (not-any? #(re-find #"--filename" %) argv))))

(deftest a-custom-title-replaces-the-default
  (is (some #{"Pick the video"} (sut/command (first sut/dialogs) {:title "Pick the video"})))
  (is (some #{sut/default-title} (sut/command (first sut/dialogs) {:title "   "}))
      "a blank title falls back rather than opening an untitled dialog"))

;; --- reading what the dialog said -------------------------------------------

(deftest parse-selection-takes-the-first-of-a-multi-selection
  (is (= "/a/b.mp4" (sut/parse-selection "/a/b.mp4\n")))
  (is (= "/a/b.mp4" (sut/parse-selection "/a/b.mp4|/a/c.mp4\n")))
  (is (nil? (sut/parse-selection "")))
  (is (nil? (sut/parse-selection "   \n")))
  (is (nil? (sut/parse-selection nil))))

(deftest interpret-maps-the-exit-contract-onto-the-railway
  (testing "a chosen file"
    (let [res (sut/interpret {:exit 0 :out "/a/b.mp4\n" :err ""})]
      (is (r/ok? res))
      (is (= "/a/b.mp4" (get-in res [:ok :path])))))

  (testing "a dismissed dialog is cancelled, not failed"
    (is (= :error/pick-cancelled (:error (sut/interpret {:exit 1 :out "" :err ""}))))
    (is (= :error/pick-cancelled (:error (sut/interpret {:exit 0 :out "" :err ""})))
        "exit 0 with no selection is still a cancellation"))

  (testing "a broken dialog fails loud and keeps stderr"
    (let [res (sut/interpret {:exit 127 :out "" :err "zenity: command not found\n"})]
      (is (= :error/pick-failed (:error res)))
      (is (= 127 (:exit res)))
      (is (= "zenity: command not found" (:message res))))))

;; --- the port contract ------------------------------------------------------

(defrecord StubPicker [path]
  port/IFilePicker
  (pick [_ _] (r/ok {:path path})))

(deftest the-unavailable-default-refuses-instead-of-hanging
  (is (port/picker? port/unavailable))
  (let [res (port/pick port/unavailable {})]
    (is (r/err? res))
    (is (= :error/no-file-picker (:error res)))))

(deftest checked-picker-admits-the-port-and-rejects-anything-else
  (is (r/ok? (port/checked-picker (->StubPicker "/a/b.mp4"))))
  (is (r/ok? (port/checked-picker port/unavailable)))
  (is (= :error/invalid-file-picker (:error (port/checked-picker {:pick identity})))
      "a plain map is not an implementation, however duck-typed"))

(deftest make-picker-degrades-to-the-refusing-default-on-a-bare-host
  (is (identical? port/unavailable (native/make-picker (installed)))
      "no dialog installed => the panel says so rather than shelling nothing")
  (let [p (native/make-picker (installed "zenity"))]
    (is (port/picker? p))
    (is (= :zenity (get-in p [:dialog :id]))))
  (is (nil? (native/describe (installed))))
  (is (= :zenity (native/describe (installed "zenity" "yad")))))
