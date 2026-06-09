(ns haywire128.etheric-rpg-engine.interact
  "State-persistent driver for single-turn interactive CLI playtesting."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [haywire128.etheric-rpg-engine.core :as c]
            [haywire128.etheric-rpg-engine.shell :as shell]
            [haywire128.etheric-rpg-engine.cli :as cli]
            [datahike.api :as d]))

(def db-config
  {:store {:backend :file :path "data/rpg-db"}})

(def env-file
  (io/file "data/env-state.edn"))

(defn serialize-env
  "Write the RLMEnv atom state to disk."
  [env]
  (spit env-file (pr-str @(:atom env))))

(defn deserialize-env
  "Load the RLMEnv atom state from disk."
  []
  (if (.exists env-file)
    (shell/restore-rlm-env (edn/read-string (slurp env-file)))
    nil))

(defn init-session!
  "Initialize a brand new persistent game session."
  [config]
  ;; 1. Re-create file-backed DB with schema
  (when (d/database-exists? db-config)
    (d/delete-database db-config))
  (d/create-database db-config)
  (let [conn (d/connect db-config)
        _ (d/transact conn shell/schema)
        db (shell/->DatahikeDB conn)
        llm (shell/llm-client)
        ;; Transact player entity
        player-entity {:entity/type :player
                       :entity/name (or (:player/name config) "Aldric")
                       :trait/set (or (:player/traits config) #{})
                       :entity/age 6
                       :entity/turns-played 0}
        _ (d/transact conn [player-entity])
        ;; Query player entity ID
        player-id (ffirst (d/q '[:find ?e :where [?e :entity/type :player]] (d/db conn)))
        config (assoc config :player/id player-id)
        ;; 2. Set up initial prompt & env
        prompt {:type :start
                :player (assoc (select-keys config [:player/name :player/genre :player/traits])
                               :db/id player-id)
                :raw "Game started"}
        env (shell/rlm-env prompt)]
    ;; Store config in env state
    (swap! (:atom env) assoc :config config)
    (serialize-env env)
    (cli/print-system "Generating initial world taxonomy...")
    ;; 3. Run Turn 1
    (let [result (shell/rlm-loop llm db env prompt :max-iterations 10)]
      (serialize-env env)
      result)))

(defn step-session!
  "Step the persistent game session with a player action."
  [input-str]
  (let [conn (d/connect db-config)
        db (shell/->DatahikeDB conn)
        llm (shell/llm-client)
        env (deserialize-env)]
    (if-not env
      (do
        (d/release conn)
        {:success false :error "No active game session. Run /start first."})
      (let [config (get @(:atom env) :config)
            game-state {:llm llm :db db :env env :config config}
            result (shell/player-action game-state input-str)]
        (serialize-env env)
        (d/release conn)
        result))))

(defn -main
  "Main entry point for interactive single-turn step."
  [& args]
  (let [input (str/trim (or (first args) ""))]
    (cond
      (str/blank? input)
      (cli/print-error "Please provide an action or command (e.g. '/start' or 'look around')")
      
      (= "/start" input)
      (let [config (try
                     (shell/load-config)
                     (catch Exception _
                       {:player/name "Hiro" :player/genre :cyberpunk :player/traits #{:hacker :rain-slicked :neon-drenched}}))]
        (println (cli/banner))
        (println)
        (cli/print-system (str "Initializing persistent world for " (:player/name config) "..."))
        (let [result (init-session! config)]
          (if (:success result)
            (do
              (cli/print-success "World initialized successfully!")
              (println)
              (cli/print-narrator (get-in result [:result :narrative])))
            (cli/print-error (str "Initialization failed: " (:error result))))))
      
      (= "/status" input)
      (let [config (shell/load-config)]
        (println)
        (cli/print-narrator "✦ Your Presence in this World ✦")
        (println (cli/style (str "  Name:  " (:player/name config)) :bold :white))
        (println (cli/style (str "  Genre: " (some-> (:player/genre config) name str/capitalize)) :bold :white))
        (println))
      
      :else
      (do
        (cli/print-system (str "The threads of fate shift: '" input "'"))
        (let [result (step-session! input)]
          (if (:success result)
            (cli/print-narrator (get-in result [:result :narrative]))
            (cli/print-error (str "Turn execution failed: " (:error result)))))))))
