(ns haywire128.etheric-rpg-engine.core-test
  (:require [clojure.test :refer :all]
            [haywire128.etheric-rpg-engine.core :as core]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Dice
;; ══════════════════════════════════════════════════════════════════════════════

(deftest cast-dice-returns-valid-shape
  (let [dice (core/cast-dice)]
    (is (map? dice))
    (is (contains? dice :fortune))
    (is (contains? dice :folly))
    (is (contains? dice :direction))
    (is (contains? dice :magnitude))))

(deftest cast-dice-numeric-range
  (let [dice (core/cast-dice)]
    (is (<= 1 (:fortune dice) 20))
    (is (<= 1 (:folly dice) 20))
    (is (<= 0.0 (:magnitude dice) 1.0))))

(deftest cast-dice-direction-corresponds-to-higher-die
  (let [dice (core/cast-dice)]
    (if (> (:fortune dice) (:folly dice))
      (is (= :fortune (:direction dice)))
      (is (= :folly (:direction dice))))))

(deftest cast-dice-magnitude-is-higher-over-20
  (let [dice (core/cast-dice)
        expected (double (/ (max (:fortune dice) (:folly dice)) 20))]
    (is (= expected (:magnitude dice)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Relationships
;; ══════════════════════════════════════════════════════════════════════════════

(deftest relationship-kind-boundaries
  (are [strength expected] (= expected (core/relationship-kind strength))
    -1.0  :mortal-enemy
    -0.9  :mortal-enemy
    -0.8  :enemy
    -0.5  :hostile
    -0.2  :wary
    -0.01 :wary
    0.0   :neutral
    0.05  :neutral
    0.1   :acquaintance
    0.3   :friendly
    0.5   :close-ally
    0.7   :trusted-confidant
    0.9   :devoted
    1.0   :devoted))

(deftest relationship-kind-symmetric-near-zero
  (is (= :wary (core/relationship-kind -0.01)))
  (is (= :neutral (core/relationship-kind 0.0))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Behaviors
;; ══════════════════════════════════════════════════════════════════════════════

(deftest action-history-summary-filters-by-window
  (let [actions [{:turn 1 :action {:type :sneak} :outcome {:success true}}
                 {:turn 50 :action {:type :intimidate} :outcome {:success false}}
                 {:turn 99 :action {:type :barter} :outcome {:success true}}]
        result (core/action-history-summary actions 100)]
    (is (= 3 (count result)))))

(deftest action-history-summary-excludes-outside-window
  (let [actions [{:turn 50 :action {:type :sneak} :outcome {:success true}}
                 {:turn 150 :action {:type :barter} :outcome {:success true}}]
        result (core/action-history-summary actions 100)]
    (is (= 1 (count result)))
    (is (= 50 (:turn (first result))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Location
;; ══════════════════════════════════════════════════════════════════════════════

(deftest perceptual-depth-capped-at-default
  (is (= 3 (core/perceptual-depth 5 10))))

(deftest perceptual-depth-minimum-one
  (is (= 1 (core/perceptual-depth 7 7))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Scout Caching
;; ══════════════════════════════════════════════════════════════════════════════

(deftest cache-key-is-deterministic
  (is (= (core/cache-key [:a :b] 1 "0")
         (core/cache-key [:a :b] 1 "0"))))

(deftest cache-key-differs-by-location
  (is (not= (core/cache-key [:a] 1 "0")
            (core/cache-key [:b] 1 "0"))))

(deftest cache-key-differs-by-player
  (is (not= (core/cache-key [:a] 1 "0")
            (core/cache-key [:a] 2 "0"))))

(deftest cache-key-differs-by-time
  (is (not= (core/cache-key [:a] 1 "0")
            (core/cache-key [:a] 1 "1 day"))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Player Input
;; ══════════════════════════════════════════════════════════════════════════════

(deftest parse-player-input-non-empty
  (let [result (core/parse-player-input "attack the goblin")]
    (is (= :unknown (:type result)))
    (is (= "attack the goblin" (:raw result)))))

(deftest parse-player-input-empty
  (is (= :invalid (:type (core/parse-player-input "")))))

(deftest parse-player-input-nil
  (is (= :invalid (:type (core/parse-player-input nil)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Stub Detection
;; ══════════════════════════════════════════════════════════════════════════════

(deftest stub-detection
  (is (true? (core/stub? {:stubbed? true :name "Bjorn"})))
  (is (false? (core/stub? {:stubbed? false :name "Hilda"})))
  (is (false? (core/stub? {:name "Hilda"}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constants
;; ══════════════════════════════════════════════════════════════════════════════

(deftest constants-sanity
  (is (pos? core/default-max-iterations))
  (is (= 20 core/fortune-sides))
  (is (= 20 core/folly-sides)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Role Registry
;; ══════════════════════════════════════════════════════════════════════════════

(deftest role-registry-has-all-seven-roles
  (is (= 7 (count core/role-registry)))
  (are [role] (contains? core/role-registry role)
    :cartographer :harbinger :forger :scout :oracle :witness :scribe))

(deftest role-registry-entries-have-prompt-and-model
  (doseq [[role entry] core/role-registry]
    (testing (str role)
      (is (string? (:prompt entry)))
      (is (string? (or (:model entry) core/default-model)))
      (is (not (clojure.string/blank? (:prompt entry)))))))
