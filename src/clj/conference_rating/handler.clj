(ns conference-rating.handler
  (:require [compojure.core :refer [GET POST PUT DELETE context defroutes routes]]
            [compojure.route :refer [not-found resources]]
            [ring.middleware.defaults :refer [site-defaults wrap-defaults secure-api-defaults]]
            [ring.util.response :refer [created response redirect content-type charset]]
            [hiccup.core :refer [html]]
            [hiccup.util :refer [escape-html]]
            [ring.middleware.json :as json]
            [hiccup.page :refer [include-js include-css]]
            [prone.middleware :refer [wrap-exceptions]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.ratelimit :refer [wrap-ratelimit ip-limit]]
            [ring.middleware.ratelimit.local-atom :refer [local-atom-backend]]
            [environ.core :refer [env]]
            [conference-rating.db-handler :as db]
            [conference-rating.aggregator :as aggregator]
            [schema.core :as s]
            [ring.middleware.okta :as okta]
            [schema.coerce :as coerce]
            [clojure.string :as string]
            [onelog.core :as onelog]
            [conference-rating.schemas :as schemas]
            [clojure.walk :as walk])

  (:use ring.middleware.anti-forgery))

(defn home-page [api-key]
  (html
   [:html
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport"
             :content "width=device-width, initial-scale=1"}]
     [:title "conference voices"]
     (include-css "thirdparty/bootstrap-3.3.5/css/bootstrap.min.css")
     (include-css "thirdparty/bootstrap-3.3.5/css/bootstrap-theme.min.css")
     (include-css "css/reagent-forms.css")
     (include-css "css/site.css")
     [:link {:rel "icon" :type "image/png" :href "img/favicon.png"}]
     [:script (str "_anti_forgery_token=\"" *anti-forgery-token* "\"")]]
    [:body {:class "bg-body"}
     [:div#app
      [:h3 "ClojureScript has not been compiled!"]
      [:p "please run "
       [:b "lein figwheel"]
       " in order to start the compiler"]]
     (include-js "thirdparty/jquery-2.1.4.min.js")
     (include-js "thirdparty/bootstrap-3.3.5/js/bootstrap.min.js")
     (include-js (str "https://maps.googleapis.com/maps/api/js?key=" api-key "&libraries=places"))
     (include-js "js/app.js")]]))


(defn get-conference [conference-id db]
  (let [conference-info   (db/get-conference conference-id db)
        ratings           (db/get-ratings conference-id db)
        aggregate-ratings (aggregator/aggregate-ratings ratings)
        ratings-of-series (db/get-average-rating-for-series (:series conference-info) db)
        attendances       (db/get-attendances conference-id db)]
    (->
      conference-info
      (assoc  :aggregated-ratings aggregate-ratings)
      (assoc :average-series-rating ratings-of-series)
      (assoc :attendees (map :user attendances))
      (assoc :voters (map :user ratings)))))

(defn- complete-conferences [db conferences]
  (->> conferences
       (map :_id)
       (map #(get-conference % db))))

(defn get-conferences [db]
  (complete-conferences db (db/get-all-conferences db)))

(defn get-past-conferences [db current-page per-page]
  (complete-conferences db (db/get-past-conferences db current-page per-page)))

(defn get-future-conferences [db current-page per-page]
  (complete-conferences db (db/get-future-conferences db current-page per-page)))

(defn- escape-string [x]
  (if (string? x)
    (escape-html x)
    x))

(defn- sanitize [m]
  (walk/postwalk escape-string m))

(s/defn add-conference [conference :- schemas/Conference db]
  (let [add-result (db/add-conference (sanitize conference) db)
        id         (:_id add-result)]
    (created (str "/api/conferences/" id) add-result)))


(defn- okta-attribute [request key]
  (first (get-in request [:session :okta/attributes key])))

(defn- user-identity [request]
  {:email (get-in request [:session :okta/user])
   :firstName (okta-attribute request "firstName")
   :lastName (okta-attribute request "lastName")})

(def parse-rating (coerce/coercer schemas/Rating coerce/json-coercion-matcher))

(defn add-rating [conference-id request db]
  (let [complete (assoc (sanitize (:body request))
                   :conference-id conference-id
                   :user (user-identity request))
        rating (parse-rating complete)
        add-result (db/add-rating rating db)
        id (:_id add-result)]
    (created (str "/api/conferences/" conference-id "/ratings/" id) add-result)))

(defn matches-series [q]
  (fn [series]
    (.contains (string/lower-case series)
               (string/lower-case q))))

(defn series-suggestions [db q]
  (->> (db/get-all-conferences db)
       (map :series)
       (filter (complement string/blank?))
       (filter (matches-series q))
       (distinct)))

(defn- with-anti-forgery [handler]
  (if (env :dev)
    handler
    (wrap-anti-forgery handler)))

(defn- utf-json-response [original-response]
  (charset (content-type (response original-response) "application/json") "UTF-8"))

(defn get-conference-ratings [conference-id db]
  (utf-json-response
    (db/get-ratings conference-id db)))

(defn- get-past-conferences-handler [db params]
 {:items (get-past-conferences db (Long/parseLong (:current-page params)) (Long/parseLong (:per-page params)))
   :total-items (db/get-count-past-conferences db)})

(defn- get-future-conferences-handler [db params]
 {:items (get-future-conferences db (Long/parseLong (:current-page params)) (Long/parseLong (:per-page params)))
   :total-items (db/get-count-future-conferences db)})

(defn read-routes [db api-key]
  (routes
    (GET "/api/conferences" [] (utf-json-response (get-conferences db)))
    (GET "/api/conferences/past" {params :params} (utf-json-response (get-past-conferences-handler db params)))
    (GET "/api/conferences/future" {params :params} (utf-json-response (get-future-conferences-handler db params)))
    (GET "/api/conferences/:id" [id] (utf-json-response (get-conference id db)))
    (GET "/api/conferences/:id/ratings" [id] (get-conference-ratings id db))
    (GET "/api/series/suggestions" {params :params} (utf-json-response (series-suggestions  db (:q params))))
    (GET "/api/user/identity" request (utf-json-response (user-identity request)))
    (GET "/" [] (home-page api-key))))

(defn update-conference [id conference db]
  (let [update-result (db/update-conference-by-id id (sanitize conference) db)]
    (utf-json-response update-result)))

(defn add-attendance [conference-id request db]
  (let [attendance {:user          (user-identity request)
                    :conference-id conference-id}]
  (db/add-attendance attendance db)
  {:status  201
   :body    "OK"}))

(defn remove-attendance [conference-id request db]
  (let [attendance {:user          (user-identity request)
                    :conference-id conference-id}]
    (db/remove-attendance attendance db)
    {:status  204
     :body    "OK"}))


(defn write-routes [db]
  (wrap-ratelimit
    (routes
      (POST "/api/conferences/:id/ratings" [id :as request] (add-rating id request db))
      (POST "/api/conferences/" request (add-conference (:body request) db))
      (POST "/api/conferences/:id/attendance/self" [id :as request] (add-attendance id request db))
      (POST "/api/conferences/:id/unattendance/self" [id :as request] (remove-attendance id request db))
      (PUT "/api/conferences/:id/edit" [id :as request] (update-conference id (:body request) db))
      (DELETE "/api/conferences/:id" [id] (do (db/delete-conference-by-id id db) {:status  204
                                                                                  :headers {}})))
    {:limits [(ip-limit 100)]
     :backend (local-atom-backend (atom {}))}))

(defn all-routes [db api-key]
  (let [read-routes (read-routes db api-key)
        write-routes (write-routes db)]
    (routes
      read-routes
      write-routes)))

(defn anti-forgery-routes [db api-key]
  (with-anti-forgery
    (all-routes db api-key)))

(defn create-routes [db api-key]
  (let [anti-forgery-routes (anti-forgery-routes db api-key)]
    (routes
      okta/okta-routes
      (GET "/login" [] (redirect "/"))
      (resources "/")
      (GET "/css/reagent-forms.css" [] (response (-> "reagent-forms.css"
                                                     clojure.java.io/resource
                                                     slurp)))
      (context "" [] anti-forgery-routes)
      (not-found "Not Found"))))

(defn ring-settings [ssl-redirect-disabled]
  (-> secure-api-defaults
      (assoc-in [:security :ssl-redirect] (not ssl-redirect-disabled))
      (assoc-in [:security :frame-options] :deny)
      (assoc-in [:security :xss-protection :mode] :block)
      (assoc-in [:security :content-type-options] :nosniff)
      (assoc :proxy true)))

(defn prevent-open-redirect-through-relay-state [handler]
  (fn [request]
    (let [clean-request (-> request
                            (assoc-in [:params :RelayState] "/")
                            (assoc-in [:form-params :RelayState] "/"))]
      (handler clean-request))))

(defn wrap-dont-show-error-page [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (onelog/error (onelog/throwable e))
        {:status 500 :body "An error occurred" }))))

(defn app [db ssl-redirect-disabled api-key]
  (let [handler (-> (create-routes db api-key)
                    (prevent-open-redirect-through-relay-state)
                    (wrap-defaults (ring-settings ssl-redirect-disabled))
                    (json/wrap-json-response)
                    (json/wrap-json-body {:keywords? true}))]
    (if (env :dev)
      (-> handler
          wrap-exceptions
          wrap-reload)
      (-> handler
          wrap-dont-show-error-page
          ))))
