(ns haywire128.etheric-rpg-engine.datahike-db-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [haywire128.etheric-rpg-engine.core :as c]
            [haywire128.etheric-rpg-engine.shell :as shell]))

(defn path-str [ks] (str/join "/" (map name ks)))

(deftest create-and-query-entity
  (let [db (shell/datahike-db)]
    (c/transact! db [{:entity/type :npc :entity/name "Bjorn"}])
    (let [entities (c/q-range db [] 100)]
      (is (= 1 (count entities)))
      (let [entity (first entities)]
        (is (= :npc (:entity/type entity)))
        (is (= "Bjorn" (:entity/name entity)))))))

(deftest query-non-existent-entity
  (let [db (shell/datahike-db)]
    (is (nil? (c/q-entity db 99999)))))

(deftest query-location-by-path
  (let [db (shell/datahike-db)]
    (c/transact! db [{:location/path-str (path-str [:kingdom :district :tavern])
                      :location/name "The Crooked Hearth"
                      :location/loc-type :tavern}])
    (let [loc (c/q-location db [:kingdom :district :tavern])]
      (is (some? loc))
      (is (= "The Crooked Hearth" (:location/name loc))))))

(deftest query-entities-in-range
  (let [db (shell/datahike-db)]
    (c/transact! db [{:entity/type :npc :entity/name "Bjorn"
                      :location/path-str (path-str [:a :b :c :d :e])}
                     {:entity/type :npc :entity/name "Hilda"
                      :location/path-str (path-str [:a :b :c :d :f])}])
    (let [entities (c/q-range db [:a :b :c :d] 10)]
      (is (= 2 (count entities))))))

(deftest query-entities-in-range-prefix-match
  (let [db (shell/datahike-db)]
    (c/transact! db [{:entity/type :npc :entity/name "InRange"
                      :location/path-str (path-str [:a :b :c :d :tavern])}
                     {:entity/type :npc :entity/name "OutOfRange"
                      :location/path-str (path-str [:x :y :z])}])
    (let [entities (c/q-range db [:a :b :c] 10)]
      (is (= 1 (count entities)))
      (is (= "InRange" (:entity/name (first entities)))))))

(deftest taxonomy-storage
  (let [db (shell/datahike-db)]
    (c/transact! db [{:taxonomy/path-str (path-str [:a :b])
                      :taxonomy/depth 2
                      :taxonomy/name "TestRegion"
                      :taxonomy/traits [:ancient :magical]
                      :taxonomy/atmosphere "Misty and ancient"}])
    (let [entities (c/q-range db [:a :b] 10)]
      (is (pos? (count entities))))))

(deftest relationship-entities
  (let [db (shell/datahike-db)]
    (c/transact! db [{:entity/type :player :entity/name "Alice"}])
    (c/transact! db [{:entity/type :npc :entity/name "Bjorn"}])
    (let [entities (c/q-range db [] 100)
          alice    (first (filter #(= "Alice" (:entity/name %)) entities))
          bjorn    (first (filter #(= "Bjorn" (:entity/name %)) entities))
          alice-id (:db/id alice)
          bjorn-id (:db/id bjorn)]
      (c/transact! db [{:relationship/from alice-id
                        :relationship/to bjorn-id
                        :relationship/strength 0.5}])
      (let [rels (c/q-rels db alice-id)]
        (is (pos? (count rels)))
        (let [rel (first rels)]
          (is (= 0.5 (:relationship/strength rel)))
          (is (= bjorn-id (:db/id (:relationship/to rel)))))))))

(deftest behavioral-patterns-accumulate
  (let [db (shell/datahike-db)]
    (c/transact! db [{:entity/type :player :entity/name "Aldric"}])
    (let [entities (c/q-range db [] 100)
          player   (first (filter #(= "Aldric" (:entity/name %)) entities))
          id       (:db/id player)]
      (c/transact! db [{:db/id id :behavior/pattern :stole-high-value-items}])
      (let [patterns (c/q-patterns db id)]
        (is (some? patterns))
        (is (contains? (set patterns) :stole-high-value-items))))))

(deftest transact-updates-existing-entity
  (let [db (shell/datahike-db)]
    (c/transact! db [{:entity/type :npc :entity/name "Bjorn"}])
    (let [entities (c/q-range db [] 100)
          entity   (first entities)
          id       (:db/id entity)]
      (c/transact! db [{:db/id id :entity/name "Bjorn the Bold"}])
      (let [updated (c/q-entity db id)]
        (is (= "Bjorn the Bold" (:entity/name updated)))))))

(deftest reputation-storage
  (let [db (shell/datahike-db)]
    (c/transact! db [{:entity/type :player :entity/name "Aldric"
                      :reputation/level :notorious}])
    (let [entities (c/q-range db [] 100)
          player   (first entities)]
      (is (= :notorious (:reputation/level player))))))

(deftest store-and-query-discoveries
  (let [db (shell/datahike-db)]
    (c/transact! db [{:entity/type :player :entity/name "Aldric"}])
    (let [entities (c/q-range db [] 100)
          player   (first (filter #(= "Aldric" (:entity/name %)) entities))
          player-id (:db/id player)]
      (shell/store-discovery! db {:pattern "pays-in-exact-change"
                                  :groundedness 0.85
                                  :discovered-at 10
                                  :description "Always exact change."}
                              player-id)
      (let [patterns (c/q-player-patterns db player-id)]
        (is (= 1 (count patterns)))
        (let [p (first patterns)]
          (is (= "pays-in-exact-change" (:behavior/pattern-name p)))
          (is (= 0.85 (:behavior/groundedness p)))
          (is (= 10 (:behavior/discovered-at p)))
          (is (= "Always exact change." (:behavior/description p))))))))

(deftest store-and-query-action-history
  (let [db (shell/datahike-db)]
    (c/transact! db [{:entity/type :player :entity/name "Aldric"}])
    (let [entities (c/q-range db [] 100)
          player   (first (filter #(= "Aldric" (:entity/name %)) entities))
          player-id (:db/id player)]
      (shell/log-action! db {:turn 1 :type :intimidate :target "Guard" :outcome {:success true}} player-id)
      (shell/log-action! db {:turn 2 :type :persuade :target "Merchant" :outcome {:success false}} player-id)
      (let [history (c/q-action-history db player-id 100)]
        (is (= 2 (count history)))
        (let [h1 (first (filter #(= 1 (:action/turn %)) history))
              h2 (first (filter #(= 2 (:action/turn %)) history))]
          (is (= :intimidate (:action/type h1)))
          (is (= "Guard" (:action/target h1)))
          (is (= {:success true} (:action/outcome h1)))
          (is (= :persuade (:action/type h2)))
          (is (= "Merchant" (:action/target h2)))
          (is (= {:success false} (:action/outcome h2))))))))