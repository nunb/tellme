(ns tellme.quote
  (:require [goog.dom :as dom]
            [goog.dom.ViewportSizeMonitor :as viewport]
            [goog.userAgent :as useragent]
            [goog.events.KeyHandler :as keyhandler]
            [goog.events.KeyCodes :as keycodes]
            [goog.events.EventType :as evttype]
            [goog.events :as events]

            [tellme.animation :as anm]
            [tellme.table :as table]) 
  (:use [tellme.base.fsm :only [fsm stateresult data state next-state ignore-msg send-message goto]])
  (:use-macros [tellme.base.fsm-macros :only [defdep defreaction defsm set-styles set-style css]]))

(def create-div (partial dom/createElement "div"))

(def padding-css (css {:paddingTop [5 :px]
                       :paddingBottom [5 :px]}))

(defn- get-range-point [r marker]
  (.insertNode r marker)
  (let [x (.-offsetLeft marker)
        y (.-offsetTop marker)]
    (dom/removeNode marker)

    [x y]))

(defn- slice-text [text srange text-css]
  (let [marker (dom/createElement "span")
        erange (.cloneRange srange)]

    (dom/setTextContent marker ".")
    (text-css marker)

    (when (not (.-collapsed srange))
      (.collapse erange false)

      [(.trim (.substring text (.-startOffset srange) (.-endOffset srange)))
       (.trim (.substring text (.-endOffset srange)))
       (get-range-point srange marker)
       (get-range-point erange marker)])))

(defn- set-content [dcontent content]
  ; FIXME: find character for webkit instead of "-"
  (dom/setTextContent dcontent content) 
  (set! (.-innerHTML dcontent) (.replace (.-innerHTML dcontent) (js/RegExp. " " "g") "&nbsp;")))

(defn- slice-row [table row dcontent content]
  (let [trange (.getRangeAt (js/getSelection js/window) 0)
        [tquote trest [xq yq] [xr yr] :as slice] (slice-text content trange text-css)]

    (when slice

      ; animate quote element
      (set-content dcontent tquote)

      (let [text-height (.-offsetHeight dcontent)
            input-row (table/add-row table)
            rest-row (table/add-row table)

            drest (create-div)
            input (dom/createElement "textarea")]

        (set-styles dcontent {:textIndent [xq :px]
                              :marginTop [yq :px]}) 

        (table/resize-row table row text-height true)
        (anm/aobj :qmargin 400 (anm/lerpstyle dcontent "marginTop" 0))
        (anm/aobj :qindent 400 (anm/lerpstyle dcontent "textIndent" 0) #(dom/setTextContent dcontent tquote))

        ; add input element
        ; FIXME: 31
        ((comp (css {:height [0 :px]
                     :backgroundColor "transparent"
                     :resize "none"
                     :padding [0 :px]
                     :position "absolute"}) padding-css text-css) input) 

        ; FIXME: 31
        (table/resize-row table input-row 41 true) 
        (table/set-row-contents table input-row input) 

        (anm/aobj :input-height 400 (anm/lerpstyle input "height" 41)) 
        (anm/aobj :input-padding 400 (anm/lerpstyle input "paddingTop" 10)) 
        (anm/aobj :input-padding-b 400 (anm/lerpstyle input "paddingBottom" 10)) 

        (.select input)

        ; add rest element row & animate
        (text-css drest) 
        (set-style drest :width [width :px]) 
        (dom/appendChild (table/element table) drest)

        (set-content drest trest)

        ; TODO: scroll to bottom
        (let [rest-height (.-offsetHeight drest)
              top (table/row-top table 0)]

          (set-styles drest {:textIndent [xr :px]
                             :marginTop [yr :px]
                             :top [top :px]
                             :position "absolute"
                             :color "#333333"})

          ; FIXME: 31
          (table/resize-row table rest-row rest-height true) 
          (anm/aobj :rtop 400 (anm/lerpstyle drest "top" (- (.-offsetHeight (table/element table)) rest-height))
                    (fn []
                      (set-style drest :position "")
                      (dom/removeNode drest)
                      (table/set-row-contents table rest-row drest)))
          (anm/aobj :rmargin 400 (anm/lerpstyle drest "marginTop" 0))
          (anm/aobj :rindent 400 (anm/lerpstyle drest "textIndent" 0)))
        
        drest))))

; Constructor --------------------------------------------------------------

(defn create-quote [content width text-css]
  (let [table (table/create-table)
        text (create-div)
        shadow (create-div)]

    ; FIXME: need to detach this
    (events/listen (dom/ViewportSizeMonitor.) evttype/RESIZE (fn [event]
                                                               (table/table-resized table)))

    ; FIXME: collapse with macro
    ; FIXME: find fix for table/element
    (set-style (table/element table) :width [width :px])
    (set-style text :width [width :px])
    (set-style shadow :width [width :px])

    ; init shadow element
    ((comp (css {:position "absolute"
                 :top [1000 :pct]}) text-css) shadow)
    ; FIXME: need to detach this
    (dom/appendChild (.-body (dom/getDocument)) shadow)

    ; quotable text
    (text-css text)
    (dom/setTextContent text content)
    (dom/setTextContent shadow content)

    ; text goes in first table row
    (table/add-row table)
    (table/set-row-contents table 0 text)
    (table/resize-row table 0 (.-offsetHeight shadow) false)

    ; FIXME: test with selection with input element
    (events/listen text "mouseup" (fn [event]
                                    (let [srange (.getRangeAt (js/getSelection js/window) 0)
                                          [tquote trest [xq yq] [xr yr] :as slice] (slice-text content srange text-css)
                                          erest (create-div)
                                          input (dom/createElement "textarea")
                                          
                                          input-row (table/add-row table)
                                          rest-row (table/add-row table)]

                                      (when slice
                                        ;(console/log (pr-str (slice-text content srange text-css))) 

                                        ; animate quote element
                                        (dom/setTextContent text tquote) 
                                        (set! (.-innerHTML text) (.replace (.-innerHTML text) (js/RegExp. " " "g") "&nbsp;")) 

                                        (let [text-height (.-offsetHeight text)]
                                          (set-styles text {:textIndent [xq :px]
                                                            :marginTop [yq :px]}) 

                                          (table/resize-row table 0 text-height true)
                                          (anm/aobj :qmargin 400 (anm/lerpstyle text "marginTop" 0))
                                          (anm/aobj :qindent 400 (anm/lerpstyle text "textIndent" 0) #(dom/setTextContent text tquote))

                                          ; add input element
                                          ; FIXME: 31
                                          ((comp (css {:height [0 :px]
                                                       :backgroundColor "transparent"
                                                       :resize "none"
                                                       :padding [0 :px]
                                                       :position "absolute"}) padding-css text-css) input) 

                                          ; FIXME: 31
                                          (table/resize-row table input-row 41 true) 
                                          (table/set-row-contents table input-row input) 

                                          (anm/aobj :input-height 400 (anm/lerpstyle input "height" 41)) 
                                          (anm/aobj :input-padding 400 (anm/lerpstyle input "paddingTop" 10)) 
                                          (anm/aobj :input-padding-b 400 (anm/lerpstyle input "paddingBottom" 10)) 

                                          (.select input)

                                          ; add rest element row & animate
                                          (text-css erest) 
                                          (set-style erest :width [width :px]) 

                                          (dom/setTextContent erest trest) 
                                          ; FIXME: find character for webkit instead of "-"
                                          (set! (.-innerHTML erest)
                                                (.replace (.replace (.-innerHTML erest) (js/RegExp. " " "g") "&nbsp;") (js/RegExp. "-" "g") "=")) 
                                          (dom/appendChild (table/element table) erest)

                                          ; TODO: scroll to bottom
                                          (let [rest-height (.-offsetHeight erest)
                                                top (table/row-top table 0)]

                                            (set-styles erest {:textIndent [xr :px]
                                                               :marginTop [yr :px]
                                                               :top [top :px]
                                                               :position "absolute"
                                                               :color "#333333"})

                                            ; FIXME: 31
                                            (table/resize-row table rest-row rest-height true) 
                                            (anm/aobj :rtop 400 (anm/lerpstyle erest "top" (- (.-offsetHeight (table/element table)) rest-height))
                                                      (fn []
                                                        (set-style erest :position "")
                                                        (dom/removeNode erest)
                                                        (table/set-row-contents table rest-row erest)))
                                            (anm/aobj :rmargin 400 (anm/lerpstyle erest "marginTop" 0))
                                            (anm/aobj :rindent 400 (anm/lerpstyle erest "textIndent" 0))))) 
                                      ))) 
    table))

; Tests --------------------------------------------------------------------

(def base-css (css {:color color
                    :position "absolute"
                    :width [300 :px]
                    :lineHeight [18 :px]
                    :fontFamily "Helvetica"
                    :fontSize [16 :px]
                    :padding [0 :px]
                    :border [0 :px]
                    :outline [0 :px]
                    :wordWrap "break-word"
                    :whiteSpace "pre-wrap"
                    :overflow "hidden"}))

(defn test-quote []
  (let [table (create-quote "There's one major problem. This doesn't fit into the monadic interface. Monads are (a -> m b), they're based around functions only. There's no way to attach static information. You have only one choice, throw in some input, and see if it passes or fails."
                            300 base-css)]
    (set-styles (table/element table)
                {:position "absolute"
                 :top [0 :px]
                 :bottom [200 :px]
                 :left [50 :pct]
                 :marginLeft [-150 :px]})

    (dom/appendChild (.-body (dom/getDocument)) (table/element table))
    (table/table-resized table)))

(events/listen js/window evttype/LOAD test-quote)
