(ns vtranslate.cli.web.routes
  "Pure routing: an HTTP method + path becomes an action keyword and its params.
   No store, no IO, no handler — which is what lets the whole URL surface be
   asserted as a table.
     match - [method path] -> {:action kw :params {...}} | nil (404)."
  (:require [clojure.string :as str]))

(defn- segments
  "Path split into non-empty segments."
  [path]
  (into [] (remove str/blank?) (str/split (str path) #"/")))

(defn match
  "The action `method` on `path` names.
   => {:action kw :params {...}} | nil when nothing matches."
  [method path]
  (let [segs (segments path)]
    (case [method segs]
      [:get []]                     {:action :ui/index      :params {}}
      [:get ["api" "config"]]       {:action :config/show   :params {}}
      [:post ["api" "config"]]      {:action :config/patch  :params {}}
      [:get ["api" "jobs"]]         {:action :jobs/list     :params {}}
      [:post ["api" "jobs"]]        {:action :jobs/create   :params {}}
      [:post ["api" "pick"]]        {:action :source/pick   :params {}}
      [:get ["api" "health"]]       {:action :health/show   :params {}}
      (let [under-jobs? (and (= :get method)
                             (<= 3 (count segs))
                             (= ["api" "jobs"] (subvec segs 0 2)))]
        (cond
          (and under-jobs? (= 3 (count segs)))
          {:action :jobs/show :params {:id (nth segs 2)}}

          (and under-jobs? (= 4 (count segs)) (= "subtitle" (nth segs 3)))
          {:action :jobs/subtitle :params {:id (nth segs 2)}}

          :else nil)))))
