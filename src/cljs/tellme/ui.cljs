(ns tellme.ui
  (:require [domina :as dm]
            [tellme.animation :as anm]))

; --------------------------------------------------------------------------

(defprotocol View
  (resized [this] "Should be called whenever the view has been resized externally."))

; Animation ----------------------------------------------------------------

; this doesn't replicate css3's ease-in-out timing function but it's
; good enough
(defn- ease-in-out [t]
  (if (< t 0.5)
    (* t t 2)
    (- 1.0 (* (- t 1) (- t 1) 2))))

(def aobjs (atom {}))
(def atimer (atom nil))

(defn- runa []
  (doseq [[tag [f stime duration onend]] @aobjs]
    (let [now (.getTime (js/Date.))
          t (ease-in-out (/ (- now stime) duration))]

      (if (> (- now stime) duration)
        (do
          (f 1.0) 
          (when onend
            (onend false)) 
          (swap! aobjs dissoc tag)
          
          (when (zero? (count @aobjs))
            (js/clearInterval @atimer)
            (reset! atimer nil))) 
        (f t)))))

(defn- aobj [tag duration f onend]
  (when (zero? (count @aobjs))
    (reset! atimer (js/setInterval runa 10)))
  (swap! aobjs assoc tag [f (.getTime (js/Date.)) (* 1 duration) onend]))

(def unit-map {:px "px" :pct "%" :pt "pt"})

(defn from-unit [u]
  (if-let [unit (unit-map u)]
    unit
    (throw (Error. "Invalid css unit"))))

(defn- extract [v]
  (if (vector? v)
    [(first v) (from-unit (second v))]
    [v nil]))

(defn- starts-with [s ss]
  (= (.lastIndexOf s ss 0) 0))

(defn- lerp [f start end]
  (let [[e u] (extract end)]
    (if u
      (fn [t] (f (str (+ start (* t (- e start))) u)))
      (fn [t] (f (+ start (* t (- e start))))))))

(defn reflect [content property & value]
  (let [pname (name property)]
    (cond
      (starts-with pname "style.")
      (do
        (dm/log-debug (apply str value))
        (apply dm/set-style! content (.substring pname (.-length "style.")) value)) 
      
      (starts-with pname "attr.")
      (apply dm/set-attr! content (.substring pname (.-length "attr.")) value)

      :else
      (reset! (property content) (apply str value)))))

(defn- tfunc [content property to]
  (let [pname (name property)] 

    ; regexps are not necessary here
    (cond
      ; animate style
      (starts-with pname "style.")
      (let [style (.substring pname (.-length "style."))]
        (lerp #(aset (.-style (dm/single-node content)) style %) (dm/style content style) to)) 

      ; animate attribute
      (starts-with pname "attr.")
      (let [attr (.substring pname (.-length "attr."))]
        (lerp #(aset (dm/single-node content) attr %) (dm/attr content attr) to))
      
      :else
      ; animate atom field
      (let [field (property content)]
        (lerp #(reset! field %) @field to)) 

      :else nil)))

(defn animate-property [content property to &
                        [{:keys [duration onend] :or {duration 400}}]]
  (aobj (str (goog.getUid content) ":" (name property)) duration (tfunc content property to) onend))

(defn animate [& anms]
  (doseq [[content property to & opts] anms] (animate-property content property to opts)))

(defn bind [dep content property & unit]
  (add-watch dep (str (goog.getUid dep) ":" (name property))
             (fn [k r o n]
               (when (not= o n)
                 (apply reflect content property n unit)))))

; Elements -----------------------------------------------------------------

(defn create-element [name]
  (let [element (.createElement js/document name)]
    (reify dm/DomContent
      (single-node [this] element)
      (nodes [this] [element]))))

