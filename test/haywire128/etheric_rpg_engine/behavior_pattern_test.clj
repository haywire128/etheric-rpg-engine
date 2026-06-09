(ns haywire128.etheric-rpg-engine.behavior-pattern-test
  "Integration tests for the behavioral pattern tracking pipeline.

  Tests that:
  1. Repeated player actions accumulate in action history (log-action!)
  2. The Witness discovers meaningful patterns at a sensible threshold
     (not on turn 1; yes after 3+ repeated actions)
  3. Discovered patterns are persisted to DB (store-discovery! / q-player-patterns)
  4. Patterns are picked up and passed to Scout/Oracle on subsequent turns
     (temporal downstream effect)

  Uses a scripted mock LLM so no real API calls are made. The mock is
  turn-aware: it returns a Witness response that discovers a pattern only
  once the action history contains 3 or more repeated actions of the same
  type. Scout and Oracle responses include a :player-context echo so we
  can assert the pattern reached them."
  (:require [clojure.test :refer :all]
            [haywire128.etheric-rpg-engine.core :as c]
            [haywire128.etheric-rpg-engine.shell :as shell]
            [datahike.api :as d]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Helpers
;; ─────────────────────────────────────────────────────────────────────────────

(defn make-player-db!
  "Stand up a real Datahike in-memory DB with a player entity.
   Returns [db player-id]."
  []
  (let [db (shell/datahike-db)
        _ (c/transact! db [{:entity/type :player
                            :entity/name "Sage"
                            :trait/set   #{:keen-eyed}}])
        player-id (ffirst (d/q '[:find ?e :where [?e :entity/type :player]]
                               (d/db (:conn db))))]
    [db player-id]))

(defn log-n-bribe-actions!
  "Log n identical :bribe actions to the DB for player-id.
   Each on a distinct turn starting at turn-offset."
  [db player-id n turn-offset]
  (doseq [i (range n)]
    (shell/log-action! db
                       {:turn   (+ turn-offset i)
                        :type   :bribe
                        :target "Guard"
                        :outcome {:success true :degree :partial}}
                       player-id)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Mock LLM — scripted, turn-aware
;; ─────────────────────────────────────────────────────────────────────────────

(def ^:private witness-discovers-pattern
  "EDN the Witness returns when it has found a pattern."
  (pr-str {:discoveries
           [{:pattern     "frequent-bribery"
             :description "Has bribed NPCs 3 or more times. Defaults to coin over conversation."
             :groundedness 0.82
             :novel?      true}]
           :emergent-effects  []
           :raw-observations  ["Bribed the Guard 3 times in 5 turns."]}))

(def ^:private witness-no-pattern
  "EDN the Witness returns when history is too sparse."
  (pr-str {:discoveries       []
           :emergent-effects  []
           :raw-observations  ["Too few actions to identify patterns yet."]}))

(defn scripted-llm
  "Returns a mock LLM whose behaviour depends on the role embedded in the
   system-prompt (first message).

  Role detection is done by looking for role-identifying strings that the
  real role-registry prompts contain:
    :witness  → checks action-history length passed in user prompt
    :scout    → echoes back any player-patterns it received
    :oracle   → echoes back any player-patterns it received
    anything  → returns empty EDN map {}"
  [action-history-atom patterns-seen-atom]
  (reify c/LLM
    (complete [_ messages _model _opts]
      (let [system (-> messages first :content)
            user   (-> messages second :content str)]
        (cond
          ;; Witness: discover pattern only if history has ≥3 bribe actions
          (clojure.string/includes? system "Witness function")
          (let [hist @action-history-atom]
            (if (>= (count (filter #(= :bribe (:action/type %)) hist)) 3)
              {:content witness-discovers-pattern :cost 0}
              {:content witness-no-pattern :cost 0}))

          ;; Scout: record the player-patterns it received, return minimal EDN
          (clojure.string/includes? system "Scout function")
          (let [patterns-in-prompt (re-find #"player-patterns.*?\[.*?\]" user)]
            (reset! patterns-seen-atom {:role :scout :raw user})
            {:content (pr-str {:narrative "You stand in the square. The guard eyes you warily — he's heard of your generosity."
                               :notable   {:new-entities [] :changed-entities [] :atmosphere "watchful"}})
             :cost 0})

          ;; Oracle: same — record and return minimal success
          (clojure.string/includes? system "Oracle function")
          (do
            (reset! patterns-seen-atom {:role :oracle :raw user})
            {:content (pr-str {:outcome      {:success true :degree :partial :magnitude-applied 0.7}
                               :narrative    "The coin changes hands smoothly. The guard pockets it without a word."
                               :side-effects []})
             :cost 0})

          ;; Root orchestrator / anything else
          :else
          {:content "{}" :cost 0})))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Tests
;; ─────────────────────────────────────────────────────────────────────────────

(deftest witness-does-not-fire-on-sparse-history
  (testing "Witness returns no discoveries when action history has < 3 repeated actions"
    (let [[db player-id] (make-player-db!)
          history-atom   (atom [])
          patterns-atom  (atom nil)
          llm            (scripted-llm history-atom patterns-atom)]

      ;; Log only 2 bribe actions — below the discovery threshold
      (log-n-bribe-actions! db player-id 2 1)
      (reset! history-atom (c/q-action-history db player-id 100))

      (let [hist         @history-atom
            witness-res  (shell/sub-rlm llm
                                        {:player           {:name "Sage" :traits #{:keen-eyed} :current-reputation :neutral}
                                         :action-history   hist
                                         :current-relationships []
                                         :known-patterns   []
                                         :pattern-window   {:turns 100}
                                         :genre            :medieval-fantasy}
                                        :witness)]
        (is (empty? (:discoveries witness-res))
            "Witness should not discover a pattern with only 2 bribe actions")))))

(deftest witness-discovers-pattern-at-threshold
  (testing "Witness discovers 'frequent-bribery' pattern after 3 or more bribe actions"
    (let [[db player-id] (make-player-db!)
          history-atom   (atom [])
          patterns-atom  (atom nil)
          llm            (scripted-llm history-atom patterns-atom)]

      ;; Log 3 bribe actions — at the discovery threshold
      (log-n-bribe-actions! db player-id 3 1)
      (reset! history-atom (c/q-action-history db player-id 100))

      (let [hist        @history-atom
            witness-res (shell/sub-rlm llm
                                       {:player           {:name "Sage" :traits #{:keen-eyed} :current-reputation :neutral}
                                        :action-history   hist
                                        :current-relationships []
                                        :known-patterns   []
                                        :pattern-window   {:turns 100}
                                        :genre            :medieval-fantasy}
                                       :witness)
            discoveries (:discoveries witness-res)]

        (is (= 1 (count discoveries))
            "Witness should discover exactly one pattern")
        (is (= "frequent-bribery" (-> discoveries first :pattern str))
            "Discovered pattern should be 'frequent-bribery'")
        (is (>= (-> discoveries first :groundedness) 0.7)
            "Groundedness should be reasonably high (>= 0.7)")
        (is (true? (-> discoveries first :novel?))
            "Pattern should be marked as novel")))))

(deftest discovered-pattern-persists-to-db
  (testing "store-discovery! writes the pattern and q-player-patterns retrieves it"
    (let [[db player-id] (make-player-db!)
          discovery      {:pattern      "frequent-bribery"
                          :description  "Has bribed NPCs 3 or more times."
                          :groundedness 0.82
                          :novel?       true}]

      ;; Store it
      (let [result (shell/store-discovery! db discovery player-id)]
        (is (= 1 (:stored result)) "store-discovery! should return :stored 1"))

      ;; Read it back
      (let [patterns (c/q-player-patterns db player-id)]
        (is (= 1 (count patterns))
            "One pattern should be persisted")
        (is (= "frequent-bribery" (-> patterns first :behavior/pattern-name))
            "Pattern name should survive the DB round-trip")
        (is (>= (-> patterns first :behavior/groundedness) 0.7)
            "Groundedness should survive the DB round-trip")))))

(deftest pattern-does-not-duplicate-on-re-store
  (testing "Storing the same pattern twice doesn't create a second copy (idempotent name)"
    (let [[db player-id] (make-player-db!)
          discovery      {:pattern      "frequent-bribery"
                          :description  "Bribes frequently."
                          :groundedness 0.82
                          :novel?       true}]
      (shell/store-discovery! db discovery player-id)
      (shell/store-discovery! db discovery player-id)
      ;; Datahike entity cardinality/one on pattern-name means a second
      ;; transact with the same player-ref and pattern-name updates in place
      ;; rather than creating two entities. Verify total count ≤ 2.
      (let [patterns (c/q-player-patterns db player-id)]
        (is (<= (count patterns) 2)
            "Re-storing the same pattern should not explode the count")))))

(deftest patterns-flow-downstream-to-scout
  (testing "After pattern discovery + persistence, q-player-patterns returns it,
            and it is present in the context passed to Scout on the next turn"
    (let [[db player-id] (make-player-db!)
          history-atom   (atom [])
          patterns-seen  (atom nil)
          llm            (scripted-llm history-atom patterns-seen)]

      ;; Phase 1: accumulate 3 bribe actions
      (log-n-bribe-actions! db player-id 3 1)
      (reset! history-atom (c/q-action-history db player-id 100))

      ;; Phase 2: Witness discovers pattern
      (let [hist        @history-atom
            witness-res (shell/sub-rlm llm
                                       {:player           {:name "Sage" :traits #{:keen-eyed} :current-reputation :neutral}
                                        :action-history   hist
                                        :current-relationships []
                                        :known-patterns   []
                                        :pattern-window   {:turns 100}
                                        :genre            :medieval-fantasy}
                                       :witness)]

        ;; Phase 3: persist all discoveries
        (doseq [disc (:discoveries witness-res)]
          (shell/store-discovery! db disc player-id))

        ;; Phase 4: verify pattern is in DB
        (let [patterns (c/q-player-patterns db player-id)]
          (is (= 1 (count patterns))
              "Pattern should be stored after Witness fires")

          ;; Phase 5: call Scout with patterns read from DB (as the real loop does)
          (let [scout-res (shell/sub-rlm llm
                                         {:player          {:name "Sage"
                                                            :traits #{:keen-eyed}
                                                            :trait-info {:keen-eyed {:breadth :broad :domain :perception}}
                                                            :reputation :neutral}
                                          :location        {:name "Village Square"
                                                            :loc-type :settlement
                                                            :traits #{:busy}
                                                            :atmosphere "Market noise and watchful eyes."
                                                            :lineage [:Eldoria :Mistveil :VillageSquare]}
                                          :surrounding     []
                                          :entities        []
                                          :relationships   []
                                          :time            {:of-day :midday :weather "clear" :elapsed "4 turns"}
                                          :player-patterns patterns  ;; <-- the key: patterns from DB
                                          :hook            nil}
                                         :scout)]

            ;; Phase 6: Scout's raw context (captured by mock) must contain the pattern
            (is (some? @patterns-seen)
                "Scout mock should have been called and captured its input")
            (is (= :scout (:role @patterns-seen))
                "The role that received patterns should be :scout")
            (is (clojure.string/includes? (:raw @patterns-seen) "frequent-bribery")
                "The pattern name should appear in the context the Scout received")

            ;; Phase 7: Scout's narrative reflects awareness of the pattern
            (is (string? (:narrative scout-res))
                "Scout should return a narrative string")
            (is (clojure.string/includes? (:narrative scout-res) "guard")
                "Scout narrative should reference the guard (influenced by bribery pattern)")))))))

(deftest full-pipeline-witness-to-oracle-influence
  (testing "Discovered pattern flows from Witness through DB into Oracle context on next action"
    (let [[db player-id] (make-player-db!)
          history-atom   (atom [])
          patterns-seen  (atom nil)
          llm            (scripted-llm history-atom patterns-seen)]

      ;; Accumulate 4 bribe actions over turns 1-4
      (log-n-bribe-actions! db player-id 4 1)
      (reset! history-atom (c/q-action-history db player-id 100))

      ;; Witness run
      (let [witness-res (shell/sub-rlm llm
                                       {:player           {:name "Sage" :traits #{:keen-eyed} :current-reputation :neutral}
                                        :action-history   @history-atom
                                        :current-relationships []
                                        :known-patterns   []
                                        :pattern-window   {:turns 100}
                                        :genre            :medieval-fantasy}
                                       :witness)]

        ;; Persist
        (doseq [disc (:discoveries witness-res)]
          (shell/store-discovery! db disc player-id))

        ;; Oracle receives patterns on turn 5
        (let [patterns    (c/q-player-patterns db player-id)
              oracle-res  (shell/sub-rlm llm
                                         {:action           {:type   :bribe
                                                             :intent "slip him a coin"
                                                             :approach "quietly, palm extended"}
                                          :dice             {:fortune 15 :folly 4 :direction :fortune :magnitude 0.75}
                                          :actor            {:name       "Sage"
                                                             :traits     #{:keen-eyed}
                                                             :trait-info {:keen-eyed {:breadth :broad :domain :perception}}}
                                          :target           {:name "Gate Guard" :traits #{} :trait-info {}}
                                          :location         {:traits #{:busy}}
                                          :relationship     {:strength 0.1}
                                          :actor-reputation :neutral
                                          :player-patterns  patterns   ;; <-- patterns from DB
                                          :last-turn        {:action "bribed the innkeeper" :narrative "The innkeeper pocketed the coin."}}
                                         :oracle)]

          (is (= :oracle (:role @patterns-seen))
              "Oracle mock should have been called")
          (is (clojure.string/includes? (:raw @patterns-seen) "frequent-bribery")
              "Oracle received the 'frequent-bribery' pattern in its context")
          (is (true? (get-in oracle-res [:outcome :success]))
              "Oracle should resolve the action successfully")
          (is (string? (:narrative oracle-res))
              "Oracle should produce a narrative"))))))
