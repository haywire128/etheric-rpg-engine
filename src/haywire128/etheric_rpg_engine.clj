(ns haywire128.etheric-rpg-engine
  "Game entry point. Starts the Clojure RPG engine REPL."
  (:gen-class)
  (:require [haywire128.etheric-rpg-engine.core :as c]
            [haywire128.etheric-rpg-engine.shell :as shell]
            [haywire128.etheric-rpg-engine.cli :as cli]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn greet
  "Callable entry point to the application."
  [data]
  (println (str "Hello, " (or (:name data) "World") "!")))

(defn new-game
  "Create a new game session with the given config.
   config: map with at minimum {:player/name str :player/genre kw}
   Returns a map with :game-state and the :initial-narrative."
  [config]
  (let [game-state (shell/start-game config)
        start-prompt (c/env-get (:env game-state) :prompt)
        start-result (shell/rlm-loop (:llm game-state) (:db game-state) (:env game-state)
                                     start-prompt
                                     :max-iterations 3)]
    {:game-state game-state
     :initial-narrative (get-in start-result [:result :narrative] "The threads of fate settle.")}))

(defn player-action
  "Process a player action through the RLM loop.
   Delegates to shell/player-action."
  [game-state input-str]
  (shell/player-action game-state input-str))

(defn world-weaver-prompt
  "Generates the system prompt for the World Weaver."
  [current-config]
  (str "You are the World Weaver, a neutral and helpful conversational entity residing in the Etheric RPG Engine.
Your purpose is to guide the player through configuring their adventure.

Current Configuration:
" (pr-str current-config) "

The target configuration structure is a Clojure map:
{:player/name \"string\"      ; The player character's name
 :player/genre :keyword       ; e.g. :medieval-fantasy, :cyberpunk, :space-opera, :gothic-horror, etc.
 :player/traits #{:keywords}   ; A set of player traits, e.g. #{:keen-eyed :silver-tongued :shadow-walker}
 :world/seed (or long nil)     ; A seed number, or nil for random
 :llm/default-model \"string\"  ; Typically \"deepseek/deepseek-v4-pro\"
 :llm/max-tokens 65536}        ; Maximum tokens (default 65536)

Rules:
1. Be helpful, clear, and neutral. Give them interesting ideas for genres or traits if they ask.
2. Converse with them naturally. Help them define or change their name, genre, traits, and seed.
3. Once they are satisfied with the configuration, or indicate they are finished, construct the final Clojure map and output it at the very end of your response inside a clojure markdown block like this:
```clojure
{:player/name \"Aldric\" :player/genre :medieval-fantasy :player/traits #{:keen-eyed :silver-tongued} :world/seed 123 :llm/default-model \"deepseek/deepseek-v4-pro\" :llm/max-tokens 65536}
```
Only output this block when the configuration conversation is fully complete and they are ready to save and start. No other clojure markdown blocks should be used.
"))

(defn conversational-config
  "Start an LLM-assisted conversational configuration loop with the World Weaver."
  [game-state current-name]
  (let [llm (:llm game-state)
        current-cfg (or (:config game-state) (shell/load-config))
        system-msg {:role :system :content (world-weaver-prompt current-cfg)}
        _ (cli/print-weaver "Welcome, traveler. I am the World Weaver. Let us shape the parameters of your reality together.\nWhat genre or theme do you envision for this world? (e.g. :medieval-fantasy, :cyberpunk, :space-opera)")
        hist (atom [system-msg])]
    (loop []
      (cli/input-prompt "World Weaver")
      (if-let [input (read-line)]
        (let [trimmed (str/trim input)]
          (cond
            (= "quit" (str/lower-case trimmed))
            (do
              (cli/print-system "The Weaver bows and fades into the mists.")
              current-cfg)
            
            (str/blank? trimmed)
            (recur)
            
            :else
            (do
              (swap! hist conj {:role :user :content trimmed})
              (cli/print-system "The Weaver weaves...")
              (let [weaver-model (or (:llm/default-model current-cfg) c/default-model)
                    {:keys [content]} (c/complete llm @hist weaver-model {:max-tokens 4096})
                    _ (swap! hist conj {:role :assistant :content content})
                    ;; Check if the response contains the final Clojure map
                    match (re-find #"(?s)```clojure[\s\n]*(.*?)[\s\n]*```" content)]
                (if match
                  (let [parsed-cfg (try
                                     (edn/read-string (nth match 1))
                                     (catch Exception _
                                       nil))]
                    (if (map? parsed-cfg)
                      (do
                        ;; Archive and replace the config
                        (shell/save-config parsed-cfg)
                        (cli/print-success "Configuration saved and archived!")
                        (cli/print-weaver (str/replace content (first match) ""))
                        parsed-cfg)
                      (do
                        (cli/print-error "The Weaver generated an invalid configuration structure. Let's continue...")
                        (recur))))
                  (do
                    (cli/print-weaver content)
                    (recur)))))))
        current-cfg))))

(defn -main
  "Game REPL entry point."
  [& args]
  (println (cli/banner))
  (println)
  (cli/print-narrator "The mists are thick. Whisper your name to step through (or type '/config' to weave your reality first)...")
  (println)
  (loop []
    (cli/input-prompt "Name (or '/config')")
    (if-let [input (read-line)]
      (let [trimmed (str/trim input)
            lower-input (str/lower-case trimmed)]
        (cond
          (str/blank? trimmed)
          (recur)
          
          (or (= "quit" lower-input) (= "/quit" lower-input))
          (do
            (println)
            (cli/print-narrator "The mists envelope you once more as you return to the void.")
            (println))
          
          (= "/help" lower-input)
          (do
            (println)
            (cli/print-narrator "✦ Initial Options ✦")
            (println (cli/style "  /help" :bold :purple) (cli/style "   - Show this list of initial options" :gray))
            (println (cli/style "  /config" :bold :purple) (cli/style " - Converse with the World Weaver to design your character and world genre" :gray))
            (println (cli/style "  /quit" :bold :purple) (cli/style "   - Escape this world and return to reality" :gray))
            (println)
            (recur))
          
          (= "/config" lower-input)
          (let [;; Load default client to converse
                llm (shell/llm-client)
                ;; Initialize a minimal game-state for Weaver
                dummy-game {:llm llm :config (shell/load-config)}
                new-cfg (conversational-config dummy-game "Traveler")
                ;; Start game runs Turn 1 JIT silently with new config!
                game-map (new-game new-cfg)
                game (:game-state game-map)
                starting-narrative (:initial-narrative game-map)]
            (cli/print-header "Turn 1")
            (cli/print-narrator starting-narrative)
            (println)
            (loop [g game
                   turn 2]
              (let [p-name (get-in g [:config :player/name] (:player/name new-cfg))]
                (cli/input-prompt p-name)
                (when-let [action (read-line)]
                  (let [act-trimmed (str/trim action)
                        act-lower (str/lower-case act-trimmed)]
                    (cond
                      (or (= "quit" act-lower) (= "/quit" act-lower))
                      (do
                        (println)
                        (cli/print-narrator "The mists envelope you once more as you return to the void.")
                        (println))
                      
                      (= "/help" act-lower)
                      (do
                        (println)
                        (cli/print-narrator "✦ Available Commands ✦")
                        (println (cli/style "  /help" :bold :purple) (cli/style "   - Show this list of command options" :gray))
                        (println (cli/style "  /status" :bold :purple) (cli/style " - Display your character's current configuration" :gray))
                        (println (cli/style "  /config" :bold :purple) (cli/style " - Talk with the World Weaver to customize config map conversationally" :gray))
                        (println (cli/style "  /quit" :bold :purple) (cli/style "   - Escape this world and return to reality" :gray))
                        (println)
                        (recur g turn))
                      
                      (= "/status" act-lower)
                      (do
                        (println)
                        (cli/print-narrator "✦ Current Parameters ✦")
                        (cli/print-data (:config g))
                        (println)
                        (recur g turn))
                      
                      (= "/config" act-lower)
                      (let [nested-cfg (conversational-config g p-name)
                            nested-game-map (new-game nested-cfg)
                            nested-g (:game-state nested-game-map)
                            nested-narrative (:initial-narrative nested-game-map)]
                        (cli/print-header (str "Turn " turn))
                        (cli/print-narrator nested-narrative)
                        (println)
                        (recur nested-g (inc turn)))
                      
                      (str/blank? act-trimmed)
                      (recur g turn)
                      
                      :else
                      (do
                        (cli/print-header (str "Turn " turn))
                        (try
                          (let [result (player-action g act-trimmed)]
                            (if (:success result)
                              (cli/print-narrator (get-in result [:result :narrative] "The threads of fate settle."))
                              (cli/print-error "The world needs a moment to settle...")))
                          (catch Exception e
                            (cli/print-error (str "The fabric of reality tears: " (.getMessage e)))))
                        (println)
                        (recur g (inc turn)))))))))
          
          :else
          ;; They typed their name directly
          (let [name trimmed
                config (try
                         (shell/load-config)
                         (catch Exception _
                           {:player/name name :player/genre :medieval-fantasy}))
                ;; Override name from prompt
                config (assoc config :player/name name)
                ;; Start game runs Turn 1 JIT silently!
                game-map (new-game config)
                game (:game-state game-map)
                starting-narrative (:initial-narrative game-map)]
            (println)
            (cli/print-header "Turn 1")
            (cli/print-narrator starting-narrative)
            (println)
            (loop [g game
                   turn 2]
              (let [p-name (get-in g [:config :player/name] name)]
                (cli/input-prompt p-name)
                (when-let [action (read-line)]
                  (let [act-trimmed (str/trim action)
                        act-lower (str/lower-case act-trimmed)]
                    (cond
                      (or (= "quit" act-lower) (= "/quit" act-lower))
                      (do
                        (println)
                        (cli/print-narrator "The mists envelope you once more as you return to the void.")
                        (println))
                      
                      (= "/help" act-lower)
                      (do
                        (println)
                        (cli/print-narrator "✦ Available Commands ✦")
                        (println (cli/style "  /help" :bold :purple) (cli/style "   - Show this list of command options" :gray))
                        (println (cli/style "  /status" :bold :purple) (cli/style " - Display your character's current configuration" :gray))
                        (println (cli/style "  /config" :bold :purple) (cli/style " - Talk with the World Weaver to customize config map conversationally" :gray))
                        (println (cli/style "  /quit" :bold :purple) (cli/style "   - Escape this world and return to reality" :gray))
                        (println)
                        (recur g turn))
                      
                      (= "/status" act-lower)
                      (do
                        (println)
                        (cli/print-narrator "✦ Current Parameters ✦")
                        (cli/print-data (:config g))
                        (println)
                        (recur g turn))
                      
                      (= "/config" act-lower)
                      (let [nested-cfg (conversational-config g p-name)
                            nested-game-map (new-game nested-cfg)
                            nested-g (:game-state nested-game-map)
                            nested-narrative (:initial-narrative nested-game-map)]
                        (cli/print-header (str "Turn " turn))
                        (cli/print-narrator nested-narrative)
                        (println)
                        (recur nested-g (inc turn)))
                      
                      (str/blank? act-trimmed)
                      (recur g turn)
                      
                      :else
                      (do
                        (cli/print-header (str "Turn " turn))
                        (try
                          (let [result (player-action g act-trimmed)]
                            (if (:success result)
                              (cli/print-narrator (get-in result [:result :narrative] "The threads of fate settle."))
                              (cli/print-error "The world needs a moment to settle...")))
                          (catch Exception e
                            (cli/print-error (str "The fabric of reality tears: " (.getMessage e)))))
                        (println)
                        (recur g (inc turn))))))))))))))