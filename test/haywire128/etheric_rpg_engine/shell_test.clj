(ns haywire128.etheric-rpg-engine.shell-test
  (:require [clojure.test :refer :all]
            [haywire128.etheric-rpg-engine.core :as c]
            [haywire128.etheric-rpg-engine.shell :as shell]))

;; ══════════════════════════════════════════════════════════════════════════════
;; RLMEnv
;; ══════════════════════════════════════════════════════════════════════════════

(deftest rlm-env-basic-operations
  (let [env (shell/rlm-env {:type :action :raw "test"})]
    (is (false? (c/finalized? env)))
    (c/env-set env :test-key 42)
    (is (= 42 (c/env-get env :test-key)))
    (c/finalize! env {:result "done"})
    (is (c/finalized? env))
    (is (= {:result "done"} (c/env-get env :final)))))

(deftest rlm-env-summary-constant-size
  (let [env (shell/rlm-env {:type :action :raw "test"})
        summary (c/summary env)]
    (is (map? summary))
    (is (= :action (:prompt-type summary)))))

(deftest rlm-env-summary-truncates-coll-values
  (let [env (shell/rlm-env {:type :action :raw "test"})
        _ (c/env-set env :entities (repeat 100 {:name "x" :type :npc}))
        summary (c/summary env)
        entities (:entities (:variables summary))]
    (is (contains? entities :count))
    (is (= 100 (:count entities)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; In-Memory DB
;; ══════════════════════════════════════════════════════════════════════════════

(deftest in-memory-db-basic-operations
  (let [db (shell/in-memory-db)]
    (c/transact! db {1 {:entity/type :npc :entity/name "Bjorn"}})
    (let [entity (c/q-entity db 1)]
      (is (= :npc (:entity/type entity)))
      (is (= "Bjorn" (:entity/name entity))))))

(deftest in-memory-db-empty-queries
  (let [db (shell/in-memory-db)]
    (is (nil? (c/q-entity db 99)))
    (is (nil? (c/q-location db [:nowhere])))
    (is (empty? (c/q-range db [:anywhere] 3)))
    (is (empty? (c/q-rels db 1)))
    (is (= {} (c/q-patterns db 1)))
    (is (empty? (c/q-history db 1 (java.util.Date.))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Sub-RLM Dispatch
;; ══════════════════════════════════════════════════════════════════════════════

(deftest sub-rlm-rejects-unknown-role
  (let [llm (reify c/LLM
              (complete [_ _ _ _] {:content "{}" :cost 0}))]
    (is (thrown? Exception (shell/sub-rlm llm {:test 1} :bogus)))))

(deftest sub-rlm-handles-malformed-llm-output
  (let [llm (reify c/LLM
              (complete [_ _ _ _] {:content "not edn at all" :cost 5}))]
    (let [result (shell/sub-rlm llm {:test 1} :scribe)]
      (is (contains? result :parse-error))
      (is (= "not edn at all" (:raw result))))))

(deftest sub-rlm-parses-edn-response
  (let [llm (reify c/LLM
              (complete [_ _ _ _] {:content "{:narrative \"test\"}" :cost 10}))]
    (let [result (shell/sub-rlm llm {:test 1} :scribe)]
      (is (= "test" (:narrative result))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Root System Prompt
;; ══════════════════════════════════════════════════════════════════════════════

(deftest root-system-prompt-is-non-empty
  (let [prompt (shell/root-system-prompt)]
    (is (string? prompt))
    (is (not (clojure.string/blank? prompt)))))

(deftest root-system-prompt-references-key-functions
  (let [prompt (shell/root-system-prompt)]
    (is (clojure.string/includes? prompt "sub-rlm"))
    (is (clojure.string/includes? prompt "cast-dice"))
    (is (clojure.string/includes? prompt "finalize!"))))

(deftest root-system-prompt-references-all-seven-roles
  (let [prompt (shell/root-system-prompt)]
    (doseq [role [:cartographer :harbinger :forger :scout :oracle :witness :scribe]]
      (is (clojure.string/includes? prompt (name role))
          (str "Missing role: " role)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Game State
;; ══════════════════════════════════════════════════════════════════════════════

(deftest start-game-returns-correct-shape
  (let [state (shell/start-game {:player/name "Aldric" :player/genre :medieval-fantasy})]
    (is (map? state))
    (is (contains? state :llm))
    (is (contains? state :db))
    (is (contains? state :env))
    (is (contains? state :config))
    (is (satisfies? c/RLMEnv (:env state)))
    (is (satisfies? c/WorldDB (:db state)))
    (is (satisfies? c/LLM (:llm state)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Integration Tests (require OPENROUTER_API_KEY)
;; ══════════════════════════════════════════════════════════════════════════════

(defn integration-test?
  "Returns true if OPENROUTER_API_KEY is set and non-empty."
  []
  (let [k (System/getenv "OPENROUTER_API_KEY")]
    (and k (not (clojure.string/blank? k)))))

(deftest ^:integration llm-client-returns-valid-response
  (if-not (integration-test?)
    (println "SKIPPED: OPENROUTER_API_KEY not set")
    (let [llm (shell/llm-client)
          result (c/complete llm
                  [{:role :user :content "Reply with exactly: {:status :ok}"}]
                  "deepseek/deepseek-v4-flash"
                  {:max-tokens 128})]
      (is (map? result))
      (is (string? (:content result)))
      (is (number? (:cost result)))
      (is (> (:cost result) 0)))))

(deftest ^:integration sub-rlm-oracle-returns-valid-edn
  (if-not (integration-test?)
    (println "SKIPPED: OPENROUTER_API_KEY not set")
    (let [llm (shell/llm-client)
          context {:action {:type :intimidate :intent "test" :approach "test"}
                   :dice {:fortune 15 :folly 5 :direction :fortune :magnitude 0.75}
                   :actor {:name "Aldric" :traits #{:silver-tongued}
                           :trait-info {:silver-tongued {:breadth :broad :domain :social}}}
                   :target {:name "TestNPC" :traits #{}
                            :trait-info {}}
                   :location {:traits #{}}
                   :relationship {:strength 0.0}
                   :actor-reputation :neutral}
          result (shell/sub-rlm llm context :oracle)]
      (is (map? result))
      (is (contains? result :outcome))
      (is (contains? result :narrative))
      (is (contains? result :side-effects))
      (is (boolean? (get-in result [:outcome :success])))
      (is (string? (:narrative result))))))

(deftest ^:integration sub-rlm-scout-returns-valid-edn
  (if-not (integration-test?)
    (println "SKIPPED: OPENROUTER_API_KEY not set")
    (let [llm (shell/llm-client)
          context {:player {:name "Aldric" :traits #{:keen-eyed}
                            :trait-info {:keen-eyed {:breadth :broad :domain :perception}}
                            :reputation :neutral}
                   :location {:name "Test Tavern" :loc-type :tavern
                              :traits #{:cozy} :description "A cozy tavern"}
                   :entities [{:name "Hilda" :profession :innkeeper
                               :traits #{:friendly} :personality {:disposition :warm}
                               :appearance "A stout woman" :stubbed? false}]
                   :relationships [{:entity "Hilda" :strength 0.3 :kind :friendly}]
                   :time {:of-day :evening :weather "clear" :elapsed "first visit"}}
          result (shell/sub-rlm llm context :scout)]
      (is (map? result))
      (is (string? (:narrative result)))
      (is (> (count (:narrative result)) 20)))))

(deftest ^:integration sub-rlm-witness-discovers-patterns
  (if-not (integration-test?)
    (println "SKIPPED: OPENROUTER_API_KEY not set")
    (let [llm (shell/llm-client)
          context {:player {:name "Aldric" :traits #{:silver-tongued}
                            :current-reputation :neutral}
                   :action-history
                   [{:turn 45 :action {:type :intimidate :target "Bjorn"}
                     :outcome {:success true :degree :partial}}
                    {:turn 46 :action {:type :intimidate :target "Guard"}
                     :outcome {:success true :degree :full}}
                    {:turn 47 :action {:type :intimidate :target "Hilda"}
                     :outcome {:success false :degree :none}}]
                   :current-relationships
                   [{:entity "Bjorn" :kind :wary :strength -0.2}
                    {:entity "Hilda" :kind :friendly :strength 0.4}]
                   :known-patterns []
                   :pattern-window {:turns 100}
                   :genre :medieval-fantasy}
          result (shell/sub-rlm llm context :witness)]
      (is (map? result))
      (is (vector? (:discoveries result)))
      (is (vector? (:raw-observations result))))))

(deftest ^:integration sub-rlm-cartographer-produces-taxonomy
  (if-not (integration-test?)
    (println "SKIPPED: OPENROUTER_API_KEY not set")
    (let [llm (shell/llm-client)
          context {:genre :medieval-fantasy
                   :parent {:path [:Aethelgard] :depth 1 :name "Aethelgard"
                            :traits #{:ancient}}
                   :target-depth 4}
          result (shell/sub-rlm llm context :cartographer)]
      (is (map? result))
      (is (vector? (:nodes result)))
      (is (pos? (count (:nodes result)))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Configuration Management (Aero)
;; ══════════════════════════════════════════════════════════════════════════════

(deftest config-load-and-save-operations
  (testing "Loading default config"
    (let [cfg (shell/load-config)]
      (is (map? cfg))
      (is (contains? cfg :player/name))
      (is (contains? cfg :player/genre))))
  
  (testing "Loading with custom profile"
    (let [test-path "resources/test-config-profile.edn"
          content "{:player/name \"Hiro\"
                    :player/genre :cyberpunk
                    :player/traits #profile {:default #{:hacker :neon-drenched}
                                             :custom #{:keen-eyed :silver-tongued}}}"
          _ (spit test-path content)
          cfg (shell/load-config :custom test-path)]
      (is (map? cfg))
      (is (contains? cfg :player/traits))
      (is (contains? (get cfg :player/traits) :keen-eyed))
      ;; Cleanup
      (let [f (clojure.java.io/file test-path)]
        (when (.exists f) (.delete f)))))
  
  (testing "Saving and archiving configuration"
    (let [test-path "resources/test-config.edn"
          old-cfg (shell/load-config)
          temp-cfg (assoc old-cfg :player/name "AeroTestPlayer" :player/genre :sci-fi)]
      ;; Delete test-config if it exists to make test clean
      (let [f (clojure.java.io/file test-path)]
        (when (.exists f) (.delete f)))
      ;; Save config to test path
      (shell/save-config temp-cfg test-path)
      (let [loaded (shell/load-config test-path)]
        (is (= "AeroTestPlayer" (:player/name loaded)))
        (is (= :sci-fi (:player/genre loaded))))
      ;; Overwrite config to trigger archive
      (shell/save-config old-cfg test-path)
      (let [restored (shell/load-config test-path)]
        (is (= (:player/name old-cfg) (:player/name restored)))
        (is (= (:player/genre old-cfg) (:player/genre restored))))
      ;; Clean up test config
      (let [f (clojure.java.io/file test-path)]
        (when (.exists f) (.delete f)))
      ;; Verify archive was created
      (let [archive-dir (clojure.java.io/file "resources/config-archive")]
        (is (.exists archive-dir))
        (is (pos? (count (.listFiles archive-dir))))))))

(deftest validate-config-checks
  (testing "Valid config map"
    (is (nil? (shell/validate-config {:player/name "Aldric"
                                      :player/genre :medieval-fantasy
                                      :player/traits #{:keen-eyed :silver-tongued}
                                      :llm/default-model "deepseek"
                                      :llm/max-tokens 4096}))))
  (testing "Missing player name"
    (is (thrown? clojure.lang.ExceptionInfo (shell/validate-config {:player/genre :medieval-fantasy}))))
  (testing "Invalid traits set"
    (is (thrown? clojure.lang.ExceptionInfo (shell/validate-config {:player/name "Aldric"
                                                                    :player/genre :medieval-fantasy
                                                                    :player/traits [:keen-eyed]}))))
  (testing "Invalid max tokens"
    (is (thrown? clojure.lang.ExceptionInfo (shell/validate-config {:player/name "Aldric"
                                                                    :player/genre :medieval-fantasy
                                                                    :llm/max-tokens -100}))))
  (testing "Invalid default model type"
    (is (thrown? clojure.lang.ExceptionInfo (shell/validate-config {:player/name "Aldric"
                                                                    :player/genre :medieval-fantasy
                                                                    :llm/default-model :some-keyword})))))

(deftest validate-code-checks
  (testing "Safe code passes"
    (is (nil? (shell/validate-code "(let [x (cast-dice)] (println x))"))))
  (testing "Code containing shell execution is rejected"
    (is (thrown? clojure.lang.ExceptionInfo (shell/validate-code "(clojure.java.shell/sh \"ls\")"))))
  (testing "Code containing slurp is rejected"
    (is (thrown? clojure.lang.ExceptionInfo (shell/validate-code "(slurp \"secrets.txt\")"))))
  (testing "Code containing System/exit is rejected"
    (is (thrown? clojure.lang.ExceptionInfo (shell/validate-code "(System/exit 0)"))))
  (testing "Code containing nested eval is rejected"
    (is (thrown? clojure.lang.ExceptionInfo (shell/validate-code "(eval '(println 1))")))))

(deftest scrub-third-wall-test
  (testing "scrub-third-wall removes dice, rolls, and general mechanic references"
    (let [ctx {:player {:traits #{:keen-eyed :sword-saint}}}
          raw-text "Because of a roll of your fortune die and your keen-eyed trait, you successfully rolled a d20."
          scrubbed (shell/scrub-third-wall raw-text ctx)]
      (is (not (clojure.string/includes? scrubbed "roll")))
      (is (not (clojure.string/includes? scrubbed "fortune die")))
      (is (not (clojure.string/includes? scrubbed "keen-eyed")))
      (is (not (clojure.string/includes? scrubbed "trait")))
      (is (not (clojure.string/includes? scrubbed "d20")))))
  (testing "scrub-third-wall maps dynamic traits correctly"
    (let [ctx {:actor {:traits #{:sword-saint}}}
          raw-text "You show off your sword-saint prowess."
          scrubbed (shell/scrub-third-wall raw-text ctx)]
      (is (not (clojure.string/includes? scrubbed "sword-saint")))
      (is (clojure.string/includes? scrubbed "mastery of the blade")))))

(deftest travel-movement-updates-current-location
  (testing "Movement action updates the current location in env"
    (let [db (shell/in-memory-db)
          _ (c/transact! db {1 {:entity/type :player
                                :entity/name "Sage"
                                :trait/set   #{:keen-eyed}}})
          player-id 1
          ;; Mock LLM returns code to update current location and finalize
          mock-root-code "(do (env-set !env :current-location [:Eldoria :Mistveil :RiverholdSquare]) (finalize! !env {:narrative \"You arrive at Riverhold Square.\" :success true}))"
          llm (reify c/LLM
                (complete [_ messages _model _opts]
                  {:content mock-root-code :cost 0}))
          env (shell/rlm-env {:type :action :raw "go to the Riverhold Square"})]
      (c/env-set env :player-id player-id)
      (c/env-set env :current-location [:Eldoria :Mistveil :Inn])
      (let [result (shell/rlm-loop llm db env {:type :action :raw "go to the Riverhold Square"} :max-iterations 3)]
        (is (:success result))
        (is (= [:Eldoria :Mistveil :RiverholdSquare] (c/env-get env :current-location)))
        (is (= "You arrive at Riverhold Square." (get-in result [:result :narrative])))))))
