(ns app.krestianstvo
  (:require
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-dom2 :as dom]
   [hyperfiddle.electric-ui4 :as ui]
   [missionary.core :as m])
  (:import [hyperfiddle.electric Pending]))

; Reference implementations:
; Virtual Time https://github.com/NikolaySuslov/krestianstvo/blob/main/src/VirtualTime.js
; Reflector server https://github.com/NikolaySuslov/lcs-reflector/blob/master/lib/reflector.js
; Future Message Send https://docs.krestianstvo.org/en/message/ (https://github.com/NikolaySuslov/krestianstvo-docs/blob/main/src/pages/en/message.mdx)
; TODO: Portals https://docs.krestianstvo.org/en/portal/

; Every client (browser window/tab) is identified by unique ID, assigned on first connection
(e/def session-id (e/server (get-in e/*http-request* [:headers "sec-websocket-key"])))

; Store all users in one place
#?(:clj (defonce !users (atom {}))) ; session-id -> user
(e/def users (e/server (e/watch !users)))

; Master holds the role of Reflector server. By default corresponding application server instances are created for all connected clients in Electric. In the current Krestianstvo implementation, we select only one server instance (first client by default), that server's instance will be used as a metronome, producing the unique timestamps. Other clients will use that generated timestamp. 

#?(:clj (defonce !master (atom 0)))
(e/def master (e/server (e/watch !master)))

;; Save start time of the Reflector metronome start
#?(:clj (def !startTime (atom (System/currentTimeMillis))))
(e/def startTime (e/server (e/watch !startTime)))

; All messages, that are generated from user-land (eg. UI, external controllers) should be stamped by Reflector and then dispatched locally by clients. 
; Atom msg is used for storing such messages. 
#?(:clj (defonce !msg (atom {})))
(e/def msg (e/server (e/watch !msg)))

; act - is the current message, that is ready for execution
(defonce !act (atom {}))
(e/def act (e/client (e/watch !act)))

; Current value of Virtual Time, advancing by Reflector ticks. Get it directly from the message. No need to store in atom.
; (defonce !vtNow (atom 0))
; (e/def vtNow (e/client (e/watch !vtNow)))

; Current Reflector tick. 
(defonce !vTime (atom 0))
(e/def vTime (e/client (e/watch !vTime)))

; Mark all messages in the local queue with a unique sequence number
(defonce !vtSeq (atom 0))
(e/def vtSeq (e/client (e/watch !vtSeq)))

; Implementation of a Reflector metronome and timestamps
#?(:clj (def !timeStamp (atom 0)))
(e/def timeStamp (e/server (e/watch !timeStamp)))

(defn -get-Time [_] (System/currentTimeMillis))

(def <ti
  (m/ap (loop [] (m/amb nil (do (m/? (m/sleep 50)) (recur))))))

(e/def getTimeStamp (e/server
                     (if (or (= session-id master) (= (count users) 1))
                       (reset! !timeStamp
                               (new (m/sample -get-Time <ti))))
                     timeStamp))

(e/def now (e/server (- getTimeStamp startTime)))

; Timestamped messages, that are inserted into queue are sorted
(defn queueSort [i j]
  (let [a (if (some? (get i :time)) i (get i :i))
        b (if (some? (get j :time)) j (get j :i))]
    (let [c (- (:time a) (:time b))]
      (if (not= (:time a) (:time b))
        c
        (let [c (and (not= (:origin a) "reflector") (= (:origin b) "reflector"))]
          (if (= c true)
            -1
            (let [c (and (= (:origin a) "reflector") (not= (:origin b) "reflector"))]
              (if (= c true)
                1
                (- (:seq a) (:seq b))))))))))


; Internal queue for dispatching messages, based on the development version of a new Event system for Electric
(e/defn *onMsg= [flow F]
  (let [!running# (atom (sorted-set-by queueSort)),  running#   (e/watch !running#) ;;(sorted-set)
        !succeeded# (atom {}), succeeded# (->> !succeeded# m/watch (m/relieve merge) new)
        !failed# (atom {}),  failed# (->> !failed# m/watch (m/relieve merge) new)
        flow# flow, F# F]
    (when (some? flow#) (swap! !running# conj flow#))
    (e/for [v# running#]
      (try (let [ret# (new F# v#)]
             (case ret# (do (reset! !succeeded# {v# ret#})
                            (swap! !running# disj v#))))
           (catch Pending  e#)
           (catch :default e#
             (swap! !failed# assoc v# e#)
             (swap! !running# disj v#))))
    [running# succeeded# failed#]))

(defn- qu [] #?(:clj clojure.lang.PersistentQueue/EMPTY :cljs #queue []))
(def !x (atom nil))
(def !resolvers (atom (qu)))
(defn qpop! [aq] (get (-> (swap-vals! aq pop) first peek) :r))
(defn resolve! [f] (let [dfv (qpop! !resolvers)] (dfv f)))

; Action on resolving messages in internal queue
(defn doAct [m] (reset! !act m))

(def <reso
  (m/ap (loop [] (m/amb nil (do (m/? (m/sleep 0 (resolve! doAct))) (recur))))))

(e/defn processMsg [] (e/client (new (m/reductions {} nil (m/sample vector <reso)))))

; Local application state, that is never stored to server. It is upadting while processing message localy, by all clients in sync. 
(defonce !appState (atom {:counter 0
                          :avatars {}}))
(e/def appState (e/client (e/watch !appState)))

; As, the state is updating locally by the clients, on the new connection the client asks an existed clients for the current application state. This syncing process involves several stages: get state, get current queue status and replay it localy (WIP). 
(def !syncState (atom false))
(e/def syncState (e/client (e/watch !syncState)))

(defonce !getState (atom false))
(e/def getState (e/client (e/watch !getState)))

(defonce !stateQ (atom {}))
(e/def stateQ (e/client (e/watch !stateQ)))

#?(:clj (defonce !savedState (atom {})))
(e/def savedState (e/server (e/watch !savedState)))

(defonce !play (atom true))
(e/def play (e/client (e/watch !play)))

(defn msgTask [k] (m/sp (m/? (m/sleep 0)) k))
(e/defn restoreMsgQueue [k] (e/client
                             (reset! !x (new (e/task->cp (msgTask k))))))


(e/defn getStateQueue [r] (if getState
                            (do (reset! !stateQ {:state appState
                                                 ;; :now (:time act) ;;vtNow
                                                 :time vTime ;;vTime
                                                 :queue (map (fn [e] (dissoc e :seq)) (sort-by (fn [a,b] (- a b)) (into [] (filter (fn [m] (not (and (= (-> m :origin) "reflector") (some? (-> m :msg :action)))))) r)))})
                                (reset! !getState false))
                            (e/server (reset! !savedState stateQ))))


; Dispatch the messages in local queue. Messages, that marked with origin "reflector" are advancing time and trigger the queue to reolve all pending future messages up to the new now.
(e/defn dispatch [running] (e/client
                            (let [ft (get (first running) :time)]
                              (if (and play syncState (> (count running) 0) (<= ft vTime))
                                (new processMsg)))

                            (if (and (= session-id master) (> (count users) 1))
                              (getStateQueue. running))))

; Add messages to the queue and create corresponding resolvers.
(e/defn processQueue []  (let
                          [qr (qu) ;;#queue []
                           [running succeeded failed]
                           (*onMsg=. (e/watch !x)
                                     (e/fn [x]
                                       (let [dfv (m/dfv)
                                             se (swap! !vtSeq inc)]
                                         (reset! !resolvers (into qr (sort-by queueSort (conj @!resolvers {:i (conj @!x {:seq se}) :r dfv}))))
                                         ((new (e/task->cp dfv)) (conj x {:seq se})))))]

                  ; (println [:running running])
                  ; (println [:succeeded succeeded])
                  ; (println [:failed failed])

                           (dispatch. running)))

; Use this function to share events from the user-land (UI), as all these messages are explicitly stamped with a e/server time. Later they all will be dispatched locally by the clients.
(e/defn extMsg [name t id params]
  (e/server
   (reset! !msg {:msg {:name name :action name :params params} :id id :time (+ (- (System/currentTimeMillis) startTime) t) :origin "reflector"})))

; Future send allows to plan the evaluation of the action in some time in the future from now. These messages are not stamped by the e/server and never going out from the client. Future messages can produce other Future messages, even creating recursive sequences.
(e/defn futureMsg [name t]
  (e/client
   (reset! !x {:msg {:name name :action name} :time t :origin "future"})))

; Below is an application example. (Simple.) Action with recursive Future message sending
(e/defn run [a] (e/client
                 (println "_run_" a)
                 (swap! !appState update-in [:counter] inc)
                 (futureMsg. :run (+ (:time a) 500))))

; one time action on inc msg 
(e/defn incCounter [a] (e/client
                        (println "_inc_" a)
                        (swap! !appState update-in [:counter] inc)))

; one time action on dec msg 
(e/defn decCounter [a] (e/client
                        (println "_dec_" a)
                        (swap! !appState update-in [:counter] dec)))

; mouse position
(def !aa (atom []))
(e/def aa (e/client (e/watch !aa)))

(defn cb-fn [cb] (cb @!aa))
(defn fun [] (let [dfv (m/dfv)] (cb-fn dfv) dfv))
(def location> (m/ap (loop []
                       (m/amb (m/? (fun))
                              (do (m/? (m/sleep 10)) (m/amb))
                              (recur)))))

; one time action for updating the cursor
(e/defn updateCursor [a] (e/client
                          (swap! !appState assoc-in [:avatars (:id a)] [(:x  (:params (:msg a))) (:y (:params (:msg a)))])))

; processing mouse coordinates and sending messages to the reflector
(e/defn MouseMove [] (e/client

                      (let [[x y] (dom/on! js/document "mousemove" (fn [e] [(.-clientX e) (.-clientY e)]))
                            cu (new (m/reductions {} nil (m/sample identity location>)))]

                        (reset! !aa [x y])
                        (if (not= cu aa) (e/server (extMsg. :cursor 0 session-id {:x (first cu) :y (second cu)}))))))


; Cursor from the Electric pinter example app
(e/defn Cursor [id [x y]]
  (when (and x y)
    (let [offset 15
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

; simple actions dispatcher for the example app
(e/defn dispatchActions [a] (e/client
                             (let [action (:action (:msg a))]
                               (println "_act_ " a)
                               (case action
                                 :run (run. a)
                                 :inc (incCounter. a)
                                 :dec (decCounter. a)
                                 :cursor (updateCursor. a)
                                 :default))))

; Example application with shared local Counter and Cursors
(e/defn Simple [core]
  (e/client

   (MouseMove.)
   (dispatchActions. act)

   (e/for [[id position] (:avatars appState)]
     (if (not (contains? users id))
       (swap! !appState update-in [:avatars] dissoc id) (Cursor. id position)))

   (dom/h3 (dom/text "Krestianstvo SDK | Electric"))
   (dom/hr)

   ; pause & play local queue
   (dom/div
    (let [label (if play "||" ">")]
      (ui/button (e/fn []
                   (swap! !play not))
                 (dom/text label)))
    (dom/h3 (dom/text "Time: " (:time act))))

   (dom/p
    (ui/button (e/fn []
                 (e/server (extMsg. :inc 0 :counter nil)))
               (dom/text "+"))

    (dom/h1 (dom/text (:counter appState)))

    (ui/button (e/fn []
                 (e/server (extMsg. :dec 0 :counter nil)))
               (dom/text "-"))
    (dom/p)
    (ui/button (e/fn []
                 (e/server (extMsg. :run 0 :counter nil)))
               (dom/text "Loop")))

   (dom/hr)
   (dom/div
    (dom/text "State: " appState) (dom/br)
    ; (dom/text "State Q on client: " stateQ) (dom/br)
    ; (dom/text "State Q on server: " (e/server savedState))
    )

   (dom/div
    (dom/text "Me: " session-id) (dom/br)
    (dom/text "Master: " master))))

; Object for managing core operations of the Krestianstvo 
(e/defn Selo [] (e/client
                 (let [master-user (if (contains? users master) (pr-str master)
                                       (do
                                         (e/server (reset! !master session-id))
                                         (pr-str master)))]

                   ; Advance virtual time
                   (e/server (reset! !msg {:msg {:name :tick :action nil :params nil} :id "all" :time now :origin "reflector"}))
                   (processQueue.)
                   (if syncState (reset! !vTime now))
                   (reset! !x msg)

                   ; Managing new connections
                   (e/server (e/for [u users] (do

                                                (if (and (= (pr-str session-id) master-user) (>= (count users) 2))
                                                  (e/client  (do
                                                               (println "New user ask for Master! " u)
                                                               (reset! !getState true)
                                                               (reset! !stateQ {})))))))

                   ;;(not synced)
                   (e/server (if (and (not= (pr-str session-id) master-user)
                                      (> (count users) 1)
                                      (not (e/client syncState)))
                               (do
                                 (e/client (do
                                             (println "not master " session-id  " master " master-user)

                                             (reset! !appState (:state (e/server savedState)))
                                             (reset! !vTime (:time (e/server savedState)))
                                             (reset! !syncState true)))
                                 (e/for [rm (:queue savedState)]
                                   (e/client (restoreMsgQueue. rm)))))))))

; Main entry
(e/defn App []
  (e/client

   (Simple. (Selo.))

   (e/server
    (if (= (count users) 0) (do
                              (println "Reset Time!")
                              (reset! !startTime (System/currentTimeMillis))
                              (reset! !savedState {})
                              (e/client (do
                                          (println "RESET")
                                          (reset! !syncState true))))))

   (e/server
    (swap! !users assoc session-id {:synced false :id session-id})
    (println "users: " users)
    (e/on-unmount #(swap! !users dissoc session-id)))))