(ns serenity.util
  (:require [cljs.core.async :refer [chan >! <! go go-loop close! alt! timeout put!]]))

(defn produce-from
  ([cb]
   (produce-from (chan) cb))
  ([ch cb & args]
   (apply cb ch args)
   ch))
