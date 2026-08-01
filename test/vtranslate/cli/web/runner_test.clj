(ns vtranslate.cli.web.runner-test
  "The runner is exercised over RECORDS implementing IEngineRunner — no
   subprocess, no JVM engine, no network, no wall clock. Records rather than
   reify: babashka's `satisfies?` does not recognise a reify'd protocol impl.

   Jobs are run inline (a synchronous `spawn`) so the assertions are
   deterministic; the production path swaps in a future."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.cli.web.port.engine :as port]
            [vtranslate.cli.web.runner :as sut]
            [vtranslate.cli.web.store :as store]))

;; --- stubs ------------------------------------------------------------------

(defrecord RenderingEngine [rendered seen]
  port/IEngineRunner
  (run-spec [_ spec log!]
    (vreset! seen spec)
    (log! "loading model")
    (log! "translating 26 cues")
    (r/ok {:rendered rendered :job {:id (:job-id spec) :state :completed}})))

(defrecord RefusingEngine []
  port/IEngineRunner
  (run-spec [_ _ log!]
    (log! "provider refused")
    (r/err :error/translator-unavailable {:provider :venice})))

(defrecord ExplodingEngine []
  port/IEngineRunner
  (run-spec [_ _ _] (throw (ex-info "socket closed" {}))))

(defrecord SilentEngine []
  port/IEngineRunner
  (run-spec [_ _ _] (r/ok {:job {:id "x"}})))          ; ok, but rendered nothing

;; --- harness ----------------------------------------------------------------

(defn- deps
  "Collaborators for a deterministic run: a fixed id, a ticking clock, and an
   inline spawn so submit! finishes before it returns."
  [engine]
  (let [t (volatile! 0)]
    {:engine  engine
     :clock   #(vswap! t + 1000)
     :next-id (constantly "job-1")
     :spawn   #(%)}))

(defn- in-temp-dir
  "Call `f` with a temp source file path; always clean up."
  [f]
  (let [dir (fs/create-temp-dir {:prefix "vt-web"})
        src (str (fs/path dir "clip.mp4"))]
    (try
      (spit src "not really a video")
      (f (str dir) src)
      (finally (fs/delete-tree dir)))))

(defn- run!
  "Submit one request through `engine` and return the terminal job."
  [engine request]
  (let [st (store/make-store)]
    (sut/submit! (deps engine) st request)
    (store/fetch st "job-1")))

;; --- tests ------------------------------------------------------------------

(deftest a-rendered-run-succeeds-and-writes-the-subtitle
  (in-temp-dir
   (fn [_dir src]
     (let [seen (volatile! nil)
           j    (run! (->RenderingEngine "1\n00:00:01,000 --> 00:00:02,000\nOlá\n" seen)
                      {:source src :target "pt" :format "srt"})
           out  (get-in j [:result :output])]
       (is (= :succeeded (:status j)))
       (is (= (str (fs/strip-ext src) ".pt.srt") out) "sidecar lands beside the source")
       (is (fs/exists? out) "the rendered subtitle reached disk")
       (is (= "1\n00:00:01,000 --> 00:00:02,000\nOlá\n" (slurp out)))
       (testing "the engine received the spec the request describes"
         (is (= src (:source @seen)))
         (is (= "pt" (:target-language @seen)))
         (is (= :format/srt (:format @seen))))
       (testing "diagnostics streamed while it ran"
         (is (= ["loading model" "translating 26 cues"] (:log j))))
       (testing "it was timed"
         (is (pos? (:duration-ms (sut/describe j 99999)))))))))

(deftest an-engine-error-fails-the-job-and-keeps-the-log
  (in-temp-dir
   (fn [_dir src]
     (let [j (run! (->RefusingEngine) {:source src :target "pt"})]
       (is (= :failed (:status j)))
       (is (= :error/translator-unavailable (:error (:error j))))
       (is (= ["provider refused"] (:log j)))
       (is (nil? (:result j)))))))

(deftest a-throwing-engine-still-reaches-a-terminal-state
  (in-temp-dir
   (fn [_dir src]
     (let [j (run! (->ExplodingEngine) {:source src :target "pt"})]
       (is (= :failed (:status j)) "a crash must not leave the job running forever")
       (is (some #(re-find #"socket closed" %) (:log j)))))))

(deftest an-ok-result-with-nothing-rendered-is-a-failure
  (in-temp-dir
   (fn [_dir src]
     (let [j (run! (->SilentEngine) {:source src :target "pt"})]
       (is (= :failed (:status j))
           "an engine that reports success but renders nothing must not look shipped")
       (is (= :error/no-rendered-subtitle (:error (:error j))))))))

(deftest an-unconfigured-runner-refuses-rather-than-pretending
  (in-temp-dir
   (fn [_dir src]
     (let [j (run! port/unavailable {:source src :target "pt"})]
       (is (= :failed (:status j)))
       (is (= :error/no-engine-runner (:error (:error j))))))))

(deftest a-mux-request-asks-the-engine-for-a-video-sink
  (in-temp-dir
   (fn [_dir src]
     (let [seen (volatile! nil)]
       (run! (->RenderingEngine "cues" seen)
             {:source src :target "es" :format "srt" :mux "hard"})
       (is (= :hard (get-in @seen [:config :composer])))
       (is (= (str (fs/strip-ext src) ".es.mp4") (:output @seen)))))))

(deftest describe-renders-a-job-for-the-wire
  (let [j (-> (run! (->RefusingEngine) {:source "/nope" :target "pt"}))]
    (is (string? (:error (sut/describe j 5000)))
        "a Result carries keywords the JSON layer must not have to understand")
    (is (string? (:log (sut/describe j 5000)))
        "the log is joined for display")))
