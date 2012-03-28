(ns tellme.session
  (:require [lamina.core :as lamina])
  (:gen-class))

; Protocol -----------------------------------------------------------------
;;
;; In oder to prevent accidental or malicious identitiy spoofing both
;; clients need to send both their sid and the other clients's sid.
;;
;; Server    -    Client
;;
;; Handshake --------------------------------
;;
;;        |<----| initial req       ; client1
;; sid    |---->|                   ; client1
;;        |<----| initial req       ; client2
;; osid   |---->|                   ; client2
;;
;;        |<----| sid + osid        ; client1
;;        |<----| osid + sid        ; client2
;; 
;; --- client1 / client2 now associated
;;
;; Messaging --------------------------------
;;
;;        |<----| sid + osid + msg  ; client1
;; msg    |---->|                   ; client2
;;
;;        |<----| osid + sid + msg  ; client2
;; msg    |---->|                   ; client1
;;
;; ...
;;
;;        |<----| close connection  ; client1/2
;; close  |---->|                   ; client2/1
;; 
;; ------------------------------------------

; Session ------------------------------------------------------------------

(def sid-batch 10)
(def sessions (atom {:cnt 0
                     :sids '()}))

(defn get-sid []
  (let [{:keys [cnt sids] :as old} @sessions]

    ; use compare-and-set! here for atomic read & write
    (if (empty? sids)

      ; generate more sids
      (if (compare-and-set!
            sessions
            old
            (-> old
              (assoc :sids (range (inc cnt) (+ sid-batch cnt)))
              (assoc :cnt (+ cnt sid-batch))))

        ; return newest sid or recur till "transaction" succeeds
        cnt
        (recur))

      ; get first sid
      (if (compare-and-set! sessions old (update-in old [:sids] next))

        ; return first free sid or recur till "transaction" succeeds
        (first sids)
        (recur)))))

(defn stream-numbers [ch]
  (future
    (lamina/on-closed ch #(println "Closed :((("))
    (loop [i 1]
      (when (not (lamina/closed? ch))
        (lamina/enqueue ch (str i "\n"))
        (println i)
        (Thread/sleep 500)
        (when (< i 50)
          (recur (inc i)))))
    (lamina/close ch)))

(defn handler [channel request]
  (try
    (let [ch (lamina/channel)]
      (stream-numbers ch)
      (lamina/enqueue channel
                      {:status 200
                       :headers {"content-type" "text/plain"
                                 "transfer-encoding" "chunked"}
                       ;:body (str "<h1 style='font-family: Helvetica; font-size: 48pt; font-weight: bold; color: #333333'>Welcome. Welcome to City " (get-sid) ".</h1>")
                       :body ch
                       })) 

    (catch java.lang.Exception e
      (lamina/enqueue channel
                      {:status 200
                       :headers {"content-type" "text/html"}
                       :body (str "<h1 style='font-family: Helvetica; font-size: 48pt; font-weight: bold; color: #333333'>Error.</h1>")})

      (let [sw (java.io.StringWriter.)]
        (.printStackTrace e (java.io.PrintWriter. sw)) 
        (println (.toString sw))))))
