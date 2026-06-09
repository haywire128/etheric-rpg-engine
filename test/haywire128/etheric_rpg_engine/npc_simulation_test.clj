(ns haywire128.etheric-rpg-engine.npc-simulation-test
  (:require [clojure.test :refer :all]
            [haywire128.etheric-rpg-engine.core :as c]
            [haywire128.etheric-rpg-engine.shell :as shell]
            [datahike.api :as d]))

(deftest stance-persistence-test
  (testing "Stance transacting, querying, and updating"
    (let [db (shell/datahike-db)
          ;; Create entities
          _ (c/transact! db [{:entity/type :player :entity/name "Licky" :trait/set #{}}
                             {:entity/type :npc :entity/name "Guard" :trait/set #{}}
                             {:entity/type :npc :entity/name "Lila" :trait/set #{}}])
          ;; Initial stances
          _ (shell/set-stance-by-names! db "Guard" "Lila" :defending 0.8)
          _ (shell/set-stance-by-names! db "Guard" "Licky" :wary 0.5)
          stances-1 (shell/q-stances db)]
      
      (is (= 2 (count stances-1)))
      (is (some #(= {:source "Guard" :target "Lila" :type :defending :intensity 0.8} %) stances-1))
      (is (some #(= {:source "Guard" :target "Licky" :type :wary :intensity 0.5} %) stances-1))
      
      ;; Update existing stance
      (shell/set-stance-by-names! db "Guard" "Licky" :threatening 0.9)
      (let [stances-2 (shell/q-stances db)]
        (is (= 2 (count stances-2)))
        (is (some #(= {:source "Guard" :target "Licky" :type :threatening :intensity 0.9} %) stances-2))))))

(deftest oracle-and-scribe-stance-integration-test
  (testing "Oracle and Scribe handle stances and npc-actions correctly"
    ;; Mock LLM
    (let [llm-calls (atom [])
          mock-llm (reify c/LLM
                     (complete [_ messages _model _opts]
                       (let [sys (-> messages first :content)
                             usr (-> messages second :content)]
                         (swap! llm-calls conj {:system sys :user usr})
                         (cond
                           (clojure.string/includes? sys "Oracle function")
                           {:content (pr-str {:outcome {:success true :degree :full :magnitude-applied 0.8}
                                              :narrative "You lick Lila's face. She gasps and steps back."
                                              :side-effects []
                                              :npc-actions [{:actor "Guard" :action :intervene :target "Licky" :reason "Guard defends Lila."}]
                                              :stance-updates [{:source "Guard" :target "Licky" :type :hostile :intensity 0.85}]})
                            :cost 0}
                           
                           (clojure.string/includes? sys "Scribe function")
                           {:content (pr-str {:narrative "You lick Lila's face. The guard immediately steps in, pointing his sword at you."
                                              :emergent-effects []
                                              :suggestions []})
                            :cost 0}
                           
                           :else
                           {:content "{}" :cost 0}))))
          db (shell/datahike-db)
          env (shell/rlm-env "Start prompt text")
          ;; Setup DB
          _ (c/transact! db [{:entity/type :player :entity/name "Licky" :trait/set #{}}
                             {:entity/type :npc :entity/name "Guard" :trait/set #{}}
                             {:entity/type :npc :entity/name "Lila" :trait/set #{}}])
          player-id (ffirst (d/q '[:find ?e :where [?e :entity/type :player]] (d/db (:conn db))))
          _ (shell/set-stance-by-names! db "Guard" "Lila" :defending 0.8)
          
          ;; Evaluate orchestrator code that queries stances, calls oracle, processes stance updates, and calls scribe
          code-str "(let [player-id (env-get !env :player-id)
                          start-prompt (env-get !env :prompt)
                          player (q-entity !db player-id)
                          player-name (:entity/name player)
                          loc-path (env-get !env :current-location)
                          location (q-location !db loc-path)
                          stances (q-stances !db)
                          dice (cast-dice)
                          ;; Call Oracle passing stances
                          result (sub-rlm {:action {:type :lick :intent \"I lick Lila\" :raw \"I lick Lila\"}
                                           :dice dice
                                           :last-turn nil
                                           :actor {:name player-name :traits #{} :trait-info {}}
                                           :target {:name \"Lila\" :traits #{} :trait-info {}}
                                           :location {:name \"Village Square\" :traits #{} :atmosphere \"tense\"}
                                           :relationship {:strength 0.0}
                                           :actor-reputation :neutral
                                           :player-patterns []
                                           :stances stances}
                                          :oracle)]
                      ;; Process stance updates
                      (doseq [su (:stance-updates result)]
                        (set-stance-by-names! !db (:source su) (:target su) (:type su) (:intensity su)))
                      ;; Call Scribe passing npc-actions
                      (let [scribe-res (sub-rlm {:player {:name player-name :traits #{} :reputation :neutral}
                                                 :turn-events [{:action {:type :lick :target \"Lila\"} :outcome result}]
                                                 :npc-actions (:npc-actions result)
                                                 :behavioral-scan {}
                                                 :relationship-deltas []
                                                 :location {:name \"Village Square\" :traits #{} :atmosphere \"tense\"}
                                                 :last-turn nil}
                                                :scribe)]
                        (finalize! !env {:narrative (:narrative scribe-res) :success true})))"
          _ (c/env-set env :player-id player-id)
          _ (c/env-set env :current-location [:world :settlement])
          _ (shell/store-location! db {:location/path-str "world/settlement" :location/name "Village Square" :location/entities []})
          
          eval-res (shell/rlm-eval code-str mock-llm db env)
          final-env (c/env-get env :final)
          final-stances (shell/q-stances db)]
      
      (is (:success final-env))
      (is (= "You lick Lila's face. The guard immediately steps in, pointing his sword at you." (:narrative final-env)))
      ;; Verify stance updates was applied to DB
      (is (some #(= {:source "Guard" :target "Licky" :type :hostile :intensity 0.85} %) final-stances))
      ;; Verify LLM calls received stances/npc-actions
      (is (= 2 (count @llm-calls)))
      (is (clojure.string/includes? (:user (first @llm-calls)) "{:source \"Guard\", :target \"Lila\", :type :defending, :intensity 0.8}"))
      (is (clojure.string/includes? (:user (second @llm-calls)) "{:actor \"Guard\", :action :intervene, :target \"Licky\", :reason \"Guard defends Lila.\"}")))))
