(ns app.demo
  (:require
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-dom2 :as dom]
   [hyperfiddle.electric-ui4 :as ui]
   [hyperfiddle.electric-svg :as svg]
   [clojure.string :as str]
   [missionary.core :as m]
   [app.krestianstvo :as k])
  (:import [hyperfiddle.electric Pending]))

; Below is an application example. (Demo.) Action with recursive Future message sending and Painter example app showing external message send.
; Parts of Pinter app is gotten from the https://github.com/formicagreen/electric-clojure-painter. Original app uses an atom on the server (e/def paths (e/server (e/watch !paths))) for sharing the drwaing paths.
; In this example app by using Krestianstvo, all drawing paths are updated just on the clients localy, aka (e/client (e/watch !appState)).

(defonce !play (atom true))
(e/def play (e/client (e/watch !play)))

(e/defn run [a selo] (e/client
                      (println "_run_" a)
                      (swap! (:!appState selo) update-in [:counter] inc)
                      (new (:futureMsg selo)
                           :run
                           0.5)))

; one time action on inc msg 
(e/defn incCounter [a selo] (e/client
                             (println "_inc_" a)
                             (swap! (:!appState selo) update-in [:counter] inc)))

; one time action on dec msg 
(e/defn decCounter [a selo] (e/client
                             (println "_dec_" a)
                             (swap! (:!appState selo) update-in [:counter] dec)))

; mouse position
(def !aa (atom []))
(e/def aa (e/client (e/watch !aa)))

(defn cb-fn [cb] (cb @!aa))
(defn fun [] (let [dfv (m/dfv)] (cb-fn dfv) dfv))
(def location> (m/eduction (distinct)
                           (m/ap (loop []
                                   (m/amb (m/? (fun))
                                          (do (m/? (m/sleep 50)) (m/amb))
                                          (recur))))))

(def !current-path-id (atom nil))
(e/def current-path-id (e/client (e/watch !current-path-id)))

(e/defn Toolbar []
  (dom/div
   (dom/style {:background "#fff5"
               :backdrop-filter "blur(10px)"
               :position "absolute"
               :z-index "1"
               :display "flex"
               :top "10px"
               :left "10px"
               :border-radius "10px"
               :box-shadow "0 0 5px rgba(0, 0, 0, 0.2)"
               ;:height "calc(100% - 20px)"
               :flex-direction "column"
               :justify-content "space-between"
               :padding "10px"})

   ; Color picker
   (dom/div
    (e/for [color ["#0f172a" "#dc2626" "#ea580c"
                   "#fbbf24" "#a3e635" "#16a34a"
                   "#0ea5e9" "#4f46e5" "#c026d3"]]
      (dom/div
       (dom/style {:border-radius "100px"
                   :border "1px solid #eeea"
                   :width "5px"
                   :height "5px"
                   :padding "5px"
                   :margin-bottom "10px"
                   :background color})
       (dom/props {:class "hover"})
       (dom/on "click"
               (e/fn [e]
                 (e/server (k/extMsg. :setColor 0 k/session-id color)))))))
    ; Delete button
   (dom/div
    (dom/props {:class "hover"})
    (dom/on "click"
            (e/fn [e]
              (e/server (k/extMsg. :resetCanvas 0 k/session-id nil))))
    (dom/text "🗑️"))))

; one time action for updating the cursor
(e/defn updateCursor [a selo]
  (e/client
   (do
     ;;(println "_cur_ " a)
     (swap! (:!appState selo) assoc-in [:avatars (:id a) :coords]
            [(:x  (:params (:msg a))) (:y (:params (:msg a)))])

     (when current-path-id
       (swap! (:!appState selo) update-in [:paths current-path-id :points] conj [(:x  (:params (:msg a))) (:y (:params (:msg a)))])))))

; processing mouse coordinates and sending messages to the reflector

; Cursor from the Electric pinter example app
(e/defn Cursor [id [x y]]
  (when (and x y)
    (let [offset 5
          cursors ["👁️" "👽" "🌝" "🌚"
                   "🐝" "🌸" "🌼" "🌱"
                   "🧿" "🪲" "🌍" "🐬"]
          index (mod (hash id) (count cursors))]
      (dom/div
       (dom/style {:position "absolute"
                   :left (str (- x offset) "px")
                   :top (str (- y offset) "px")
                   :z-index "2"
                   :transform "scale(2)"
                   :width "30px"
                   :height "30px"
                   :pointer-events "none"})
       (dom/text (cursors index))))))

(e/defn pointerup [e]
  (e/server (k/extMsg. :pointerup 0 k/session-id nil)))

(e/defn pointerdown [e]
  (let [x (.-clientX e)
        y (.-clientY e)
        id (.now js/Date)]

    (e/server
     (k/extMsg. :pointerdown 0 k/session-id {:id id :x x :y y}))))

(e/defn resetCanvas [a selo]
  (e/client
   (swap! (:!appState selo) assoc-in [:paths] (sorted-map))))

(e/defn doSetColor [a selo]
  (e/client
   (do
     (println "set color " a)
     (swap! (:!appState selo) assoc-in [:avatars (:id a) :color]
            (:params (:msg a))))))

(e/defn doPointerUp [a]
  (e/client
   (reset! !current-path-id nil)))

(e/defn doPointerDown [a selo]
  (e/client
   (do
     (println "pointer down " a)
     (reset! !current-path-id (:id (:params (:msg a))))
     (swap! (:!appState selo) assoc-in [:paths (:id (:params (:msg a)))]
            {:points [[(:x (:params (:msg a))) (:y (:params (:msg a)))]]
             :color (let [color (:color (get (:avatars (:appState selo)) (:id a)))] (if (some? color) color "black"))}))))

(e/defn Path [{:keys [points color]}]
  (e/client
   (svg/polyline
    (dom/props {:points (str/join " " (map #(str (first %) "," (second %)) points))
                ; Convert [[x1 y1] [x2 y2] ...] to "x1,y1 x2,y2 ..."
                :stroke color
                :fill "none"
                :stroke-width "5"
                :stroke-linecap "round"
                :stroke-linejoin "round"
                :opacity "0.9"}))))

(e/defn Canvas [selo]
  (let [canvas-size 300]
    (svg/svg
     (dom/props {:viewBox (str "0 0 " canvas-size " " canvas-size)
                 :style {:position "relative"
                         :top "0"
                         :left "0"
                         :border "2px solid"
                         :pointer-events "none"
                         :width (str canvas-size "px")
                         :height (str canvas-size "px")}})
     (e/client
      (e/for-by key [[ke v] (:paths (:appState selo))]
                (do
                  (Path. v)))))))

; simple actions dispatcher for the example app
(e/defn dispatchActions [selo]
  (e/client
   (let [a (:act selo)
         action (:action (:msg a))]
     (println "_act_ " a)
     ;;(if (not= action :tick) (println "_act_ " a))
     (case action
       :run (run. a selo)
       :inc (incCounter. a selo)
       :dec (decCounter. a selo)
       :cursor (updateCursor. a selo)
       :pointerdown (doPointerDown. a selo)
       :pointerup (doPointerUp. a)
       :setColor (doSetColor. a selo)
       :resetCanvas (resetCanvas. a selo)
       :default))))

(defmacro stampCursor [F a] `(e/server (let [data (new ~F (new ~a))]
                                         (k/extMsg. :cursor
                                                    (:instant data)
                                                    k/session-id
                                                    {:x (first (:value data)) :y (second (:value data))}))))
;; Stamp Mouse move with Reflector timestamp
(e/defn MouseMove []
  (dom/on "mousemove" (e/fn [e]
                        (e/client
                         (reset! !aa [(.-clientX e) (.-clientY e)]))))
  (app.demo/stampCursor
   (e/fn [c]
     (new (m/reductions {} nil
                        (m/eduction
                         (map #(do
                                 {:value %
                                  :instant (System/currentTimeMillis)}))
                         c))))
   (e/fn [] (e/fn []
              (e/client (new (m/reductions {} nil (m/latest identity location>))))))))

; Example application with shared local Counter and Cursors
(e/defn Demo [selo]
  (e/client
   (let [appState (:appState selo)
         !appState (:!appState selo)]
     (dom/div

      (dom/div
       (dom/on "pointerdown" pointerdown)
       (dom/on "pointerup" pointerup)
       (Canvas. selo)
       (Toolbar.)
       (MouseMove.))

      (dispatchActions. selo)

      (e/for [[id position] (:avatars appState)]
        (if (not (contains? k/users id))
          (swap! !appState update-in [:avatars] dissoc id) (Cursor. id (:coords position))))

      (dom/h3 (dom/text "Krestianstvo SDK | Electric"))
      (dom/hr)

   ; pause & play local queue
      (dom/div
       (let [label (if play "||" ">")]
         (ui/button (e/fn []
                      (swap! !play not))
                    (dom/text label)))
       (dom/h3 (dom/text "Time: " (:time (:act selo)))))

      (dom/p
       (ui/button (e/fn []
                    (e/server (k/extMsg. :inc 0 :counter nil)))
                  (dom/text "+"))

       (dom/h1 (dom/text (:counter appState)))

       (ui/button (e/fn []
                    (e/server (k/extMsg. :dec 0 :counter nil)))
                  (dom/text "-"))
       (dom/p)
       (ui/button (e/fn []
                    (e/server (k/extMsg. :run 0 :counter nil)))
                  (dom/text "Loop")))

      (dom/hr)

      (dom/div
     ;(dom/text "State: " appState) (dom/br)
     ;(dom/text "State Q on client: " stateQ) (dom/br)
     ;(dom/text "State Q on server: " (e/server savedState))
       )

      (dom/div
       (dom/text "Me: " k/session-id) (dom/br)
       (dom/text "Master: " k/master))))))

; Main entry
(e/defn App []
  (e/client
   (let [initData {:counter 0
                   :avatars {}
                   :paths (sorted-map)}]

     (Demo. (k/Selo. app.demo/play initData)))

   (k/resetSelo.)
   (e/server
    (swap! k/!users assoc k/session-id {:synced false :id k/session-id})
    (println "users: " k/users)
    (e/on-unmount #(swap! k/!users dissoc k/session-id)))))