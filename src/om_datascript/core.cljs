(ns om-datascript.core
  (:require [goog.dom :as gdom]
            [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [datascript.core :as d]))

(enable-console-print!)

(println "Testing Datascript!")

;; I will have a :car/maker attribute which will be a reference to some other entity (think record),
;; so do the needful when I ask you to handle it (e.g. de-refing). Also :car/colors is going to be
;; an array.
(def conn (d/create-conn {:car/maker {:db/type :db.type/ref}
                          :car/colors {:db/cardinality :db.cardinality/many}}))

;; First example

(d/transact!
 conn
 [{:db/id -1
   :app/title "Hello, DataScript!"
   :app/count 0}])

(defmulti read om/dispatch)

(defmethod read :app/counter
  [{:keys [state query]} _ _]
  {:value (d/q '[:find [(pull ?e ?selector) ...]
                 :in $ ?selector
                 :where [?e :app/title]]
               (d/db state) query)})

(defmulti mutate om/dispatch)

(defmethod mutate 'app/increment
  [{:keys [state]} _ entity]
  {:value {:keys [:app/counter]}
   :action (fn [] (d/transact! state
                               [(update-in entity [:app/count] inc)]))})

(defui Counter
  static om/IQuery
  (query [this]
         [{:app/counter [:db/id :app/title :app/count]}])
  Object
  (render [this]
          (let [{:keys [app/title app/count] :as entity}
                (get-in (om/props this) [:app/counter 0])]
            (dom/div nil
                     (dom/h2 nil title)
                     (dom/span nil (str "Count: " count))
                     (dom/button
                      #js {:onClick
                           (fn [e]
                             (om/transact! this
                                           `[(app/increment ~entity)]))}
                      "Click me!")))))

(def reconciler
  (om/reconciler
   {:state conn
    :parser (om/parser {:read read :mutate mutate})}))

(om/add-root! reconciler
              Counter (gdom/getElement "app"))

;; Learn Datascript

(d/transact! conn [{:maker/name "Honda"
                    :maker/country "Japan"}])

;; +  transact! means we're going to transact something (insert/delete/update).
;; +  conn is our database (or database connection if you will).
;; +  The weird looking array is what we intend to transact. There are several
;; ways this can be specified as we'll learn in future lessons. Here we are
;; simply asking Datascript to insert these two attribues about some entity
;; into the database.

;; As you can see, I didn't add any of the attributes I mentioned in my schema,
;; you only need to define stuff that you want to specifically control. Lets do
;; one more.

(d/transact! conn [{:db/id -1
                    :maker/name "BMW"
                    :maker/country "Germany"}
                   {:car/maker -1
                    :car/name "i525"
                    :car/colors ["red" "green" "blue"]}])

;(d/transact! conn [{:db/id -1
;                    :maker/name "BMW1"
;                    :maker/country "Germany1"}
;                   {:car/maker -1
;                    :car/name "i5251"
;                    :car/colors ["red" "green" "blue"]}])
;
;(d/transact! conn [{:db/id -1
;                    :maker/name "BMW2"
;                    :maker/country "Germany2"}
;                   {:car/maker -1
;                    :car/name "i5252"
;                    :car/colors ["red" "green" "blue"]}])

;; The new things here:
;; + We're transacting multiple things (since the array now has two maps).
;; + There's a weird :db/id field set to -1 in our maker insertion.
;; + There's a :car/maker attributes set to -1 in our car insertion.
;; + The :cars/colors attribute is an array, it will be handled correctly
;; for us because we mentioned that in the schema, it has many wow
;; cadinaleeteez

;; You're saying
;; Insert two things: a maker and a car made by that maker. Give the maker
;; an id -1 (because I am going to refer to it later), then add a car and
;; set the car's maker ref as the maker I just inserted (identified by -1).

;; The -1 is resolved to a real entity id when the transaction happens and the
;; :car/maker is correctly set to it. The -1 here is just a temporary id for us to
;; connect stuff up as we insert it, without us having to do multitple transactions
;; (insert maker first, get id, then insert car).

;; Querying
;; Now to fetch these values out of the database
(def output (d/q '[:find ?name
                   :where
                   [?e :maker/name "BMW"]
                   [?c :car/maker ?e]
                   [?c :car/name ?name]]
                  @conn))

;; This should give you #{["i525"]}
;; That looks like an awful way to do whatever the heck its doing. Fear not,
;; fear is the mind-killer.

;; I don't want to go into how datascript stores stuff internally as datoms
;; and all the stuff that goes on (since I don't completely understand it yet).
;; For now lets's keep it simple:

;; + Anything that starts with ? is a variable which will hold a value for us
;; as we process the :where rules.
;; + The thing after :find is what the query will return. In this case whatever
;; ends up being in ?name comes out.
;; + Each :where rule has a specific format, for our purposes for now its:
;; [<entity-id> <attribute> <value>]
;; A variable in a rule is assigned any values that satisfies that rule. E.g.
;; when we say [?m :maker/name "BMW"] thw variable ?m is at that <entity-id>
;; position, so once this rule is processed, ?m holds the entity-id for "BMW"
;; maker.

;; So as we go down the rules:
;; + [?m :maker/name "BMW"] - ?m ends up the entity-id of the "BMW" maker.
;; + [?c :car/maker ?m] - ?c ends up with the entity-id of the car which
;; has its maker as ?m (which is "BMW").
;; + [?c :car/name ?name] - ?name ends up with the value of the :car/name
;; attribute of the ?c entity-id, which in this case is "i525".
;; + The rule processing finishes and we end up with "i525" in ?name which is
;; finally returned from the query.

(def carname (let [car-entity (ffirst
                                (d/q '[:find ?c
                                       :where
                                       [?e :maker/name "BMW"]
                                       [?c :car/maker ?e]]
                                     @conn))]
               (:car/name (d/entity @conn car-entity))))


(defmulti read1 om/dispatch)

(defmethod read1 :car/maker
  [{:keys [state query]} _ _]
  {:value (d/q '[:find [(pull ?e ?selector) ...]
                 :in $ ?selector
                 :where [?e :maker/name]]
               (d/db state) query)})

(defui CarMakerItem
  Object
  (render [this]
    (let [{:keys [db/id maker/name maker/country] :as entity} (om/props this)]
      (dom/div nil
               (dom/h3 nil "Car Maker Item: ")
               (dom/div nil (str entity))
               (dom/div nil (str "db/id: " id))
               (dom/div nil (str "maker/name: " name))
               (dom/div nil (str "maker/country: " country))))))

(def carmakeritem (om/factory CarMakerItem))

(defui CarMakers
  static om/IQuery
  (query [this]
    [{:car/maker [:db/id :maker/name :maker/country]}])
  Object
  (render [this]
    (let [entities (get (om/props this) :car/maker)]
      (dom/div nil
               (dom/h2 nil "Car Makers")
               (dom/div nil (str (om/props this)))
               (dom/div nil (str (get (om/props this) :car/maker)))
               (map carmakeritem entities)))))

(def reconciler1
  (om/reconciler
    {:state conn
     :parser (om/parser {:read read1})}))

(om/add-root! reconciler1
              CarMakers (gdom/getElement "app1"))








