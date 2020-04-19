(ns serenity.channel-socket
  (:require [taoensso.sente :as sente])
  (:require-macros [mount.core :refer [defstate]]))

(declare chsk)
(declare ch-chsk)
(declare chsk-send!)
(declare chsk-state)

(defn start-chsk! [csrf-token client-id]
  (let [client (sente/make-channel-socket-client!
                "/ws"
                csrf-token
                {:type :auto
                 :packer :edn
                 :client-id client-id
                 :wrap-recv-evs? false})
        {:keys [_chsk ch-recv send-fn state]} client]
    (defonce chsk _chsk)
    (defonce ch-chsk ch-recv)
    (defonce chsk-send! send-fn)
    (defonce chsk-state state)))

(defonce router (atom nil))

(defn stop-router! []
  (when-let [stop-fn @router]
    (stop-fn)))

(defn start-router! [ch-chsk event-msg-handler]
  (stop-router!)
  (reset! router
          (sente/start-client-chsk-router! ch-chsk event-msg-handler)))
