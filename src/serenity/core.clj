(ns serenity.core
  (:gen-class)
  (:require [compojure.core :refer [defroutes GET POST DELETE ANY context]]
            [compojure.route :refer [files not-found]]
            [org.httpkit.server :refer [run-server]]
            [ring.logger.timbre :refer [wrap-with-logger]]
            [ring.middleware.defaults]
            [ring.middleware.anti-forgery :as anti-forgery]
            [hiccup.core :as hiccup]
            [hiccup.page :refer [include-css include-js]]
            [taoensso.timbre :as log]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer (get-sch-adapter)]
            [clojure.tools.cli :refer [parse-opts]]))

(let [chsk-server (sente/make-channel-socket!
                   (get-sch-adapter)
                   {:packer :edn
                    :user-id-fn (fn [req]
                                  (log/debug (:client-id req))
                                  (:client-id req))})
      {:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      chsk-server]
  (def ring-ajax-post ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv)
  (def chsk-send! send-fn)
  (def connected-uids connected-uids))

(add-watch connected-uids :connected-uids
  (fn [_ _ old new]
    (when (not= old new)
      (log/infof "Connected uids change: %s" new))))

(defmulti -event-msg-handler :id)

(defmethod -event-msg-handler :default
  [{:keys [event] :as ev-msg}]
  (log/infof "Unhandled event: %s" event))

(defonce peers (atom {}))

(defn add-peers [peers peer1 peer2]
  (-> peers
      (assoc peer1 peer2)
      (assoc peer2 peer1)))

(defn peer-online? [peer-id]
  (some? (some #{peer-id} (:any @connected-uids))))

(defn get-peer [client-id]
  (get @peers client-id))

(defmethod -event-msg-handler :serenity/message
  [{:keys [?data client-id] :as ev-msg}]
  (log/debug (str ":serenity/message handler " client-id " " ?data))

  (when-let [peer (get-peer client-id)]
    (log/debug "Peer found:" peer)
    (chsk-send! peer [:serenity/message ?data])))

(defmethod -event-msg-handler :serenity/connect
  [{:keys [?data client-id] :as ev-msg}]
  (let [{:keys [peer-id]} ?data]
    (when (peer-online? peer-id)
      (swap! peers add-peers client-id peer-id)
      (chsk-send! client-id [:serenity/connected {:peer-id peer-id}])
      (chsk-send! peer-id [:serenity/connected {:peer-id client-id}]))))

(defmethod -event-msg-handler :serenity/offer
  [{:keys [?data client-id] :as ev-msg}]
  (let [{:keys [offer]} ?data
        peer (get-peer client-id)]
    (when (peer-online? peer)
      (chsk-send! peer [:serenity/offer {:offer offer}]))))

(defmethod -event-msg-handler :serenity/accept
  [{:keys [?data client-id] :as ev-msg}]
  (let [{:keys [accept]} ?data
        peer (get-peer client-id)]
    (when (peer-online? peer)
      (chsk-send! peer [:serenity/accept {:accept accept}]))))

(defn event-msg-handler [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defonce router (atom nil))

(defn stop-router! []
  (when-let [stop-fn @router]
    (stop-fn)))

(defn start-router! []
  (stop-router!)
  (reset! router
          (sente/start-server-chsk-router!
           ch-chsk event-msg-handler)))

(defn index [req]
  (hiccup/html
   [:head
    (include-css "main.css")
    (include-css "//fonts.googleapis.com/css2?family=Fira+Mono:wght@400;700")]
   [:p "Welcome to " [:b "SERENITY"] "."]
   [:blockquote [:a {:href "https://tvtropes.org/pmwiki/pmwiki.php/Main/CantStopTheSignal"
                     :target "_blank"}
                 "Can't stop the signal, Mal. They can never stop the signal."]]
   (let [csrf-token (:anti-forgery-token req)]
     [:div#csrf-token {:data-csrf-token csrf-token}])
   [:div#console.console]
   [:div#console-input.console-input
    {:contenteditable "true"
     :autofocus ""}]
   (include-js "main.js")))

(defroutes routes
  (GET "/" [] index)
  (GET "/ws" req (ring-ajax-get-or-ws-handshake req))
  (POST "/ws" req (ring-ajax-post req)))

(defn app []
  (-> #'routes
      wrap-with-logger
      (ring.middleware.defaults/wrap-defaults ring.middleware.defaults/site-defaults)))

(defonce server (atom nil))
(defonce opts (atom nil))

(defn start-server! []
  (reset! server (run-server (app) {:port (:port @opts)
                                    :thread (:threads @opts)}))
  (log/info (str "Server started on port " (:port @opts))))

(defn stop-server! []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)
    (log/info (str "Server stopped"))))

(defn start! []
  (start-router!)
  (start-server!))

(defn stop! []
  (stop-server!)
  (stop-router!))

(defn restart! []
  (stop!)
  (start!))

(defn- to-int [s] (Integer/parseInt s))
(def cli-options
  [["-p" "--port PORT" "Port"
    :default 8080
    :parse-fn to-int]
   ["-t" "--threads THREADS" "Number of worker threads"
    :default 4
    :parse-fn to-int]
   ["-h" "--help"]])

(defn print-header []
  (println "SERENITY: a signaling server for WebRTC."))

(defn -main [& args]
  (let [{:keys [options summary errors]} (parse-opts args cli-options)]
    (when (:help options)
      (print-header)
      (println)
      (println "Help:")
      (println summary)
      (System/exit 0))

    (when (some? errors)
      (print-header)
      (doseq [err errors]
        (println err))
      (println "Please use -h for help.")
      (System/exit 1))

    (reset! opts options)
    (start!)))

(doseq [uid (:any @connected-uids)]
  (chsk-send! uid [:serenity/message "This is a test."]))
