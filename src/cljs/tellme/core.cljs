(ns tellme.core
  (:require [goog.dom :as dom]
            [goog.dom.ViewportSizeMonitor :as viewport]
            [goog.userAgent :as useragent]
            [goog.events.KeyHandler :as keyhandler]
            [goog.events.KeyCodes :as keycodes]
            [goog.events.EventType :as evttype]
            [goog.events :as events])
  (:use [tellme.base.fsm :only [fsm stateresult data state next-state ignore-msg send-message goto]])
  (:use-macros [tellme.base.fsm-macros :only [defsm]]))

(def sm (defsm
          nil
          ([:start :in data]
           (js/alert "asdasda")
           (ignore-msg))
          
          ([:start {:keys [a b]} _]
           (js/alert (+ a b))
           (ignore-msg))))

(defn test []
  (-> sm
    (goto :start) 
    (send-message {:a 5 :b 7})) 
  (+ 1 3))

(def ^:dynamic x "bblbla")

(defn foo [] (console/log x))

(defn footest []
  (binding [x "ho"] (foo)
    (set! x "asdasd")
    (foo))
  (foo))

(defn animate [element style callback]
  (set! (.-msTransition (.-style element)) "all 400ms ease-in-out") 
  (set! (.-webkitTransition (.-style element)) "all 400ms ease-in-out") 
  (set! (.-MozTransition (.-style element)) "all 400ms ease-in-out") 
  (set! (.-top (.-style element)) style)

  (when callback
    (.addEventListener element "transitionend" callback true)))

(defn begin []
  (let [auth (dom/getElement "auth")
        comm (dom/getElement "comm")]

    ; FIXME: auth not removed
    (animate auth "-100%" #(dom/removeNode auth))
    (animate comm "0%" nil)))

; Animation ----------------------------------------------------------------

; this doesn't replicate css3's ease-in-out timing function but it's
; good enough
(defn ease-in-out [t]
  (if (< t 0.5)
    (* t t 2)
    (- 1.0 (* (- t 1) (- t 1) 2))))

(defn aobj [duration f]
  (let [stime (.getTime (js/Date.))
        frame (atom 0)
        timer (atom nil)] 

    (reset! timer
            (js/setInterval
              (fn []
                (let [now (.getTime (js/Date.))
                      t (ease-in-out (/ (- now stime) duration))]

                  (f t)

                  (when (> (- now stime) duration)
                    (f 1.0)
                    (js/clearInterval @timer)))

                (swap! frame inc))
              10))))

(defn lerp [object k end]
  (let [start (object k)
        delta (- end start)]
    (fn [t]
      (assoc object k (+ start (* delta t))))))

(defn ajs [{:keys [element property end duration style onframe onend]}]
  (let [start (if style
                (js/parseInt (.replace (aget (.-style element) property) "px" ""))
                (aget element property))
        stime (.getTime (js/Date.))
        frame (atom 0)
        delta (- end start)]
    
    ; stop previous animation
    (if (.-jsAnimation element)
      (js/clearInterval (.-jsAnimation element))) 

    (set! (.-jsAnimation element)
          (js/setInterval
            (fn []
              (let [now (.getTime (js/Date.))
                    t (ease-in-out (/ (- now stime) duration))
                    lerp (+ start (* delta t))]

                (if style
                  (aset (.-style element) property (str lerp "px"))
                  (aset element property lerp)) 

                (when onframe
                  (onframe t))

                (when (> (- now stime) duration)
                  (aset (.-style element) property (str end unit))
                  (js/clearInterval (.-jsAnimation element))
                  (set! (.-jsAnimation element) nil)
                  
                  (when onend
                    (onend))))

              (swap! frame inc))
            10))))

(defn setup-animation [element]
  (set! (.-MozTransition (.-style element)) "all 400ms ease-in-out")
  (set! (.-webkitTransition (.-style element)) "all 400ms ease-in-out")
  (set! (.-msTransition (.-style element)) "all 400ms ease-in-out"))

; Size adjustment ----------------------------------------------------------

(def sizes
  (atom {:table-height 0
         :message-height 0

         :sticky-bottom true
         :scroll-top 0

         :message-padding 0
         :content-height 0
         :point1-top 0
         :point2-top 0}))

(defn adjust-sticky-bottom [{:keys [table-height content-height scroll-top] :as sizes}]
  (into sizes {:sticky-bottom (>= scroll-top (- content-height table-height))
               :scroll-top scroll-top}))

(defn adjust-sizes [{:keys [scroll-top table-height message-height sticky-bottom] :as sizes}
                    {:keys [barpoint1 barpoint2 messagepadding scrollcontainer scrolldiv]}]

  (let [message-padding (Math/max 0 (- table-height message-height))
        content-height (+ message-padding message-height)
        
        tpct (* 100 (/ scroll-top content-height))
        spct (* 100 (/ table-height content-height))]

    (into sizes
          {:message-padding message-padding
           :content-height content-height
           :scroll-top (if sticky-bottom
                         (- content-height table-height)
                         scroll-top)
           :point1-top tpct
           :point2-top (+ tpct spct)})))

(defn adjust-dom! [{:keys [table-height content-height scroll-top
                           message-padding point1-top point2-top] :as sizes}
                   {:keys [barpoint1 barpoint2 messagepadding
                           barcontainer scrollcontainer scrolldiv]}]

    (set! (.-height (.-style messagepadding)) (str message-padding "px")) ; FIXME: names
    (set! (.-height (.-style scrollcontainer)) (str content-height "px"))
    (set! (.-height (.-style barcontainer)) (str table-height "px"))
    (set! (.-scrollTop scrolldiv) scroll-top)

    (set! (.-top (.-style barpoint1)) (str point1-top "%"))
    (set! (.-top (.-style barpoint2)) (str point2-top "%")))

; Message handling ---------------------------------------------------------

(defn create-shadowbox [{:keys [comm inputbox scrollcontainer scrollcontent] :as context}]
  (let [shadowbox (dom/createElement "div")
        offset (if (.-GECKO goog.userAgent) -2 0)
        width (+ (.-offsetWidth inputbox) offset)]

    (set! (.-className shadowbox) "shadowbox")
    (set! (.-bottom (.-style shadowbox)) "1000%")
    (dom/appendChild comm shadowbox)

    ; fix widths if we need an offset
    ; wouldn't need to do that but FF is a pixel off here
    (set! (.-width (.-style shadowbox)) (str width "px")) 
    (set! (.-width (.-style scrollcontainer)) (str width "px"))
    (set! (.-marginLeft (.-style scrollcontainer)) (str (- (/ width 2)) "px"))
    (set! (.-width (.-style scrollcontent)) (str width "px"))

    (dom/setTextContent shadowbox ".")

    (into context {:shadowbox shadowbox
                   :shadowbox-width width})))

(defn adjust-inputbox-size [{:keys [inputbox shadowbox inputcontainer
                                    scrollcontainer barcontainer] :as context}]

  (dom/setTextContent shadowbox (if (> (.-length (.-value inputbox)) 0)
                                  (.-value inputbox)
                                  "."))

  (let [height (.-offsetHeight shadowbox)]
    (ajs {:element inputcontainer
          :property "height"
          :duration 400
          :end height
          :style true})

    (ajs {:element inputbox
          :property "height"
          :duration 400
          :end height
          :style true})

    (ajs {:element scrollcontainer
          :property "bottom"
          :duration 400
          :end height
          :style true
          :onframe (partial table-resized context)})

    (ajs {:element barcontainer
          :property "bottom"
          :duration 400
          :end height
          :style true})


    (comment (set! (.-height (.-style inputcontainer)) (str height "px")) 
             (set! (.-height (.-style inputbox)) (str height "px"))
             (set! (.-bottom (.-style scrollcontainer)) (str height "px"))
             (set! (.-bottom (.-style barcontainer)) (str height "px")))

    (adjust-scrolltop context)
    (update-scrollbar context)))

(defn add-message [{:keys [comm scrolldiv scrollcontainer scrollcontent inputbox
                           shadowbox messageheight messagepadding
                           shadowbox-width] :as context}]
  (let [value (.-value inputbox)

        mcontent (dom/createElement "div")
        acontent (dom/createElement "div")

        stop (.-scrollTop scrolldiv)
        soheight (.-offsetHeight scrollcontainer)

        height (.-offsetHeight shadowbox)
        unpadded-height (- height 10)
        newheight (+ messageheight height)
        newcontext (assoc context :messageheight newheight)]

    ; setup content div
    (set! (.-className mcontent) "messagecontent")
    (set! (.-height (.-style mcontent)) (str unpadded-height "px"))

    ; setup animation div
    (set! (.-className acontent) "shadowbox")
    (set! (.-bottom (.-style acontent)) "0px")
    (set! (.-left (.-style acontent)) "50%")

    ; wouldn't need to do that but FF is a pixel off here
    (set! (.-width (.-style acontent)) (str shadowbox-width "px"))
    (set! (.-marginLeft (.-style acontent)) (str (- (/ shadowbox-width 2)) "px"))

    (dom/setTextContent acontent value)
    (dom/appendChild comm acontent)

    ; run slide up animation
    (ajs {:element acontent
          :property "bottom"
          :end 31 ;FIXME
          :duration 400
          :style true
          :onend (fn []
                   (dom/setTextContent mcontent value)
                   (dom/removeNode acontent))})

    ; run scroll animation
    (dom/appendChild scrollcontent mcontent)
    (set! (.-scrollTop scrolldiv) stop)

    (comment ajs {:element scrolldiv
          :property "scrollTop"
          :end (- newheight soheight)
          :duration 400
          :style false})

    ; clear & shrink input box to normal size
    (set! (.-value inputbox) "") 
    (adjust-inputbox-size newcontext)
    
    (-> context
      ;(update-sticky-bottom)
      (assoc :messageheight newheight))))

; main ------------------------------------------------------------------------

(defn main [{:keys [osidbox inputbox inputcontainer comm scrolldiv scrollcontainer] :as start-context}]
  (let [shadowbox (dom/createElement "div")
        context (atom (-> start-context
                        (create-shadowbox)
                        (into {:messageheight 0
                               :sticky-bottom true})))

        scrollhandler (fn [event]
                        (update-scrollbar @context)
                        (swap! context update-sticky-bottom))

        windowhandler (fn [event]
                        (update-scrollbar @context)
                        (update-document-size @context)
                        (adjust-scrolltop @context))
        
        messagehandler (fn [event]
                         (when (= (.-keyCode event) keycodes/ENTER)
                           (when (> (.-length (.-value inputbox)) 0)
                             (swap! context add-message)) 
                           (.preventDefault event)))

        resizehandler (fn [event]
                        (adjust-inputbox-size @context))

        keyhandler (fn [event]
                     (let [kcode (.-keyCode event)
                           ccode (.-charCode event)]

                       (if (and (not= kcode keycodes/BACKSPACE)
                                (or (< ccode keycodes/ZERO)
                                    (> ccode keycodes/NINE)))
                         (.preventDefault event))))

        changehandler (fn [event])]

    (adjust-inputbox-size @context)
    (update-document-size @context)
    (update-scrollbar @context)

    (.focus osidbox)

    (begin)

    ; need this for the godless webkit scroll-on-drag "feature"
    (events/listen scrollcontainer "scroll" (fn [event]
                                                 (set! (.-scrollTop scrollcontainer) 0)
                                                 (set! (.-scrollLeft scrollcontainer) 0)))

    ; register listeners
    (events/listen scrolldiv "scroll" scrollhandler)
    (events/listen (dom/ViewportSizeMonitor.) evttype/RESIZE windowhandler)
    (events/listen (events/KeyHandler. osidbox) "key" keyhandler)
    (events/listen osidbox "input" changehandler)
    (events/listen inputbox "input" resizehandler)
    (events/listen (events/KeyHandler. inputbox) "key" messagehandler)))


; defmacro calling
(defn get-context []
  {:scrollcontainer (dom/getElement "scrollcontainer")
   :scrolldiv (dom/getElement "scrolldiv")
   :scrollcontent (dom/getElement "scrollcontent")
   :barcontainer (dom/getElement "barcontainer")
   :barpoint1 (dom/getElement "barpoint1")
   :barpoint2 (dom/getElement "barpoint2")
   :messagepadding (dom/getElement "messagepadding")
   :inputcontainer (dom/getElement "inputcontainer")
   :comm (dom/getElement "comm")
   :osidbox (dom/getElement "osidbox")
   :inputbox (dom/getElement "inputbox")})

(events/listen js/window evttype/LOAD #(main (get-context)))
