(ns serenity.peer
  (:require [cljs.core.async :refer [chan >! <! go go-loop close! alt! timeout put!]]
            [serenity.util :as util]
            [simple-peer :as Peer]))

(defonce max-buf-size (* 64 1024)) ;; 64kb, also the max for simple-peer's internal WebRTC backpressure
(defn make-peer [initiator? ice-servers on-connect on-error]
  (let [peer (Peer. (clj->js {:writableHighWaterMark max-buf-size
                              :initiator initiator?
                              :trickle false
                              :config {:iceServers ice-servers}}))
        offer-chan (chan)
        accept-chan (chan)
        drain-chan (chan)

        ;; Looks like simple-peer does this.push without respecting backpressure, so as far as I can tell, there's no
        ;; easy way to stop reading from the socket when we're reading too quickly.
        ;;
        ;; In fact, it looks like the underlying protocol doesn't really support reading with backpressure:
        ;; https://bugs.chromium.org/p/webrtc/issues/detail?id=4616
        ;;
        ;; So we'd have to implement it ourselves in userland by sending messages back to the peer which is too bad :(
        ;;
        ;; Instead of doing that, we just make the read buffer here really large. We could also solve the problem by setting
        ;; the input stream to "paused mode" (not using the on "data" callback), but that just means it will be buffered in the
        ;; stream rather than in this channel. We pray to Yog-Sothoth that we take from this chan fast enough not to fill up
        ;; that humongous buffer and that we have enough memory to let it fill up. A buffer size of 1e6 provides 16gb of space
        ;; given 16kb size messages.
        ;;
        ;; Problem solved forever!
        data-chan (chan 1e6)]

    (.on peer "signal"
         (fn [data]
           (let [ch (if initiator? offer-chan accept-chan)]
             (go (>! ch data)))))

    (.on peer "error"
         (fn [err]
           (on-error err)))

    (.on peer "connect"
         (fn []
           (on-connect)))

    (.on peer "drain"
         (fn []
           (go (>! drain-chan :drained))))

    (.on peer "data"
         (fn [data]
           (let [d (js->clj (js/JSON.parse data) :keywordize-keys true)]
             (go (>! data-chan d)))))

    {:peer peer
     :offer-chan offer-chan
     :accept-chan accept-chan
     :drain-chan drain-chan
     :data-chan data-chan}))

(defn signal [{:keys [peer]} offer-or-accept]
  (.signal peer offer-or-accept))

(defn write [{:keys [peer]} msg]
  (.write peer msg))

(defn status [{:keys [peer]}]
  (util/produce-from
   (fn [ch]
     (try
       (.getStats peer
                  (fn [err report]
                    (if err
                      (put! ch [:error err])
                      (put! ch [:status {:connected (.-connected peer) :report report}]))))
       (catch js/Object e
         (put! ch [:disconnected]))))))
