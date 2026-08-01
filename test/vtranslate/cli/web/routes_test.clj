(ns vtranslate.cli.web.routes-test
  "Routing is pure, so the whole URL surface is asserted as a table."
  (:require [clojure.test :refer [deftest is testing]]
            [vtranslate.cli.web.routes :as sut]))

(deftest every-served-url-maps-to-its-action
  (doseq [[method path expected]
          [[:get  "/"                        {:action :ui/index :params {}}]
           [:get  "/api/health"              {:action :health/show :params {}}]
           [:get  "/api/config"              {:action :config/show :params {}}]
           [:post "/api/config"              {:action :config/patch :params {}}]
           [:get  "/api/jobs"                {:action :jobs/list :params {}}]
           [:post "/api/jobs"                {:action :jobs/create :params {}}]
           [:get  "/api/jobs/web-1"          {:action :jobs/show :params {:id "web-1"}}]
           [:get  "/api/jobs/web-1/subtitle" {:action :jobs/subtitle :params {:id "web-1"}}]]]
    (is (= expected (sut/match method path))
        (str method " " path))))

(deftest trailing-and-doubled-slashes-do-not-invent-routes
  (is (= {:action :jobs/list :params {}} (sut/match :get "/api/jobs/")))
  (is (= {:action :ui/index :params {}} (sut/match :get "//")))
  (is (= {:action :jobs/show :params {:id "x"}} (sut/match :get "/api/jobs//x//"))))

(deftest anything-else-is-a-404
  (testing "unknown paths"
    (is (nil? (sut/match :get "/api/unknown")))
    (is (nil? (sut/match :get "/api/jobs/web-1/video")))
    (is (nil? (sut/match :get "/api/jobs/a/b/c"))))
  (testing "a known path under the wrong method"
    (is (nil? (sut/match :delete "/api/jobs")))
    (is (nil? (sut/match :post "/api/jobs/web-1")))
    (is (nil? (sut/match :post "/")))))
