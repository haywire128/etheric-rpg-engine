(require '[haywire128.etheric-rpg-engine.shell :as shell]
         '[haywire128.etheric-rpg-engine.core :as c]
         '[haywire128.etheric-rpg-engine.cli :as cli])

(defn run-live-playtest []
  (println (cli/banner))
  (println)
  (flush)
  
  (cli/print-system "Loading configuration...")
  (flush)
  (let [config (shell/load-config)]
    (cli/print-system (str "Initializing world for " (:player/name config) " (" (name (:player/genre config)) ")..."))
    (flush)
    (let [game-state (shell/start-game config)
          player-id (get-in game-state [:config :player/id])
          start-prompt (c/env-get (:env game-state) :prompt)]
      
      ;; Turn 1: Initialization / World Generation
      (cli/print-system "Turn 1: Invoking RLM Loop for game startup (Cartographer & Scout)...")
      (flush)
      (let [start-result (shell/rlm-loop (:llm game-state) (:db game-state) (:env game-state)
                                        start-prompt
                                        :max-iterations 10)]
        (if-not (:success start-result)
          (do
            (cli/print-error (str "Turn 1 Failed: " (:error start-result)))
            (flush))
          (do
            (cli/print-success "Turn 1 Successful!")
            (println)
            (cli/print-narrator (get-in start-result [:result :narrative]))
            (println)
            (flush)
            
            ;; Turn 2: Player Action
            (let [action "Check the local data terminals for corporate security patrols in the sector."
                  _ (println (cli/style (str "Action [Turn 2] > " action) :bold :cyan))
                  _ (cli/print-system "Fate shifts: Running RLM Loop (Oracle, Witness, Scribe)...")
                  _ (flush)
                  action-result (shell/player-action game-state action)]
              (if-not (:success action-result)
                (do
                  (cli/print-error (str "Turn 2 Failed: " (:error action-result)))
                  (flush))
                (do
                  (cli/print-success "Turn 2 Successful!")
                  (println)
                  (cli/print-narrator (get-in action-result [:result :narrative]))
                  (println)
                  (flush)
                  
                  ;; Turn 3: Second Player Action
                  (let [action "Jack directly into the access panel of the back door and run a bypass handshake."
                        _ (println (cli/style (str "Action [Turn 3] > " action) :bold :cyan))
                        _ (cli/print-system "Fate shifts: Running RLM Loop (Oracle, Witness, Scribe)...")
                        _ (flush)
                        action-result (shell/player-action game-state action)]
                    (if-not (:success action-result)
                      (do
                        (cli/print-error (str "Turn 3 Failed: " (:error action-result)))
                        (flush))
                      (do
                        (cli/print-success "Turn 3 Successful!")
                        (println)
                        (cli/print-narrator (get-in action-result [:result :narrative]))
                        (println)
                        (flush)
                        
                        ;; Audit DB State
                        (cli/print-system "PERSISTENT WORLD DATABASE AUDIT")
                        (let [patterns (c/q-player-patterns (:db game-state) player-id)
                              history (c/q-action-history (:db game-state) player-id 100)]
                          (cli/print-system (str "Discovered Player Patterns (" (count patterns) "):"))
                          (cli/print-data patterns)
                          (println)
                          (cli/print-system (str "Logged Action History (" (count history) "):"))
                          (cli/print-data history)
                          (flush))))))))))))))

(run-live-playtest)
(System/exit 0)
