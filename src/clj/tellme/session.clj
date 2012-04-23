(ns tellme.session
  (:use [tellme.base.fsm-macros])
  (:require [tellme.base.fsm :as fsm] 
            [tellme.comet :as comet]
            [lamina.core :as lamina]
            [aleph.http :as http]
            [aleph.http.utils :as utils]
            [net.cgrand.moustache :as moustache])
  (:gen-class))

; Protocol -----------------------------------------------------------------
;;
;; Server    -    Client
;;
;; Handshake ----------------------------------
;;
;;        |<----| initial req         ; client1
;; uuid   |---->|                     ; client1
;;        |<----| initial req         ; client2
;; ouuid  |---->|                     ; client2
;;
;;        |<----| :auth  uuid + osid  ; client1
;;        |<----| :auth  ouuid + sid  ; client2
;;
;; :begin |---->|                     ; client1
;; :begin |---->|                     ; client2
;; 
;; --- client1 / client2 now associated
;;
;; Messaging ----------------------------------
;;
;;        |<----| :msg uuid + msg     ; client1
;; :msg   |---->|                     ; client2
;;
;;        |<----| :msg ouuid + msg    ; client2
;; :msg   |---->|                     ; client1
;;
;; ...
;;
;;        |<----| close connection    ; client1/2
;; :close |---->|                     ; client2/1
;; 
;; --------------------------------------------

; Utils --------------------------------------------------------------------

(defn pexception [e]
  (let [sw (java.io.StringWriter.)]
    (.printStackTrace e (java.io.PrintWriter. sw)) 
    (println (.toString sw))))

; Session ------------------------------------------------------------------

(def sid-batch 10)
(def sid-pool (ref {:cnt 0
                    :sids '()}))

(defn get-sid []
  (dosync
    (let [{:keys [cnt sids]} @sid-pool]
      (if (empty? sids)
        (do 
          ; generate more sids
          (alter sid-pool
                 (fn [old]
                   (-> old
                     (assoc :sids (range (inc cnt) (+ sid-batch cnt)))
                     (update-in [:cnt] + sid-batch))))
          cnt)
        (do
          (alter sid-pool update-in [:sids] next)
          (first sids))))))

(def uuid (atom 0))

(defn get-uuid []
  ;(.toString (java.util.UUID/randomUUID))
  (str (swap! uuid inc)))

; Backchannel --------------------------------------------------------------

(def sessions (ref {}))

(defn- on-close [uuid]
  (when-let [{:keys [sid]} (fsm/data @(@sessions uuid))]
    (dosync
      (commute sessions dissoc uuid) 
      (commute sid-pool update-in [:sids] conj sid))))

(defn prgoto [pt state]
  (dosync (alter pt fsm/goto state)))

(defn check-sid [uuid sid]
  (when (@sessions uuid)
    (if (= sid (:sid (fsm/data @(@sessions uuid))))
      (fsm/data @(@sessions uuid)))))

; protocol state machine
(def protocol
  (defsm
    ; data :: {:channel :uuid :sid :osid}
    nil

    ; on start, immediately send uuid to client
    ([:start :in {:keys [uuid sid channel]}]
     (lamina/enqueue channel (str {:uuid uuid :sid sid}))
     (fsm/next-state :auth))

    ([:auth {:keys [command osid]} {:keys [uuid sid channel] :as olddata}]
     (if (= command :auth)
       (let [opt (@sessions osid)]

         ; we allow sid == osid here just for forever alone guys
         (if (and opt (= sid (:osid (fsm/data @opt)))) 
           (do
             (lamina/enqueue channel (str {:ack :ok}))
             (prgoto opt :auth-ok)
             (fsm/next-state :auth-ok (assoc olddata :osid osid)))
           (do
             (lamina/enqueue channel (str {:ack :ok}))
             (fsm/next-state :auth (assoc olddata :osid osid)))))
       (do
         (lamina/enqueue channel (str {:ack :error :reason :noauth}))
         (fsm/ignore-msg))))

    ([:auth-ok :in {:keys [channel backchannel osid] :as data}]
     (comet/enqueue backchannel (str {:ack :ok :message :begin}))
     ; cache fsm of other client for convenience
     (fsm/next-state :dispatch (assoc data :opt (@sessions osid))))

    ([:dispatch {command :command :as msg} data]
     ; filter out allowed commands, else goto :error
     (if (some (partial = command) [:message :end]) 
       (fsm/next-state command data msg) 
       (fsm/next-state :error data {:message msg
                                    :last-state :dispatch})))

    ([:error msg {:keys [channel]}]
     (lamina/enqueue channel (str {:ack :error
                                             :reason :invalid
                                             :message (:message msg)}))
     ; if this didn't come frome dispatch, goto :end
     (if (= (:last-state msg) :dispatch)
       (fsm/next-state :dispatch)
       (fsm/next-state :end)))

    ([:message {message :message} {:keys [channel opt]}]
     (comet/enqueue (:backchannel (fsm/data @opt)) (str {:command :message
                                                     :message message}))
     (lamina/enqueue channel (str {:ack :ok}))
     (fsm/next-state :dispatch))
    
    ([:end :in {:keys [sid uuid opt channel backchannel] :as olddata}]
     (on-close uuid) 

     ; if backchannel is already closed this is a no op
     (comet/enqueue channel (str {:command :end}))
     (comet/close backchannel)

     ; if connected to other client, disconnect him too
     (when opt
       ; remove reference to ourselves first
       (dosync (ref-set opt (fsm/with-data @opt (dissoc (fsm/data @opt) :opt))))
       (prgoto opt :end))
     (fsm/next-state :end (dissoc olddata :opt)))))

(defn backchannel [request]
  (let [rchannel (lamina/channel)]
    (try
      (let [{:keys [uuid sid]} (read-string (.readLine (:body request)))] 
        (when-let [{backchannel :backchannel} (check-sid uuid sid)]
          (comet/client-connected backchannel rchannel)))

      (catch java.lang.Exception e
        (lamina/enqueue rchannel {:ack :error :reason :invalid})))

    {:status 200
     :headers {"content-type" "text/plain"
               "transfer-encoding" "chunked"}
     :body rchannel}))

; Channel ------------------------------------------------------------------

(defn channel [request]
  (let [rchannel (lamina/channel)]
    (try
      ; FIXME: eval security
      (when-let [{:keys [command uuid sid] :as command} (read-string (.readLine (:body request)))]

        (println "channel: " command)

        (if (= command :get-uuid)
          (let [uuid (get-uuid)
                sid (get-sid)
                backchannel (comet/create)
                channel (lamina/channel)
                session (ref (fsm/with-data protocol
                                            {:channel channel
                                             :backchannel backchannel
                                             :uuid uuid
                                             :sid sid
                                             :osid nil}))]
            (lamina/on-closed backchannel (partial on-close uuid))
            (dosync
              (commute sessions assoc uuid session) 
              (lamina/receive channel (fn [msg]
                                        (lamina/enqueue-and-close rchannel msg))) 
              (prgoto session :start)))) 

        (dosync
          (if-let [{:keys [channel]} (check-sid uuid sid)] 
            (do
              (println "going to: " command)
              (prgoto (@sessions uuid) command) 
              (lamina/receive channel (fn [msg]
                                        (lamina/enqueue-and-close rchannel msg))))
            (lamina/enqueue-and-close rchannel (str {:ack :error :reason :session})))))

      (catch java.lang.Exception e
        (pexception e)
        (lamina/enqueue-and-close rchannel (str {:ack :error :reason :invalid})))) 

    {:status 200
     :headers {"content-type" "text/plain"}
     :body rchannel}))

; Routing ------------------------------------------------------------------

(def handler
  (http/wrap-ring-handler
    (moustache/app
      [""] {:get "Hello."}
      ["channel"] {:get channel}
      ["backchannel"] {:get backchannel})))

(comment defn request-handler [ch request]
         (condp = (:uri request)
           "/proxy" (twitter-proxy-handler ch request)
           "/broadcast" (twitter-broadcast-handler ch request)))

