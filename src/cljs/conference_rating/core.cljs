(ns conference-rating.core
  (:require [reagent.core :as reagent :refer [atom]]
            [reagent.session :as session]
            [secretary.core :as secretary :include-macros true]
            [conference-rating.history :as history]
            [conference-rating.add-conference-page.add-conference-page :as add-conference]
            [conference-rating.backend :as backend]
            [conference-rating.conference-detail-page.conference-detail-page :as conference]
            [conference-rating.add-rating-page.add-rating-page :as add-rating]
            [conference-rating.conference-list-page.conference-list-page :as conference-list]
            [conference-rating.user-info :as user-info]
            [cljsjs.typeahead-bundle])
    (:import goog.History))

;; -------------------------
;; Views

(defn current-page []
  [:div [(session/get :current-page)]])

(defn load-future-conferences-success-handler [conferences total-conferences]
  (reset! conference-list/displayed-future-conferences conferences)
  (reset! conference-list/total-future-conferences total-conferences))

(defn load-past-conferences-success-handler [conferences total-conferences]
  (reset! conference-list/displayed-past-conferences conferences)
  (reset! conference-list/total-past-conferences total-conferences))

(secretary/defroute "/" []
                    (backend/load-future-conferences 1 load-future-conferences-success-handler)
                    (backend/load-past-conferences 1 load-past-conferences-success-handler)
                    (session/put! :current-page #'conference-list/conferences-page))

(secretary/defroute "/add-conference" []
                    (session/put! :current-page #'add-conference/add-conference-page))

(secretary/defroute "/conferences/:id/edit" [id]
                    (reset! add-conference/edit-conference-data nil)
                    (backend/load-conference id add-conference/set-edit-conference-data)
                    (session/put! :current-page #'add-conference/edit-conference-page))

(secretary/defroute "/conferences/:id" [id]
                    (reset! conference/displayed-conference nil)
                    (reset! conference/display-ratings nil)
                    (reset! conference/displayed-conference-list nil)
                    (backend/load-conference id #(reset! conference/displayed-conference %1))
                    (backend/load-conference-ratings id #(reset! conference/display-ratings %1))
                    (backend/load-conferences #(reset! conference/displayed-conference-list %1))
                    (session/put! :current-page #'conference/conference-page))

(secretary/defroute "/conferences/:id/add-rating" [id]
                    (session/put! :conference-id-to-rate id)
                    (session/put! :current-page #'add-rating/add-rating))

;; -------------------------
;; Initialize app
(defn mount-root []
  (reagent/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (history/hook-browser-navigation!)
  (mount-root)
  (user-info/load-user-info!))
