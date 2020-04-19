(ns serenity.channel-socket
  (:require [serenity.config :refer [config]]
            [taoensso.sente :as sente]
            [mount.core :refer [defstate args]]))

(defn start-chsk [{:keys [client-id csrf-token]}]
  (let [client (sente/make-channel-socket-client!
                "/ws"
                csrf-token
                {:type :auto
                 :packer :edn
                 :client-id client-id
                 :wrap-recv-evs? false})
        {:keys [_chsk ch-recv send-fn state]} client]
    {:chsk _chsk
     :ch-chsk ch-recv
     :chsk-send! send-fn
     :chsk-state state}))

;; TODO: should prob define :stop
;; Probably worth turning chsk into a protocol instead of map too
(defstate channel-socket :start (start-chsk @config))

(defn stop-router [stop-fn]
  (stop-fn))

(defn start-router [{:keys [event-handler channel-socket]}]
  (let [{:keys [ch-chsk]} channel-socket]
    (sente/start-client-chsk-router! ch-chsk event-handler)))

(defstate router
  :start (let [{:keys [router]} (args)]
           (start-router {:channel-socket @channel-socket
                          :event-handler (:event-handler router)}))
  :stop (stop-router @router))
