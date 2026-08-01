(ns vtranslate.cli.web.job-test
  "The lifecycle promoters are pure and clock-free, so every transition is
   asserted directly against the Job schema rather than through a running job."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [vtranslate.cli.web.job :as sut]
            [vtranslate.cli.web.schema :as schema]))

(def request {:source "/v/clip.mp4" :target "pt" :format "srt"})

(defn- valid? [j] (m/validate schema/Job j))

(deftest a-new-job-is-queued-and-conforms
  (let [j (sut/make "job-1" request 1000)]
    (is (valid? j) (pr-str (m/explain schema/Job j)))
    (is (= :queued (:status j)))
    (is (= 1000 (:submitted-at j)))
    (is (nil? (:started-at j)))
    (is (not (sut/terminal? j)))))

(deftest transitions-stay-within-the-schema
  (let [queued (sut/make "job-1" request 1000)]
    (testing "start records when it began and is still not terminal"
      (let [running (sut/start queued 1500)]
        (is (valid? running))
        (is (= :running (:status running)))
        (is (= 1500 (:started-at running)))
        (is (not (sut/terminal? running)))))
    (testing "succeed carries the write summary and is terminal"
      (let [done (-> queued (sut/start 1500) (sut/succeed 4500 {:output "/v/clip.pt.srt"}))]
        (is (valid? done))
        (is (sut/terminal? done))
        (is (= "/v/clip.pt.srt" (get-in done [:result :output])))))
    (testing "fail carries the error and is terminal"
      (let [failed (-> queued (sut/start 1500) (sut/fail 2000 {:error :boom}))]
        (is (valid? failed))
        (is (sut/terminal? failed))
        (is (= {:error :boom} (:error failed)))))))

(deftest duration-spans-start-to-finish
  (let [queued (sut/make "job-1" request 1000)]
    (is (nil? (sut/duration-ms queued 9999)) "a queued job has not started")
    (is (= 500 (sut/duration-ms (sut/start queued 1500) 2000))
        "a running job is measured against now")
    (is (= 3000 (sut/duration-ms (-> queued (sut/start 1500) (sut/succeed 4500 {})) 9999))
        "a finished job is measured against its finish, not now")))

(deftest the-log-appends-and-is-bounded
  (testing "blank lines are dropped and trailing space trimmed"
    (let [j (-> (sut/make "j" request 0) (sut/log "  ") (sut/log "") (sut/log "real  "))]
      (is (= ["real"] (:log j)))))
  (testing "the newest lines survive a flood"
    (let [j (reduce sut/log (sut/make "j" request 0) (map str (range 600)))]
      (is (= 500 (count (:log j))))
      (is (= "599" (last (:log j))))
      (is (= "100" (first (:log j))) "the oldest lines are the ones dropped"))))

(deftest spec-inputs-restate-the-request
  (testing "a sidecar job carries no video sink"
    (let [j (sut/make "j" request 0)]
      (is (= {:job-id "j" :source "/v/clip.mp4" :target "pt"
              :source-language nil :format "srt" :mux nil}
             (sut/spec-inputs j nil)))))
  (testing "format defaults the way the CLI defaults it"
    (is (= "srt" (sut/format-of (sut/make "j" (dissoc request :format) 0)))))
  (testing "a mux request promotes to a keyword and takes the video sink"
    (let [j (sut/make "j" (assoc request :mux "hard") 0)]
      (is (= :hard (sut/mux-of j)))
      (is (= "/v/clip.pt.mp4" (:output (sut/spec-inputs j "/v/clip.pt.mp4"))))))
  (testing "no mux means no video sink even when one is offered"
    (let [j (sut/make "j" (assoc request :mux "") 0)]
      (is (nil? (sut/mux-of j)))
      (is (nil? (:output (sut/spec-inputs j "/v/clip.pt.mp4")))))))
