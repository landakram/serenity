(ns serenity.client
  (:require [taoensso.timbre :as log]
            [taoensso.sente :as sente]
            [clojure.string :as string]
            [crate.core :as crate]
            [cljs.core.async :refer [chan >! <! go]]
            [simple-peer :as Peer])
  (:require-macros [serenity.macros :refer [when-let*]]))

(log/info "Alive.")

(declare commands)
(declare run-command)

(def csrf-token (atom nil))

(when-let [el (js/document.getElementById "csrf-token")]
  (reset! csrf-token (.getAttribute el "data-csrf-token")))

(def client-id (str (random-uuid)))
(def peer-id
  (let [fragment (.substring js/document.location.hash 1)]
    (if (not (string/blank? fragment))
      fragment
      nil)))

(log/info (str "client-id: " client-id))
(log/info (str "peer-id: " peer-id))
(log/info (str "csrf-token: " @csrf-token))

(defn last-child-tag [el]
  (when-let [child (.-lastElementChild el)]
    (.-tagName child)))

(def console-el (.getElementById js/document "console"))
(defn gui-print
  ([class str]
   (cond
     :else
     (gui-print [:p {:class (name class)} str])))
  ([el]
   ;; Collapse consecutive debug statements into a <details>
   (cond
     (and (= "debug" (get (second el) :class))
          (some? (last-child-tag console-el))
          (= "debug" (.-className (.-lastElementChild console-el)))
          (not= "DETAILS" (last-child-tag console-el)))
     (let [last-child (.-lastElementChild console-el)]
       (.replaceChild console-el
                      (crate/html [:details {:class "debug"}
                                   [:summary (array-seq (.-childNodes last-child))]])
                      last-child)
       (.appendChild (.-lastElementChild console-el) (crate/html el)))

     (and (= "debug" (get (second el) :class))
          (= "DETAILS" (last-child-tag console-el)))
     (.appendChild (.-lastElementChild console-el) (crate/html el))

     :else
     (.appendChild console-el (crate/html el)))
   (js/window.scrollTo 0 js/document.body.scrollHeight)))

(let [client (sente/make-channel-socket-client!
               "/ws"
               @csrf-token
               {:type :auto
                :packer :edn
                :client-id client-id
                :wrap-recv-evs? false})
      {:keys [chsk ch-recv send-fn state]} client]
  (def chsk chsk)
  (def ch-chsk ch-recv)
  (def chsk-send! send-fn)
  (def chsk-state state))

(defmulti -event-msg-handler :id)

(defmethod -event-msg-handler :default
  [{:keys [event] :as ev-msg}]
  (log/infof "Unhandled event: %s" event))

(defn peer-link [peer-id]
  (str js/document.location.href "#" peer-id))

(defmethod -event-msg-handler :chsk/state
  [{:keys [?data] :as ev-msg}]
  (let [[old-state-map new-state-map] ?data]
    (if (:first-open? new-state-map)
      (do
        (log/infof "Channel socket successfully established!: %s" new-state-map)
        (gui-print :debug (str "Your peer-id is " client-id "."))
        (if (some? peer-id)
          (chsk-send! [:serenity/connect {:peer-id peer-id}])
          (do
            (run-command (:get-peer-link commands)))))
      (log/infof "Channel socket state change: %s" new-state-map))))

(defmethod -event-msg-handler :chsk/recv
  [{:keys [?data] :as ev-msg}]
  (log/infof "Push event from server: %s" ev-msg))

(defmethod -event-msg-handler :chsk/handshake
  [{:keys [?data] :as ev-msg}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (log/infof "Handshake: %s" ?data)))

(defmethod -event-msg-handler :serenity/message
  [{:keys [?data] :as ev-msg}]
  (gui-print :info ?data)
  (log/info (str ":serenity/message handler with message: " ?data)))

(defn initiator? []
  (nil? peer-id))

(def peer (Peer. (clj->js {:initiator (initiator?)
                           :trickle false
                           :config {:iceServers [{:urls "turn:coturn.markhudnall.com:3478"
                                                  :username "7243CDF7-62CA-4DCC-82AA-05FB023CDE48"
                                                  :credential "AEFE1791-B5F2-49A1-AAF4-AD750721EA6C"}]}})))
(def offer-chan (chan))
(def accept-chan (chan))
(def data-chan (chan))
(.on peer "signal"
     (fn [data]
       (log/info "SIGNAL" (js/JSON.stringify data))
       (let [ch (if (initiator?) offer-chan accept-chan)]
         (go (>! ch (js/JSON.stringify data))))))

(.on peer "error"
     (fn [err]
       (gui-print :error err)))

(.on peer "connect"
     (fn []
       (gui-print :success "Connected via WebRTC.")))

(.on peer "data"
     (fn [data]
       (go (>! data-chan data))))

(defmethod -event-msg-handler :serenity/connected
  [{:keys [?data] :as ev-msg}]
  (let [{:keys [peer-id]} ?data]
    (gui-print :debug (str "Connected to " peer-id " on signaling server."))
    (when (initiator?)
      (go
        (let [offer (<! offer-chan)]
          (gui-print :debug (str "Sending offer: " offer))
          (chsk-send! [:serenity/offer {:offer offer}]))))))

(defmethod -event-msg-handler :serenity/offer
  [{:keys [?data] :as ev-msg}]
  (let [{:keys [offer]} ?data]
    (gui-print :debug (str "Received offer: " offer))
    (.signal peer (js/JSON.parse offer))
    (go
      (let [accept (<! accept-chan)]
        (gui-print :debug (str "Sending accept:" accept))
        (chsk-send! [:serenity/accept {:accept accept}])))))

(defmethod -event-msg-handler :serenity/accept
  [{:keys [?data] :as ev-msg}]
  (let [{:keys [accept]} ?data]
    (gui-print :debug (str "Received accept:" accept))
    (.signal peer (js/JSON.parse accept))))

(defn event-msg-handler [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defonce router (atom nil))

(defn stop-router! []
  (when-let [stop-fn @router]
    (stop-fn)))

(defn start-router! []
  (stop-router!)
  (reset! router
          (sente/start-client-chsk-router! ch-chsk event-msg-handler)))

(defn display-help []
  (let [header (str "The following commands are available:")
        cmd-help (map (fn [[cmd {:keys [description pattern]}]]
                        [:p [:b pattern] " " description])
                      commands)]
    (gui-print [:div {:class "info"}
                [:p header]
                cmd-help])))

(def commands
  {:msg {:pattern "msg <message>"
         :description "Send a message to the peer with whom you're connected."
         :handler (fn [args]
                    (gui-print "info" (str "you: " args))
                    (chsk-send! [:serenity/message (str client-id ": " args)]))}
   :get-peer-link {:pattern "get-peer-link"
                   :description (str "Print a link to sent to your friend. "
                                     "When your friend visits the link, you'll be connected over WebRTC.")
                   :handler (fn [_]
                              (gui-print [:p [:b "Send this link to your friend: "]
                                          [:a {:href (peer-link client-id)
                                               :target "_blank"}
                                           (peer-link client-id)]]))}
   :clear {:pattern "clear"
           :description "Clear console output."
           :handler (fn [_]
                      (aset console-el "innerHTML" ""))}
   :help {:pattern "help"
          :description "Display this help message."
          :handler (fn [_]
                     (display-help))}})

(defn parse-command [text]
  (let [regex #"([a-zA-Z0-9-]+) ?(.*)"]
    (when-let* [[_ cmd args] (re-matches regex text)
                command (get commands (keyword cmd))]
      [command args])))

(defn run-command
  ([cmd]
   (run-command cmd nil))
  ([cmd args]
   ((:handler cmd) args)))

(let [console-input (js/document.getElementById "console-input")]
  (aset js/window "console_input" console-input)
  (.addEventListener console-input "keypress"
                     (fn [e]
                       (let [text (.-textContent console-input)]
                         (when (= "Enter" (.-key e))
                             (.preventDefault e)
                             (when (not (string/blank? text))
                               (if-let [[cmd args] (parse-command text)]
                                 (run-command cmd args)
                                 (gui-print [:p {:class "error"}
                                             "Command not found. Type " [:b "help"] " to see available commands."]))
                               (aset console-input "textContent" ""))))))
  (defn main []
    (start-router!)
    (.focus console-input)
    (.addEventListener js/window "click"
                       (fn [e]
                         (when (string/blank? (.toString (js/window.getSelection)))
                           (.focus console-input))))))
