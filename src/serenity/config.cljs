(ns serenity.config
  (:require [clojure.string :as string]
            [mount.core :refer [defstate]]))

(defonce csrf-token (atom nil))
(defonce turn-username (atom nil))
(defonce turn-password (atom nil))

(when-let [el (js/document.getElementById "app-data")]
  (reset! csrf-token (.getAttribute el "data-csrf-token"))
  (reset! turn-username (.getAttribute el "data-turn-username"))
  (reset! turn-password (.getAttribute el "data-turn-password")))

(defonce client-id (str (random-uuid)))
(defonce peer-id
  (let [fragment (.substring js/document.location.hash 1)]
    (if (not (string/blank? fragment))
      fragment
      nil)))

(defn make-config []
  {:csrf-token @csrf-token
   :ice-servers [{:urls "turn:coturn.markhudnall.com:3478"
                  :username @turn-username
                  :credential @turn-password}]
   :client-id client-id
   :peer-id peer-id})

(defstate config :start (make-config))
