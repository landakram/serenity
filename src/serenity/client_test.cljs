(ns serenity.client-test
  (:require [cljs.test :refer (deftest is use-fixtures async)]
            [cljs.core.async :refer [promise-chan go chan put! >! <!]]
            [serenity.client :as client]
            [mount.core :as mount]))

(defn stubbed-channel-socket []
  (let [recv-ch (chan)
        send-ch (chan)
        send-fn (fn [msg] (put! send-ch msg))]
    {:chsk {}
     :ch-chsk recv-ch
     :chsk-send! send-fn
     :chsk-state (atom {})
     :send-ch send-ch}))

(defn stubbed-peer []
  (let [offer-chan (promise-chan)
        accept-chan (chan)
        drain-chan (chan)
        data-chan (chan)]
    {:offer-chan offer-chan
     :accept-chan accept-chan
     :drain-chan drain-chan
     :data-chan data-chan}))

(defn setup-fixtures []
  (let [chsk (stubbed-channel-socket)
        peer (stubbed-peer)]
    (-> (mount/swap {#'serenity.channel-socket/channel-socket chsk
                     #'serenity.config/config {:client-id "client-id"
                                               :peer-id nil
                                               :csrf-token "csrf-token"
                                               :ice-servers []}
                     #'serenity.peer/peer peer
                     #'serenity.client/-gui-print (constantly :gui-print)})
        (mount/with-args {:router {:event-handler client/event-msg-handler}})
        (mount/start))

    {:chsk chsk
     :peer peer}))

(defn teardown-fixtures []
  (mount/stop))

(deftest a-test
  (async
   done
   (go
     (let [{:keys [chsk peer]} (setup-fixtures)
           {:keys [ch-chsk chsk-state chsk-send! send-ch]} chsk]

       (>! (:offer-chan peer) "offer")
       (>! ch-chsk {:ch-recv ch-chsk
                    :id :serenity/connected
                    :event [:serenity/connected {:data {:peer-id "peer-id"}}]
                    :state chsk-state
                    :?data {:peer-id "peer-id"}
                    :send-fn chsk-send!})
       (is (= [:serenity/offer {:offer "\"offer\""}] (<! send-ch)))

       (teardown-fixtures)
       (done)))))
