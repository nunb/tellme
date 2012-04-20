(ns tellme.core
  (:require [goog.dom :as dom]
            [goog.dom.ViewportSizeMonitor :as viewport]
            [goog.userAgent :as useragent]
            [goog.events.KeyHandler :as keyhandler]
            [goog.events.KeyCodes :as keycodes]
            [goog.events.EventType :as evttype]
            [goog.events :as events]

            [domina :as dm]
            [domina.css :as dmc]
            [domina.events :as dme]

            [tellme.base.fsm :as fsm]
            [tellme.ui :as ui]
            [tellme.table :as table]
            [tellme.quote :as qt])
  (:use [tellme.base.fsm :only [fsm stateresult data state next-state ignore-msg send-message goto]])
  (:use-macros [tellme.base.fsm-macros :only [view defdep defreaction defsm set-style set-styles css]]))

(defn animate [element style callback]
  (set! (.-msTransition (.-style element)) "all 400ms ease-in-out") 
  (set! (.-webkitTransition (.-style element)) "all 400ms ease-in-out") 
  (set! (.-MozTransition (.-style element)) "all 400ms ease-in-out") 
  (set! (.-oTransition (.-style element)) "all 400ms ease-in-out") 
  (set! (.-top (.-style element)) style)

  (when callback
    (.addEventListener element "webkitTransitionEnd" callback true)
    (.addEventListener element "transitionend" callback true)
    (.addEventListener element "msTransitionEnd" callback true)
    (.addEventListener element "oTransitionEnd" callback true)))

(defn begin []
  (let [auth (dom/getElement "auth")
        comm (dom/getElement "comm")]

    (animate auth "-100%" #(dom/removeNode auth))
    (animate comm "0%" nil)))

; Utils --------------------------------------------------------------------

(defn- show-quote-button [event]
  (dm/set-style! (.-quoteButton (.-currentTarget event)) :visibility "visible"))

(defn- hide-quote-button [event]
  (dm/set-style! (.-quoteButton (.-currentTarget event)) :visibility "hidden"))

(defn- quote-message [{:keys [main-container quote-overlay table]}
                      {:keys [text row height]} event]

  (let [qt (dm/add-class! (qt/create-quote text #(dm/log-debug (pr-str (qt/get-quotes %)))) "chat-table")
        client-height (.-clientHeight (.-body (dom/getDocument)))
        bottom (+ height (- (table/row-top table row) (table/scroll-top table)))]

    (dm/set-style! qt :bottom (- client-height (+ bottom 28)) "px")
    (dm/set-style! main-container :opacity 1)
    (dm/set-styles! quote-overlay {:visibility "visible"
                                   :opacity "0"}) 

    ; fade out main and show quote overlay
    (ui/animate [main-container :style.opacity 0
                 :onend #(dm/set-style! main-container :visibility "hidden")]
                [quote-overlay :style.opacity 1
                 :onend #(ui/animate [qt :style.bottom [(* client-height 0.3) :px]])])

    (dm/append! quote-overlay qt)
    (ui/resized qt)
    
    (events/listen (dom/ViewportSizeMonitor.) evttype/RESIZE (fn [event]
                                                               (ui/resized qt)))))

(defn set-message-at-row [{:keys [table] :as data} row
                          {:keys [text site height] :as message}]

  (let [msg-container (view :div.message)
        quote-button (view :div.quote-button)
        
        container (view :div.fill-width msg-container quote-button)]

    (dm/set-style! container :height height "px")
    (set! (.-quoteButton (dm/single-node container)) quote-button)

    (dm/set-text! msg-container text)
    (table/set-row-contents table row container)
    
    ; events
    (dme/listen! container :mouseover show-quote-button)
    (dme/listen! container :mouseout hide-quote-button)
    (dme/listen! quote-button :click (partial quote-message data message))))

; FSM ----------------------------------------------------------------------

(def base-sm
  (defsm
    ; fsm data
    {:queue []
     :table nil
     :main-container nil
     :quote-overlay nil}

    ; slide locked state, just queue up messages
    ([:locked message data]
     ;(dm/log-debug (str ":locked message: " (:text message)))
     (next-state :locked (update-in data [:queue] conj (assoc message :slide false))))

    ([:locked :go-to-ready {:keys [queue] :as data}]
     ;(dm/log-debug (str ":locked go-to-ready"))
     ; when going out of :locked state, send all queued messages to self
     (comp (fn [sm] (reduce (fn [a msg] (fsm/send-message a msg)) sm queue))
           (fsm/next-state :ready (assoc data :queue []))))
    
    ([:ready :go-to-ready _]
     (fsm/ignore-msg))

    ; ready for displaying new messages
    ([:ready {:keys [slide text height self] :as message}
      {:keys [table queue] :as data}]

     ;(dm/log-debug (str ":ready message: " text))

     (if slide
       ; received a new local message
       (do
         (if (table/at? table :bottom)
           (let [row (table/add-row table)
                 overlay (view :div.message-overlay)]

             (dm/set-text! overlay text) 
             (dm/append! (dmc/sel "body") overlay) 

             (ui/animate [table row [height :px]]
                         [overlay :style.bottom [31 :px] 
                          :onend (fn []
                                   ;(dm/log-debug (str "slide done: " text))
                                   (set-message-at-row data row (assoc message :row row))
                                   (dm/detach! overlay)
                                   (swap! self fsm/send-message :go-to-ready))])) 

           ; else (if table/at? table bottom)
           (do 
             ;(dm/log-debug (str "starting scroll: " text))
             (ui/animate [table :scroll-bottom [height :px]
                       ; after sliding, return to :ready and add the message
                       ; we wanted to add in the first place (but had to scroll
                       ; down before doing so)
                       :duration 0
                       :onend (fn []
                                ;(dm/log-debug (str "scroll done: " text))
                                (reset! self (-> @self
                                               (fsm/send-message :go-to-ready)
                                               (fsm/send-message message))))]))) 
         ; lock sliding
         (fsm/next-state :locked)) 

       ; else (if slide)
       (let [row (table/add-row table)]
         (ui/animate [table row [height :px]])
         (set-message-at-row data row (assoc message :row row))
         (fsm/ignore-msg)))))) 

; main ---------------------------------------------------------------------

(defn main3 []
  (let [table (dm/add-class! (table/create-table) "chat-table") 
        shadow (view :div.shadow)
        input (view :textarea.chat-input)

        main-container (view :div.main table shadow input)
        quote-overlay (view :div.quote-overlay)

        body (view (dmc/sel "body") main-container quote-overlay)

        sm (atom (fsm/with-data base-sm {:queue []
                                         :table table
                                         :main-container main-container
                                         :quote-overlay quote-overlay})) 
        
        message (atom nil)
        shadow-height (defdep [message]
                              (dm/set-text! shadow (if (> (.-length message) 0) message "."))
                              (ui/property shadow :offsetHeight)) 
        input-height (atom -1)]
    
    ; dependencies
    (defreaction shadow-height (ui/animate [input-height shadow-height]))

    ;(ui/bind input-height input :style.height "px")
    ;(ui/bind input-height table :style.bottom "px")
    ;(defreaction input-height (ui/resized table))

    (defreaction input-height
                 (dm/set-style! input :height input-height "px")
                 (dm/set-style! table :bottom input-height "px")
                 (ui/resized table))

    ; dom
    (ui/resized table)

    ; events
    (dme/listen! input :input (fn [event]
                                (reset! message (dm/value input))))

    (events/listen (dom/ViewportSizeMonitor.) evttype/RESIZE (fn [event]
                                                               (ui/resized table)))

    (events/listen (events/KeyHandler. (dm/single-node input))
                   "key"
                   (fn [event]
                     (when (= (.-keyCode event) keycodes/ENTER)
                       (when (> (.-length (dm/value input)) 0)
                         (swap! sm fsm/send-message {:self sm
                                                     :slide true
                                                     :text (dm/value input)
                                                     :height (ui/property shadow :offsetHeight)})

                         (dm/set-value! input "")
                         (reset! message "")) 
                       (.preventDefault event))))  

    ; init
    (swap! sm fsm/goto :ready)
    (reset! message "")))
 
(events/listen js/window evttype/LOAD main3)

