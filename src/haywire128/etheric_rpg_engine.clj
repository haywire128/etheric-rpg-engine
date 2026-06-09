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
 :player/traits #{:keywords}   ; A set of player backgrounds/talents, e.g. #{:keen-eyed :silver-tongued :shadow-walker}
 :world/seed (or long nil)     ; A seed number, or nil for random
 :llm/default-model \"string\"  ; Typically \"inception/mercury-2\"
 :llm/max-tokens 16384}        ; Maximum tokens (default 16384)

Rules:
1. Be helpful, clear, and neutral. Give them interesting ideas for genres or backgrounds/talents if they ask.
2. CRITICAL THIRD-WALL GUARD RAIL: The third wall must never be broken. In your conversational dialogue, you must NEVER use the words 'trait' or 'traits', 'die', 'dice', 'roll', 'rolls', 'fortune', or 'folly'. Instead, refer to traits as 'backgrounds', 'defining characteristics', 'talents', 'specialties', or 'aspects of your nature'. You must also NEVER output the raw keyword format of traits (like ':keen-eyed') or their literal names in the conversational chat. Only map the player's natural descriptions to appropriate keywords silently inside the final Clojure map block.
3. Converse with them naturally. Help them define or change their name, genre, backgrounds/talents, and seed.
4. Once they are satisfied with the configuration, or indicate they are finished, construct the final Clojure map and output it at the very end of your response inside a clojure markdown block like this:
```clojure
{:player/name \"Aldric\" :player/genre :medieval-fantasy :player/traits #{:keen-eyed :silver-tongued} :world/seed 123 :llm/default-model \"inception/mercury-2\" :llm/max-tokens 16384}
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

(defn print-immersive-status [config]
  (println)
  (cli/print-narrator "✦ Your Presence in this World ✦")
  (println (cli/style (str "  Name:  " (:player/name config)) :bold :white))
  (println (cli/style (str "  Genre: " (some-> (:player/genre config) name str/capitalize)) :bold :white))
  (println))

(defn run-game-loop
  "The in-game REPL. Runs until the player quits or stdin closes.
   game: initial game state map. turn: starting turn number (usually 2).
   Returns nil when the session ends."
  [game turn]
  (let [p-name (get-in game [:config :player/name] "Traveler")]
    (cli/input-prompt p-name)
    (when-let [action (read-line)]
      (let [act-trimmed (str/trim action)
            act-lower   (str/lower-case act-trimmed)]
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
            (println (cli/style "  /help"   :bold :thistle) (cli/style "   - Show this list of command options" :dim :lilac-ash))
            (println (cli/style "  /status" :bold :thistle) (cli/style " - Display your character's current configuration" :dim :lilac-ash))
            (println (cli/style "  /config" :bold :thistle) (cli/style " - Talk with the World Weaver to customize config map conversationally" :dim :lilac-ash))
            (println (cli/style "  /quit"   :bold :thistle) (cli/style "   - Escape this world and return to reality" :dim :lilac-ash))
            (println)
            (recur game turn))

          (= "/status" act-lower)
          (do
            (print-immersive-status (:config game))
            (recur game turn))

          (= "/config" act-lower)
          (let [nested-cfg (conversational-config game p-name)
                _ (cli/print-system (str "Initializing world for " (:player/name nested-cfg)
                                         " (" (some-> (:player/genre nested-cfg) name) ")..."))
                _ (cli/print-system (str "Turn " turn ": Invoking RLM Loop for game startup (Cartographer & Scout)..."))
                nested-map (new-game nested-cfg)]
            (if (:game-state nested-map)
              (do
                (cli/print-success "World initialized successfully!")
                (println)
                (cli/print-narrator (:initial-narrative nested-map))
                (println)
                (recur (:game-state nested-map) (inc turn)))
              (do
                (cli/print-error "Initialization failed.")
                (recur game turn))))

          (str/blank? act-trimmed)
          (recur game turn)

          :else
          (do
            (println (cli/style (str "Action [Turn " turn "] > " act-trimmed) :bold :frozen-water))
            (cli/print-system "Fate shifts: Running RLM Loop (Oracle, Witness, Scribe)...")
            (let [result (try
                           (player-action game act-trimmed)
                           (catch Exception e
                             {:success false :error (.getMessage e)}))]
              (if (:success result)
                (do
                  (cli/print-success (str "Turn " turn " Successful!"))
                  (println)
                  (cli/print-narrator (get-in result [:result :narrative] "The threads of fate settle."))
                  (println)
                  (recur game (inc turn)))
                (do
                  (if (:error result)
                    (cli/print-error (str "The fabric of reality tears: " (:error result)))
                    (cli/print-error "The world needs a moment to settle..."))
                  (recur game turn))))))))))

(defn start-session!
  "Initialize a game from config and run the game loop.
   Returns when the session ends (player quits or EOF)."
  [config]
  (cli/print-system (str "Initializing world for " (:player/name config)
                          " (" (some-> (:player/genre config) name) ")..."))
  (cli/print-system "Turn 1: Invoking RLM Loop for game startup (Cartographer & Scout)...")
  (let [game-map (try
                   (new-game config)
                   (catch Exception e
                     (cli/print-error (str "The fabric of reality tears during startup: " (.getMessage e)))
                     (.printStackTrace e)
                     nil))]
    (if-let [game (:game-state game-map)]
      (do
        (cli/print-success "World initialized successfully!")
        (println)
        (cli/print-narrator (:initial-narrative game-map))
        (println)
        (run-game-loop game 2))
      (cli/print-error "Initialization failed."))))

(defn -main
  "Game REPL entry point."
  [& args]
  (println (cli/banner))
  (println)
  (cli/print-narrator "The mists are thick. Whisper your name to step through (or type '/config' to weave your reality first)...")
  (println)
  (loop []
    (cli/input-prompt "Name (or '/config')")
    (when-let [input (read-line)]
      (let [trimmed     (str/trim input)
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
            (println (cli/style "  /help"   :bold :thistle) (cli/style "   - Show this list of initial options" :dim :lilac-ash))
            (println (cli/style "  /config" :bold :thistle) (cli/style " - Converse with the World Weaver to design your character and world genre" :dim :lilac-ash))
            (println (cli/style "  /quit"   :bold :thistle) (cli/style "   - Escape this world and return to reality" :dim :lilac-ash))
            (println)
            (recur))

          (= "/config" lower-input)
          (let [llm        (shell/llm-client)
                dummy-game {:llm llm :config (shell/load-config)}
                new-cfg    (conversational-config dummy-game "Traveler")]
            (start-session! new-cfg)
            (recur))

          :else
          ;; Player typed their character name directly
          (let [config (try
                         (shell/load-config)
                         (catch Exception _
                           {:player/name trimmed :player/genre :medieval-fantasy}))
                config (assoc config :player/name trimmed)]
            (start-session! config)
            (recur)))))))