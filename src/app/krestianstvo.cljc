(ns app.krestianstvo
  (:require
   [hyperfiddle.electric :as e]
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

; Current value of Virtual Time, advancing by Reflector ticks. Get it directly from the message. No need to store in atom.
; (defonce !vtNow (atom 0))
; (e/def vtNow (e/client (e/watch !vtNow)))

; Implementation of a Reflector metronome and timestamps
#?(:clj (def !timeStamp (atom 0)))
(e/def timeStamp (e/server (e/watch !timeStamp)))

(defn -get-Time [_] (System/currentTimeMillis))

(def <ti
  (m/ap (loop [] (m/amb nil (do (m/? (m/sleep 50)) (recur))))))

(e/defn getTimeStamp [] (e/server
                         (reset! !timeStamp (new (m/sample -get-Time <ti)))))

(e/defn tick [] (e/server (/ (- timeStamp startTime) 1000)))

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
  (let [!running# (atom #{}),  running#   (e/watch !running#) ;;(sorted-set)
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

; NEEDED TO FIX! As, the state is updating locally by the clients, on the new connection the client asks an existed clients for the current application state. This initial syncing process involves several stages: get state, get current queue status and replay it localy. 

#?(:clj (defonce !savedState (atom {})))
(e/def savedState (e/server (e/watch !savedState)))

; Use this function to share events from the user-land (UI), as all these messages are explicitly stamped with a e/server time. Later they all will be dispatched locally by the clients. ;;(+ (- (System/currentTimeMillis) startTime) t)
(e/defn extMsg [name t id params]
  (e/server
   (reset! !msg {:msg {:name name :action name :params params} :id id
                 :time   (double (/ (if (= t 0) (- (System/currentTimeMillis) startTime) (- t startTime)) 1000))
                 :origin "reflector"})))

(e/defn resetSelo []
  (e/server
   (if (= (count users) 0)
     (do
       (println "Reset Time!")
       (reset! !startTime (System/currentTimeMillis))
       (reset! !savedState {})
       (reset! !msg {})))))

; Object for managing core operations of the Krestianstvo (shared Croquet VM)
(e/defn Selo [play initData]
  (e/client
   (let [master-user (if (contains? users master) (pr-str master)
                         (do
                           (e/server (reset! !master session-id)) (pr-str master)))

         ; act - is the current message, that is ready for execution
         !act (atom {})
         act (e/watch !act)

         ; Current Reflector tick. 
         !vTime (atom 0)
         vTime (e/watch !vTime)

         ; Mark all messages in the local queue with a unique sequence number
         !vtSeq (atom 0)
         vtSeq (e/watch !vtSeq)

         !x (atom nil)

         !resolvers (atom (qu))
         resolvers (e/watch !resolvers)
         qpop! (fn [aq] (get (-> (swap-vals! aq pop) first peek) :r))
         resolve! (fn [f] (let [dfv (qpop! !resolvers)] (dfv f)))

        ; Local application state, that is never stored to server. It is upadting while processing message localy, by all clients in sync. 
         !appState (atom initData)
         appState (e/watch !appState)

         !getState (atom false)
         getState (e/watch !getState)

         !stateQ (atom {})
         stateQ (e/watch !stateQ)

        ; Future send allows to plan the evaluation of the action in some time in the future from now. These messages are not stamped by the e/server and never going out from the client. Future messages can produce other Future messages, even creating recursive sequences.
         futureMsg (e/fn [name t]
                     (reset! !x {:msg {:name name :action name}
                                 :time (+ t (:time act))
                                 :origin "future"}))

        ; Action on resolving messages in internal queue
         doAct (fn [m] (reset! !act m))

         <reso
         (m/ap (loop [] (m/amb nil (do (m/? (m/sleep 0 (resolve! doAct))) (recur)))))

         ;;processMsg (e/fn [] (new (m/reductions {} nil (m/sample identity <reso))))

         msgTask (fn [k] (m/sp (m/? (m/sleep 0)) k))
         restoreMsgQueue (e/fn [k] (reset! !x (new (e/task->cp (msgTask k)))))

         getStateQueue (e/fn [r]
                         (e/client
                          (if getState
                            (do
                              (println "st "  r)
                              (reset! !stateQ {:state appState
                                              ;; :now vTime
                                               :time vTime
                                               :queue (map (fn [e] (dissoc (:i e) :seq)) (sort-by queueSort (sort-by (fn [a,b] (- a b)) (into [] (filter (fn [m] (not (and (= (-> (:i m) :origin) "reflector") (some? (-> (:i m) :msg :action)))))) r))))})
                              (reset! !getState false)
                              (e/server (if (> (count users) 1) (reset! !savedState stateQ)))))))

         ; Dispatch the messages in local queue. Messages, that marked with origin "reflector" are advancing time and trigger the queue to reolve all pending future messages up to the new now.
         dispatch (e/fn [] (e/client
                            (let [ft (:time (:i (first resolvers)))]
                              (if (and play (> (count resolvers) 0) (<= ft vTime))
                                (new <reso)))))

        ; Add messages to the queue and create corresponding resolvers.
         processQueue (e/fn []
                        (e/client
                         (let
                          [qr (qu) ;;#queue []
                           [running succeeded failed]
                           (*onMsg=. (e/watch !x)
                                     (e/fn [x]
                                       (let [dfv (m/dfv)
                                             se (swap! !vtSeq inc)]
                                         (reset! !resolvers (into qr (sort-by queueSort (conj @!resolvers {:i (conj x {:seq se}) :r dfv}))))
                                         ((new (e/task->cp dfv)) (conj x {:seq se})))))]

                ;(println [:running running])
                ; (println [:succeeded succeeded])
                ; (println [:failed failed])
                           )))]

    ; Advance virtual time
     (e/server
      (if (= (pr-str session-id) master-user)
        (do
          (e/client (println "I AM MASTER" session-id))
          (getTimeStamp.))))

     (processQueue.)
     (dispatch.)

     (let [tck {:msg {:name :tick :action :tick :params nil}
                :id "all"
                :time (e/server (double (tick.)))
                :origin "reflector"}]

       (reset! !x tck)
       (reset! !x msg)
       (reset! !vTime (:time tck))
       (if (= (:origin msg) "reflector") (reset! !vTime (:time msg))))

     (e/for [u users]
       (if  (> (count users) 1)
         (if  (= (pr-str session-id) master-user)
           (do
             (println "Sync client state " u)
             (reset! !getState true)
             (getStateQueue. resolvers))
           (do
             (println "not synced " (e/server savedState))
             (reset! !appState (:state (e/server savedState)))
             (reset! !vTime (:time (e/server savedState)))

             (e/for [rm (e/server (:queue savedState))]
               (restoreMsgQueue. rm))))))

     {:appState appState
      :!appState !appState
      :act act
      :futureMsg futureMsg})))