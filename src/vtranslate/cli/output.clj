(ns vtranslate.cli.output
  "Where a finished job's artifacts land, and how the engine Result becomes
   files on disk. Shared by both driving adapters (argv and HTTP).
     subtitle-path - pure: source + target + format -> sidecar path.
     video-path    - pure: source + target -> muxed .mp4 path.
     write-rendered - effect: engine Result -> files, => Result<summary>."
  (:require [babashka.fs :as fs]
            [hive-dsl.result :as r]))

(defn subtitle-path
  "Sidecar subtitle path for a job: `output` when given, else beside `source` as
   <stem>.<target>.<fmt>. => string."
  [source target fmt output]
  (or output
      (let [p      (fs/path source)
            parent (fs/parent p)
            stem   (fs/strip-ext (fs/file-name p))]
        (str (fs/path (or parent (fs/path ".")) (str stem "." target "." fmt))))))

(defn video-path
  "mp4 sink beside `source` (<stem>.<target>.mp4) for a muxed/burned video.
   => string."
  [source target]
  (let [p      (fs/path source)
        parent (fs/parent p)
        stem   (fs/strip-ext (fs/file-name p))]
    (str (fs/path (or parent (fs/path ".")) (str stem "." target ".mp4")))))

(defn- spit-dirs! [path content]
  (when-let [parent (fs/parent (fs/path path))]
    (fs/create-dirs parent))
  (spit path content))

(defn write-rendered
  "Write the rendered subtitle carried by engine `result` to `output`.
   An err Result passes through untouched.
   => (r/ok {:output path :job {...} :output-video ...})
    | (r/err :error/no-rendered-subtitle {...})."
  [result output]
  (if (r/err? result)
    result
    (let [rendered (get-in result [:ok :rendered])
          video    (get-in result [:ok :output-video])]
      (if (string? rendered)
        (do
          (spit-dirs! output rendered)
          (r/ok (cond-> {:output output
                         :job    (select-keys (get-in result [:ok :job])
                                              [:id :state :target-language :subtitle-id])}
                  video (assoc :output-video video))))
        (r/err :error/no-rendered-subtitle {:output output})))))

(defn write-all-rendered
  "Write every rendered subtitle the engine produced. More than one
   [:ok :outputs] entry => one sidecar per language, named
   <stem>.<lang>.<fmt> beside `base-source`; a single output (or the flat
   subtitle-ingress shape) defers to write-rendered at `output`.
   An err Result passes through untouched. => Result<summary>."
  [result base-source fmt output]
  (let [outputs (get-in result [:ok :outputs])]
    (if (and (r/ok? result) (> (count outputs) 1))
      (let [written (mapv (fn [{:keys [target-language rendered]}]
                            (let [path (subtitle-path base-source target-language fmt nil)]
                              (spit-dirs! path rendered)
                              {:language target-language :output path}))
                          outputs)]
        (r/ok {:outputs written
               :job     (select-keys (get-in result [:ok :job])
                                     [:id :state :target-language :subtitle-id])}))
      (write-rendered result output))))
