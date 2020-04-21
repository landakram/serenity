(ns serenity.client
  (:require [taoensso.timbre :as log]
            [taoensso.sente :as sente]
            [clojure.string :as string]
            [mount.core :refer [defstate] :as mount]
            [cljs.core.match :refer-macros [match]]
            [crate.core :as crate]
            [cljs.core.async :refer [chan >! <! go go-loop close! alt! timeout put!]]
            [reagent.core :as r]
            [reagent.dom :as rdom]
            [reagent.ratom :refer [reaction]]
            [drag-drop :as drag-drop]
            [clojure.contrib.humanize :as humanize]
            [serenity.config :refer [config]]
            [serenity.peer :as peer]
            [serenity.util :as util]
            [serenity.channel-socket :refer [channel-socket]]
            [goog.string :as gstring]
            [goog.string.format]
            ["streamsaver" :as streamSaver]
            ["web-streams-polyfill/ponyfill" :as web-streams-polyfill]
            [oops.core :refer [oget oset!]])
  (:require-macros [serenity.macros :refer [when-let*]]))

(declare commands)
(declare run-command)
(declare await)
(declare deserialize-array-buffer)
(declare help-header)

(defn last-child-tag [el]
  (when-let [child (.-lastElementChild el)]
    (.-tagName child)))

(defonce console-el (.getElementById js/document "console"))
(defn -gui-print
  ([class str]
   (cond
     :else
     (-gui-print [:p {:class (name class)} str])))
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

(defstate gui-print :start -gui-print)

(defmulti -event-msg-handler :id)

(defmethod -event-msg-handler :default
  [{:keys [event] :as ev-msg}]
  (log/infof "Unhandled event: %s" event))

(defn peer-link [peer-id]
  (str js/document.location.href "#" peer-id))

(defonce signaling-status (atom {:status :disconnected :state {}}))

(defmethod -event-msg-handler :chsk/state
  [{:keys [?data] :as ev-msg}]
  (let [[old-state-map new-state-map] ?data
        {:keys [chsk-send!]} @channel-socket]
    (if (:first-open? new-state-map)
      (do
        (log/infof "Channel socket successfully established!: %s" new-state-map)
        (@gui-print :debug (str "Your peer-id is " (:client-id @config) "."))
        (@gui-print :debug "Connected to signaling server.")
        (swap! signaling-status assoc :status :established :state new-state-map)
        (if (some? (:peer-id @config))
          (chsk-send! [:serenity/connect {:peer-id (:peer-id @config)}])
          (do
            (@gui-print :info (help-header))
            (run-command (:get-peer-link commands)))))
      (do
        (swap! signaling-status assoc :state new-state-map)
        (log/infof "Channel socket state change: %s" new-state-map)))))

(defmethod -event-msg-handler :chsk/recv
  [{:keys [?data] :as ev-msg}]
  (log/infof "Push event from server: %s" ev-msg))

(defmethod -event-msg-handler :chsk/handshake
  [{:keys [?data] :as ev-msg}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (log/infof "Handshake: %s" ?data)))

(defmethod -event-msg-handler :serenity/message
  [{:keys [?data] :as ev-msg}]
  (@gui-print :info ?data)
  (log/info (str ":serenity/message handler with message: " ?data)))

(defn initiator? []
  (nil? (:peer-id @config)))

;; Should generalize this to multiple files I guess
(defonce file (r/atom nil))
(defn make-file [metadata]
  (let [{:keys [size name]} metadata
        writer (-> streamSaver
                   (.createWriteStream name #js {:size size})
                   (.getWriter))]
    {:metadata metadata
     :progress {:state :downloading
                :started (.getTime (js/Date.))
                :bytes 0}
     :writer writer}))

(defn finalize-file [{:keys [parts metadata]}]
  (let [type (:type metadata)
        blob (js/Blob. (clj->js parts) #js {:type type})
        blob-url (js/URL.createObjectURL blob)]
    (@gui-print [:p [:a {:href blob-url
                        :download (:name metadata)}
                    (str "Save " (:name metadata))]])))

(declare mount-progress!)
(defn handle-header [d]
  (reset! file (make-file (dissoc d :msg-type)))
  (mount-progress! file :download))

(defn mark-progress [file bytes]
  (update-in file [:progress :bytes] + bytes))

(defn mark-done [file]
  (assoc-in file [:progress :state] :done))

(defn handle-chunk [d]
  (go
    (let [buf (-> d
                  :blob
                  deserialize-array-buffer
                  <!
                  (js/Uint8Array. ))]
      (.write (:writer @file) buf)
      (swap! file mark-progress (.-byteLength buf)))))

(defn handle-footer [d]
  (swap! file mark-done)
  (.close (:writer @file)))

(defn start-peer-listener! [p]
  (go-loop [d (<! (:data-chan p))]
    (when (some? d)
      (condp = (:msg-type d)
        "h" (handle-header d)
        "c" (<! (handle-chunk d))
        "f" (handle-footer d))
      (recur (<! (:data-chan p))))))

(defn progress-indicator [file up-or-down]
  (let [f (atom @file)
        debounced-f (r/atom @f)]
    (add-watch file (:name @file)
               (fn [key ref old-state {:keys [progress]}]
                 (swap! f assoc :progress progress)
                 (when (= :done (get-in @f [:progress :state]))
                   (remove-watch ref key))))

    (go-loop []
      (<! (timeout 1000))
      (reset! debounced-f @f)
      (when (not= :done (get-in @debounced-f [:progress :state]))
        (recur)))

    (fn []
      (let [{:keys [metadata progress]} @debounced-f
            {:keys [state started bytes]} progress
            {:keys [name size]} metadata
            elapsed-time (- (.getTime  (js/Date.)) started)
            bytes-per-s (* 1000 (/ bytes elapsed-time))
            percent-done (/ bytes size)
            arrow (condp = up-or-down
                    :upload "ᐃ"
                    :download "ᐁ")
            send-or-receive (condp = up-or-down
                              :upload "Sending..."
                              :download "Receiving...")]
        [:div.progress-indicator {:class "info"}
         (if (not= state :done)
           [:p {:class "info"} send-or-receive]
           [:p {:class "success"} "Completed in " (humanize/duration elapsed-time {:number-format str}) "."])
         [:p name " ⌁ " (humanize/filesize size) " " arrow " " (humanize/filesize bytes-per-s) "/s"]
         [:pre "[" ,(repeat (* percent-done 50) "=") ,(repeat (* (- 1 percent-done) 50) " ") "] "
          (gstring/format "%d" (min 100 (* 100 percent-done))) "% "]]))))

(defn mount-progress! [file up-or-down]
  (let [el (crate/html [:div.progress-container])]
    (rdom/render [progress-indicator file up-or-down] el)
    (.appendChild console-el el)))

(defmethod -event-msg-handler :serenity/connected
  [{:keys [?data] :as ev-msg}]
  (let [{:keys [peer-id]} ?data
        {:keys [chsk-send!]} @channel-socket]
    (swap! signaling-status assoc :status :connected-to-peer :peer peer-id)
    (@gui-print :debug (str "Connecting to " peer-id "..."))
    (@gui-print :debug (str "Connected to " peer-id " on signaling server."))
    (when (initiator?)
      ;; TODO: change how this works to support reconnects
      (go
        (let [offer (<! (:offer-chan @peer/peer))]
          (swap! signaling-status assoc :status :sent-offer)
          (@gui-print :debug (str "Sending offer:"))
          (@gui-print [:p {:class "debug"} [:pre (js/JSON.stringify offer nil 2)]])
          (chsk-send! [:serenity/offer {:offer (js/JSON.stringify offer)}]))))))

(defmethod -event-msg-handler :serenity/offer
  [{:keys [?data] :as ev-msg}]
  (let [{:keys [offer]} ?data
        parsed-offer (js/JSON.parse offer)
        {:keys [chsk-send!]} @channel-socket]
    (swap! signaling-status assoc :status :received-offer)
    (@gui-print :debug (str "Received offer:"))
    (@gui-print [:p {:class "debug"} [:pre (js/JSON.stringify parsed-offer nil 2)]])
    (peer/signal @peer/peer parsed-offer)
    (go
      (let [accept (<! (:accept-chan @peer/peer))]
        (swap! signaling-status assoc :status :sent-accept)
        (@gui-print :debug (str "Sending accept:"))
        (@gui-print [:p {:class "debug"} [:pre (js/JSON.stringify accept nil 2)]])
        (chsk-send! [:serenity/accept {:accept (js/JSON.stringify accept)}])))))

(defmethod -event-msg-handler :serenity/accept
  [{:keys [?data] :as ev-msg}]
  (let [{:keys [accept]} ?data
        parsed-accept (js/JSON.parse accept)]
    (swap! signaling-status assoc :status :received-accept)
    (@gui-print :debug (str "Received accept:"))
    (@gui-print [:p {:class "debug"} [:pre (js/JSON.stringify parsed-accept nil 2)]])
    (peer/signal @peer/peer parsed-accept)))

(defn event-msg-handler [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defn help-header []
  [:p [:b "SERENITY"] " is a peer-to-peer file sharing tool. When you send your " [:b "peer link"]
   " to a friend, your browsers will be directly connected over an encrypted channel without a 3rd party intermediate."])

(defn display-help []
  (let [cmd-help (map (fn [[cmd {:keys [description pattern]}]]
                        [:li [:b pattern] " " description])
                      commands)]
    (@gui-print [:div {:class "info"}
                (help-header)
                [:p "Once you're connected, you can share files by dragging and dropping them into this browser window."]
                [:p "The following commands are also available:"]
                [:ul.cmd-list ,cmd-help]])))

(def commands
  {:msg
   {:pattern "msg <message>"
    :description "Send a message to the peer with whom you're connected."
    :handler
    (fn [args]
      (let [{:keys [chsk-send!]} @channel-socket]
        (@gui-print "info" (str "you: " args))
        (chsk-send! [:serenity/message (str (:client-id @config) ": " args)])))}

   :get-peer-link
   {:pattern "get-peer-link"
    :description (str "Print a link to send to your friend. "
                      "When your friend visits the link, you'll be connected over WebRTC.")
    :handler
    (fn [_]
      (@gui-print [:p [:b "To get started, send this link to your friend: "]
                  [:a {:href (peer-link (:client-id @config))
                       :target "_blank"}
                   (peer-link (:client-id @config))]]))}

   :status
   {:pattern "status"
    :description "Get the status of your connection with a peer."
    :handler
    (fn [_]
      (go
        (@gui-print [:p {:class "info"} [:u "Connection status"]])
        (@gui-print :info "Signaling server:")
        (@gui-print [:p {:class "debug"} [:pre (js/JSON.stringify (clj->js @signaling-status) nil 2)]])
        (@gui-print :info "WebRTC:")
        (let [status (<! (peer/status @peer/peer))]
          (match
           status 
           [:error err]
           (@gui-print :error err)

           [:disconnected]
           (@gui-print [:p {:class "debug"} "Disconnected"])

           [:status {:connected connected :report report}]
           (do
             (oset! js/window "!peer" (:peer @peer/peer))
             (@gui-print [:p {:class "debug"} (if connected "Connected" "Disconnected")])
             (@gui-print [:p {:class "debug"} [:pre (js/JSON.stringify report nil 2)]]))))))}

   :clear
   {:pattern "clear"
    :description "Clear console output."
    :handler
    (fn [_]
      (oset! console-el "innerHTML" ""))}

   :expand
   {:pattern "expand"
    :description "Expand all collapsed console output. Useful for sharing logs for bug reports."
    :handler
    (fn [_]
      (let [details (js/document.querySelectorAll "details")]
        (doseq [d (array-seq details)]
          (oset! d "open" true)
          (js/window.scrollTo 0 js/document.body.scrollHeight))))}

   :collapse
   {:pattern "collapse"
    :description "Collapse all expanded console output."
    :handler
    (fn [_]
      (let [details (js/document.querySelectorAll "details")]
        (doseq [d (array-seq details)]
          (oset! d "open" false)
          (js/window.scrollTo 0 js/document.body.scrollHeight))))}

   :help
   {:pattern "help"
    :description "Display this help message."
    :handler
    (fn [_]
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

(defn attach-console-input-focus! [console-input]
  (.focus console-input)
  (.addEventListener js/window "click"
                     (fn [^js e]
                       (when (string/blank? (.toString (js/window.getSelection)))
                         (.focus console-input)))))

(defn attach-console-input! [console-input]
  (.addEventListener console-input "keypress"
                     (fn [^js e]
                       (let [text (.-textContent console-input)]
                         (when (= "Enter" (.-key e))
                           (.preventDefault e)
                           (when (not (string/blank? text))
                             (if-let [[cmd args] (parse-command text)]
                               (run-command cmd args)
                               (@gui-print [:p {:class "error"}
                                           "Command not found. Type " [:b "help"] " to see available commands."]))
                             (oset! console-input "textContent" "")))))))

(defn await [promise]
  (util/produce-from
   (fn [ch]
     (.then promise (fn [& args]
                      (go (>! ch args)))))))

(defn serialize-blob [blob]
  (util/produce-from
   (fn [ch]
     (let [reader (js/FileReader.)]
       (.readAsDataURL reader blob)
       (oset! reader "onload" (fn []
                               (go (>! ch (.-result reader)))))))))

(defn deserialize-array-buffer [data-uri]
  (util/produce-from
   (fn [ch]
     (-> (js/fetch data-uri)
         (.then (fn [res]
                  (.arrayBuffer res)))
         (.then (fn [array-buf]
                  (go (>! ch array-buf))))))))

;; Some WebRTC learning resources:
;;
;; - Optimal chunk size, 16kb: https://viblast.com/blog/2015/2/5/webrtc-data-channel-message-size/
;; - on bufferredAmount and the need for backpressure: https://viblast.com/blog/2015/2/25/webrtc-bufferedamount/
;; - Chrome does not implement "blob" binaryType:
;;     https"//stackoverflow.com/questions/53327281/firefox-not-understanding-that-a-variable-contains-an-arraybuffer-while-chrome-d/"
;; - Persisting data during download:
;;     https://stackoverflow.com/questions/29700049/webrtc-datachannels-saving-data-in-file-during-transfer-of-big-files
(defonce HEADER :h)
(defonce CHUNK :c)
(defonce FOOTER :f)
(defn send-file [p file]
  (let [chunk-size (* 16 1024)
        ch (chan (/ peer/max-buf-size chunk-size))
        header-msg {:msg-type HEADER
                    :name (.-name file)
                    :size (.-size file)
                    :type (.-type file)}
        progress-file (atom (make-file header-msg))]

    (mount-progress! progress-file :upload)

    ;; Reader: puts data onto the simple-peer stream, respecting backpressure
    ;;
    ;; simple-peer does its own backpressure for the WebRTC connection to ensure
    ;; that it does not write too quickly, but we can still fill up simple-peer's
    ;; writeable buffer too quickly and cause memory issues for ourselves. So we
    ;; still set highWaterMark when initializing the peer and then respect the
    ;; stream's 'drain' events.
    ;;
    ;; core.async channels with buffers provide the backpressure mechanism.
    (go-loop [msg (<! ch)]
      (let [serialized-msg (js/JSON.stringify (clj->js msg))]
        (when (some? msg)
          (<! (peer/write p serialized-msg))
          (recur (<! ch)))))

    ;; Writer: put file onto the chan in chunks, with header and footer metadata messages
    (put! ch header-msg)
    (go-loop [start 0
              end (min (+ start chunk-size) (.-size file))]
      (let [chunk (.slice file start end)]
        (cond
          (= 0 (.-size chunk))
          (do
            (swap! progress-file mark-done)
            (>! ch {:msg-type FOOTER})
            (close! ch)
            (log/info "Done"))

          :else
          (do
            (swap! progress-file mark-progress (.-size chunk))
            (>! ch {:msg-type CHUNK
                    :blob (<! (serialize-blob chunk))})
            (recur end
                   (min (+ end chunk-size) (.-size file)))))))))

(defn attach-drag-drop! [drop-target-id]
  (drag-drop drop-target-id
             (fn [files pos file-list directories]
               (let [file (first files)]
                 (go
                   (cond
                     (> 1 (count files))
                     (@gui-print :error "Only one file at a time is supported right now.")

                     (let [[status args] (<! (peer/status @peer/peer))]
                       (or (= status :error)
                           (= status :disconnected)
                           (and
                            (= status :status)
                            (not (get args :connected)))))
                     (do
                       (@gui-print :error "You must be connected with someone over WebRTC to send files.")
                       (@gui-print [:p {:class "error"}
                                   "Connect with a peer by sending them your peer link. "
                                   "Type " [:b "help"] " for more details."]))

                     :else
                     (send-file @peer/peer file)))))))

(defn main []
  (let [console-input (js/document.getElementById "console-input")]
    (when (nil? (oget js/window "?WritableStream"))
      (oset! streamSaver "!WritableStream" web-streams-polyfill/WritableStream)
      (oset! streamSaver "!TransformStream" web-streams-polyfill/TransformStream))

    (mount/start-with-args
     {:router {:event-handler event-msg-handler}
      :peer {:ice-servers (:ice-servers @config)
             :initiator? (initiator?)
             :on-connect (fn []
                           (@gui-print :success "Connected via WebRTC.")
                           (@gui-print :info "To share a file, drag and drop it into this browser window."))
             :on-error (fn [err]
                         (@gui-print :error (str "WebRTC error: \"" err "\""))
                         (when (peer/destroyed? @peer/peer)
                           (mount/stop #'peer/peer)
                           (mount/start #'peer/peer)))}})

    (log/info "Alive.")
    (log/info (str "client-id: " (:client-id @config)))
    (log/info (str "peer-id: " (:peer-id @config)))
    (log/info (str "csrf-token: " (:csrf-token @config)))

    (oset! js/window "!console_input" console-input)
    (attach-console-input! console-input)
    (attach-console-input-focus! console-input)
    (attach-drag-drop! "#drop-target")

    (start-peer-listener! @peer/peer)))
