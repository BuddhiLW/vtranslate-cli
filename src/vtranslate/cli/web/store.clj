(ns vtranslate.cli.web.store
  "In-memory job store. State lives in one atom so every mutation is a pure
   promoter applied under `swap!` — the promoters are in web.job and are
   testable without this ns.

   Deliberately not durable: the panel drives a local engine checkout, and a
   job that outlives the server process has nothing to resume into."
  (:require [vtranslate.cli.web.job :as job]))

(defn make-store
  "A fresh empty store."
  []
  (atom {:jobs {} :order []}))

(defn put!
  "Insert `j`, remembering submission order. => the stored job."
  [store j]
  (swap! store (fn [s]
                 (-> s
                     (assoc-in [:jobs (:id j)] j)
                     (update :order conj (:id j)))))
  j)

(defn fetch
  "The job stored under `id`, or nil."
  [store id]
  (get-in @store [:jobs id]))

(defn apply!
  "Apply pure promoter `f` to the job under `id` (extra `args` passed through).
   A missing id is a no-op. => the updated job, or nil."
  [store id f & args]
  (-> (swap! store (fn [s]
                     (if (get-in s [:jobs id])
                       (apply update-in s [:jobs id] f args)
                       s)))
      (get-in [:jobs id])))

(defn recent
  "Stored jobs, newest submission first, at most `limit`."
  [store limit]
  (let [{:keys [jobs order]} @store]
    (into [] (comp (map jobs) (remove nil?) (take limit)) (reverse order))))

(defn active?
  "True when any stored job is still queued or running."
  [store]
  (boolean (some (complement job/terminal?) (vals (:jobs @store)))))
