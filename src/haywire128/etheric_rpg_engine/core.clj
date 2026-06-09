(ns haywire128.etheric-rpg-engine.core
  "Functional core. Pure functions, protocols, data shapes, constants, role registry.
   ZERO I/O. Every function testable without infrastructure."
  (:require [clojure.string :as str]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Protocols
;; ══════════════════════════════════════════════════════════════════════════════

(defprotocol RLMEnv
  "RLM environment atom. Root LLM manipulates via generated code in PREPL.
   Shell implements with an atom. Tests use a plain map."
  (env-get   [this key]       "Read variable from environment")
  (env-set   [this key val]   "Write variable to environment")
  (finalize! [this val]        "Set final result, signal loop exit")
  (finalized? [this]          "Check if loop should exit")
  (summary   [this]           "Constant-size metadata for root LLM context window"))

(defprotocol WorldDB
  "Single boundary for all Datahike operations. Shell implements.
   Tests use an atom-backed in-memory DB."
  (q-range    [this path depth]         "Entities within perceptual range")
  (q-entity   [this id]                "Single entity with all components")
  (q-location [this path]              "Location entity at path")
  (q-rels     [this entity-id]          "All relationships (edges) for entity")
  (q-patterns [this entity-id]          "Behavioral pattern map for entity")
  (q-history  [this entity-id since]    "Temporal: changes since instant")
  (q-player-patterns [this player-id]   "All discovered behavioral patterns for player")
  (q-action-history [this player-id window] "Recent actions for player within window")
  (transact!  [this tx-data]           "Write to Datahike"))

(defprotocol LLM
  "Single boundary for all LLM inference. Shell implements via OpenRouter.
   Tests mock with a fn that returns canned responses."
  (complete [this messages model opts]  "Full chat completion. Returns {:content ... :cost ...}."))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constants
;; ══════════════════════════════════════════════════════════════════════════════

(def ^:const default-max-iterations 50)
(def ^:const default-max-tokens 16384)

(def ^:const fortune-sides 20)
(def ^:const folly-sides 20)

(def ^:const pattern-window-turns 100)

(def default-model
  "Default model for all LLM calls. Override per-role in role-registry if needed."
  "inception/mercury-2")

;; ══════════════════════════════════════════════════════════════════════════════
;; Pure Functions
;; ══════════════════════════════════════════════════════════════════════════════

;; ─── Dice ────────────────────────────────────────────────────────────────────

(defn cast-dice
  "Roll Fortune and Folly. Returns map with direction and magnitude.
   Direction is decided by the higher die.
   Magnitude = max(fortune, folly) / max-possible-sides."
  []
  (let [fortune (inc (rand-int fortune-sides))
        folly   (inc (rand-int folly-sides))
        high    (max fortune folly)]
    {:fortune   fortune
     :folly     folly
     :direction (if (> fortune folly) :fortune :folly)
     :magnitude (double (/ high (max fortune-sides folly-sides)))}))

;; ─── Relationships ───────────────────────────────────────────────────────────

(def relationship-kinds
  "Ordered from most hostile to most devoted."
  [:mortal-enemy :enemy :hostile :wary :neutral :acquaintance :friendly :close-ally :trusted-confidant :devoted])

(defn relationship-kind
  "Derive qualitative label from continuous strength (-1.0 to 1.0).
   Pure function. No DB access."
  [strength]
  (cond
    (< strength -0.8)  :mortal-enemy
    (< strength -0.5)  :enemy
    (< strength -0.2)  :hostile
    (< strength 0.0)   :wary
    (< strength 0.1)   :neutral
    (< strength 0.3)   :acquaintance
    (< strength 0.5)   :friendly
    (< strength 0.7)   :close-ally
    (< strength 0.9)   :trusted-confidant
    :else              :devoted))

;; ─── Behaviors ────────────────────────────────────────────────────────────────

(defn action-history-summary
  "Build a windowed action summary for the Witness.
   Pure: takes a seq of action maps, returns a tally.
   Action map: {:turn int :action {:type kw :target str} :outcome {:success bool}}"
  [actions current-turn]
  (let [lower (- current-turn pattern-window-turns)]
    (filter #(and (>= (:turn %) lower) (<= (:turn %) current-turn)) actions)))

;; ─── Traits ───────────────────────────────────────────────────────────────────

;; ─── Location ─────────────────────────────────────────────────────────────────

(def default-perceptual-depth
  "How many levels down the location hierarchy to perceive."
  3)

(defn perceptual-depth
  "Compute perceptual range depth given location path length and taxonomy.
   Returns min(default-depth, depth-to-leaf)."
  [path-length taxonomy-target-depth]
  (min default-perceptual-depth
       (max 1 (- taxonomy-target-depth path-length))))

;; ─── Scout Caching ────────────────────────────────────────────────────────────

(defn cache-key
  "Deterministic cache key for Scout output.
   Invalidate when time elapsed > 0 or location changes."
  [location-path player-id time-elapsed-str]
  [(vec location-path) player-id time-elapsed-str])

;; ─── Player Input ─────────────────────────────────────────────────────────────

(defn parse-player-input
  "Pure: free text → structured action map.
   Falls back to :unknown type if unparseable."
  [raw]
  (cond
    (nil? raw) {:type :invalid :raw nil}
    (str/blank? raw) {:type :invalid :raw raw}
    :else {:type :unknown :raw (str/trim raw)}))

;; ─── Stub Detection ───────────────────────────────────────────────────────────

(defn stub?
  "Check if entity needs Forging."
  [entity]
  (true? (:stubbed? entity)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Role Registry (data, not code)
;; ══════════════════════════════════════════════════════════════════════════════

(def cartographer-prompt
  "You are the Cartographer function.

Signature:
(fn cartographer [{:keys [genre parent target-depth]}]
  ;; Returns: {:nodes [...]})

Purpose:
Design the structural geography of a world region. Produce a hierarchical taxonomy.
Each node has :name, :node-type, :traits, :atmosphere, :npc-patterns, and :children.

IMPORTANT: The following is an **illustrative example** of the expected data shape and structure.
Do NOT copy this data literally. Generate content appropriate to the actual input context, genre, and situation.
Adapt the content to the genre, location, and situation provided in the input.

For example, the structure looks like:
{:genre :medieval-fantasy
 :parent {:path [:Aethelgard]
          :depth 1
          :name \"Aethelgard\"
          :traits #{:ancient :magical}}
 :target-depth 4}

Would produce output like:
{:nodes [{:name \"Kingdom-Of-Rasmuth\"
          :node-type :kingdom
          :traits #{:feudal :militaristic}
          :atmosphere \"A proud kingdom of stone castles...\"
          :npc-patterns [{:profession :knight :count 50
                          :schedule [{:time \"06:00-18:00\" :activity :patrol}]}]
          :children [{:name \"Moss-Garden\"
                      :node-type :macro-biome
                      :traits #{:ancient :misty}
                      :atmosphere \"A sprawling woodland...\"
                      :npc-patterns [...]
                      :children [...]}]}]}

Rules:
- Sibling regions must have diverse character.
- Depth must match target-depth. Leaf = :settlement or :landmark.
- NPC patterns at (target-depth - 2).
- Respond with EDN map containing :nodes ONLY. No explanation. No markdown fences.
- Crucial: Ensure that all open vectors '[', maps '{', and lists '(' are correctly balanced and closed. Double-check all closing brackets.
- Limit size: Generate exactly 1 or 2 sibling nodes at each level (do not generate 3 or more siblings). Keep the tree compact and focused to avoid bracket matching errors.
- Do not truncate the children or node listings. Produce a complete world map.")

(def chronicler-prompt
  "You are the Chronicler function.

Signature:
(fn chronicler [{:keys [genre taxonomy]}]
  ;; Returns: {:lore-nodes [...]})

Purpose:
Create the AOT (Ahead-of-Time) 'Web of Interest' — a network of lore nodes representing people, places, and things of interest within the generated world taxonomy. These nodes connect characters, locations, and items with rich history, hidden truths, and potential plot hooks.
The player must not be presented with these hooks immediately; instead, they will be trickled JIT (Just-in-Time) and organically, and only trigger significant plot hooks if the player directly enters or interacts with that specific node of interest.

IMPORTANT: The following is an **illustrative example** of the expected data shape and structure.
Do NOT copy this data literally. Generate content appropriate to the actual input context, genre, and situation.

For example, the structure looks like:
{:genre :medieval-fantasy
 :taxonomy [{:name \"Eldor's Hold\" :node-type :settlement :traits #{:frontier :fortified} :atmosphere \"...\"}]}

Would produce output like:
{:lore-nodes
 [{:lore/name \"Lord Aldric\"
   :lore/type :person
   :lore/description \"The aging governor of Eldor's Hold. He secretly fears the moon-blood eclipse and has been desperately seeking relics to bolster the town's defenses.\"
   :lore/connections [\"Heartstone\" \"Eldor's Hold\"]
   :lore/hook \"Lord Aldric reveals that the Heartstone was stolen from the crypt beneath the ruined chapel, and begs you to retrieve it before the eclipse.\"}
  {:lore/name \"Heartstone\"
   :lore/type :thing
   :lore/description \"An ancient, pulsing amber crystal once used to ward off dark magic. It was stored in the crypt beneath the ruined chapel.\"
   :lore/connections [\"Ruined Chapel\" \"Lord Aldric\"]
   :lore/hook \"As you touch the Heartstone, a surge of raw, warm energy courses through you, fragmenting your amnesia for a brief second with a vision of a grand laboratory.\"}]}

Rules:
- Generate 3 to 5 lore nodes that form a cohesive lore web for the starter region and surrounding locations.
- Connect people, places, and things together in the :lore/connections list (using their names).
- Keep descriptions focused on backstory, atmosphere, and secrets.
- Keep the :lore/hook highly specific to that node, describing a major revelation or interaction when the player actively engages/interacts with it.
- Respond with EDN map containing :lore-nodes ONLY. No explanation. No markdown fences.
- Crucial: Ensure that all open vectors '[', maps '{', and lists '(' are correctly balanced and closed. Double-check all closing brackets.")

(def harbinger-prompt
  "You are the Harbinger function.

Signature:
(fn harbinger [{:keys [genre location npc-patterns]}]
  ;; Returns: {:entities [...]})

Purpose:
Populate a newly-entered location with inhabitants, objects, and points of interest.
Create entity stubs: name, type, profession, base traits. Mark unconvincing ones :stubbed? true.

IMPORTANT: The following is an **illustrative example** of the expected data shape and structure.
Do NOT copy this data literally. Generate content appropriate to the actual input context, genre, and situation.
Adapt the content to the genre, location, and situation provided in the input.

For example, the structure looks like:
{:genre :medieval-fantasy
 :location {:name \"The Crooked Hearth\"
            :loc-type :tavern
            :traits #{:cozy :rowdy :well-stocked}}
 :npc-patterns [{:profession :innkeeper :count 1
                 :schedule [{:time \"06:00-22:00\" :activity :tending-bar}]}
                {:profession :patron :count 5
                 :schedule [{:time \"18:00-23:00\" :activity :drinking}]}]}

Would produce output like:
{:entities
 [{:entity/type :npc
   :entity/name \"Hilda\"
   :entity/profession :innkeeper
   :trait/set #{:observant :motherly :tired}
   :trait/registry {:observant {:breadth :narrow :domain :perception}
                    :motherly {:breadth :narrow :domain :social}
                    :tired {:breadth :narrow :domain :physical}}
   :stubbed? false}
  {:entity/type :npc
   :entity/name \"Bjorn\"
   :entity/profession :patron
   :trait/set #{:strong-armed}
   :trait/registry {:strong-armed {:breadth :narrow :domain :physical}}
   :stubbed? true}]}

Rules:
- Batch ALL entities for the location in ONE call.
- Every entity must have :entity/type, :entity/name, :trait/set, :trait/registry.
- NPCs get :entity/profession. Items get no profession.
- Include :stubbed? as true or false for every entity.
- Mark entities you are uncertain about as :stubbed? true (Forger will deepen them).
- THIRD-WALL GUARD RAIL: Trait keywords in :trait/set and :trait/registry are internal data only. They must never appear in any string field (atmosphere, description, appearance). These keywords shape behavior at runtime — they are never spoken aloud or written as prose.
- PRIORITIES: Every NPC entity MUST include an `:npc/priorities` key whose value is a pr-str'd vector of priority maps. Each priority map has:
  - `:priority/type` — keyword, one of: `:care-for-entity`, `:maintain-routine`, `:protect-entity`, `:serve-faction`, `:pursue-goal`
  - `:priority/target-name` — string, the name of the entity or faction this priority concerns (omit if no specific target)
  - `:priority/reason` — keyword, one of: `:professional`, `:familial`, `:altruistic`, `:obligatory`, `:fearful`, `:mercenary`
  - `:priority/urgency` — keyword, one of: `:critical`, `:high`, `:medium`, `:low`
- RECOVERING PLAYER RULE: If the context includes `:recovering-player true`, at least one generated NPC MUST have a `:care-for-entity` priority targeting the player (`:priority/target-name` set to the player name). The `:priority/reason` and the NPC's profession, name, and appearance must emerge from the genre and world context — do NOT assume `:caregiver` profession or any specific identity. A guard, a parent, a priest, a droid, a fellow patient, or anyone else may be the one caring for the player.
- Respond with EDN map containing :entities ONLY.")

(def forger-prompt
  "You are the Forger function.

Signature:
(fn forger [{:keys [entity location nearby-entities]}]
  ;; Returns: {:entity-delta {:add-traits #{...} :add-personality {...} :add-appearance str}})

Purpose:
Give depth to a bare entity. Add personality, appearance, hidden traits.
Traits should loosely correlate to profession but need not be constrained.

IMPORTANT: The following is an **illustrative example** of the expected data shape and structure.
Do NOT copy this data literally. Generate content appropriate to the actual input context, genre, and situation.
Adapt the content to the genre, location, and situation provided in the input.

For example, the structure looks like:
{:entity {:entity/name \"Bjorn\"
          :entity/profession :blacksmith
          :trait/set #{:strong-armed}
          :trait/registry {:strong-armed {:breadth :narrow :domain :physical}}}
 :location {:name \"The Crooked Hearth\"
            :traits #{:cozy :rowdy}}
 :nearby-entities [{:name \"Hilda\" :profession :innkeeper :traits #{:observant :motherly}}
                   {:name \"Aldric\" :profession :player :traits #{:silver-tongued}}]}

Would produce output like:
{:entity-delta
 {:add-traits #{:meticulous}
  :add-trait-registry {:meticulous {:breadth :narrow :domain :craftsmanship}}
  :add-personality {:disposition :gruff
                    :secrets [\"writes poetry about the moon\"]
                    :motivations [\"earn enough to leave the district\"]
                    :fears [\"becoming like his father\"]}
  :add-appearance \"A broad-shouldered man with singed eyebrows, calloused hands, and a permanent scowl that softens only when inspecting his work.\"}}

Rules:
- Entity must feel DISTINCT and REAL.
- Traits loosely correlate to profession, not rigidly constrained.
- Secrets, motivations, fears must be specific and personal.
- Respond with EDN map containing :entity-delta ONLY.")

(def scout-prompt
  "You are the Scout function.

Signature:
(fn scout [{:keys [player location entities relationships time player-patterns]}]
  ;; Returns: {:narrative str :notable {:new-entities [...] :changed-entities [...] :atmosphere str}})

Purpose:
Construct the second-person sensory experience of a location from the player's eyes.
CRITICAL: Write your narrative in the **second-person perspective** ('you' / 'your') at all times. Never use first-person perspective ('I' / 'my' / 'me').
CRITICAL: On the first turn (Turn 1), frame the character's waking up as a transition from a surreal, abstract fever dream of \"The Ether\" (where their player name and traits like those in `:traits` were chosen) to waking in their current location as a child of the age specified in `:age` under `:player` burning with fever and suffering from amnesia. Derive the sleeping surface, room quality, and setting details entirely from the `:location` data and the `:entities` already present — do NOT assume a type. The scene must feature whoever is present in `:entities` with a caring or attending disposition toward the player character. Focus purely on the immediate environment, the child's feverish state, the attending presence(s), and the complete loss of memory — there must be no mention of external quests or global threats.
CRITICAL: You are passed the location's `:lineage` vector (e.g., `[:Eldoria :Whispering-Woods :Elderglen]`), JIT-populated `:entities` (characters/items present in the area), and `:surrounding` (surrounding sister macro-biomes/landmarks visible on the horizon). You MUST weave these specific names, atmospheres, characters, and neighboring lands into your description. Tell the player EXACTLY what named settlement they are in, what parent region or kingdom it belongs to, what characters are standing nearby, and what distant landmarks or sister regions are visible on the horizon to explore. They must have absolute clarity on who they are, where they are, and what their options are!
Player traits modulate what is noticed and how.
Player behavioral patterns influence how NPCs perceive and react to the player.

IMPORTANT: The following is an **illustrative example** of the expected data shape and structure.
Do NOT copy this data literally. Generate content appropriate to the actual input context, genre, and situation.
Adapt the content to the genre, location, and situation provided in the input.

For example, the structure looks like:
{:player {:name \"Aldric\"
          :traits #{:keen-eyed :shadow-walker :silver-tongued}
          :trait-info {:keen-eyed {:breadth :broad :domain :perception}
                       :shadow-walker {:breadth :broad :domain :stealth}
                       :silver-tongued {:breadth :broad :domain :social}}
          :meta {:character/essence \"Unconscious mana channeling through swordplay — manifests as improbable outcomes in combat. Completely undetectable, even to the player.\"
                 :character/flaw \"Believes he acts from honor but consistently chooses personal gain.\"}
          :reputation :notorious}
 :location {:name \"The Crooked Hearth\"
            :loc-type :tavern
            :traits #{:cozy :rowdy}
            :description \"A timber-framed tavern leaning slightly...\"}
 :entities [{:name \"Hilda\" :profession :innkeeper :traits #{:observant :motherly}
             :personality {:disposition :warm} :appearance \"A stout woman...\"}
            {:name \"Bjorn\" :profession :blacksmith :traits #{:strong-armed :meticulous}
             :personality {:disposition :gruff} :appearance \"Broad-shouldered...\"}
            {:name \"Garrett\" :profession :patron :stubbed? true}]
 :relationships [{:entity \"Hilda\" :strength 0.4 :kind :friendly}
                 {:entity \"Bjorn\" :strength 0.2 :kind :acquaintance}]
 :time {:of-day :evening :weather \"light rain\" :elapsed \"3 days\"}
 :player-patterns [{:behavior/pattern-name \"pays-in-exact-change\"
                    :behavior/groundedness 0.73
                    :behavior/description \"Never rounds up, tips, or overpaid.\"}
                   {:behavior/pattern-name \"attempted-intimidation\"
                    :behavior/groundedness 0.85
                    :behavior/description \"Uses threats to get what he wants.\"}]}

Would produce output like:
{:narrative \"The Crooked Hearth is warm against the evening rain. Hilda nods at you from behind the bar — a familiar face now. Your keen eyes pick out Bjorn in the corner, nursing an ale and looking more haggard than you recall. A loud patron you don't recognize holds court at the center table. Whispers trail the notorious wherever they go.\"
 :notable {:new-entities [\"Garrett\"]
           :changed-entities [{:entity \"Bjorn\" :observation \"looking more haggard than usual\"}]
           :atmosphere \"subdued tension beneath the warmth\"}}

Rules:
- CRITICAL THIRD-WALL GUARD RAIL: The third wall must never be broken. Player and NPC traits are **invisible mechanical substrate** — they shape what the player notices and how the world reacts, but they must NEVER appear in the narrative as words, names, or concepts. Do NOT write 'keen-eyed', 'silver-tongued', 'shadow-walker', or any other trait keyword or paraphrase of it. Instead, render traits purely as sensory consequence: a :keen-eyed player notices the blade tucked under the innkeeper's apron; a :silver-tongued player finds the right words coming naturally. The reader must never know a trait system exists. Do NOT use the words 'trait', 'traits', 'die', 'dice', 'roll', 'rolls', 'fortune', 'folly', 'stat', 'attribute', or 'mechanic' in narrative output.
- CRITICAL: NEVER use a trait keyword as a prose adjective slot — not even partially. Traits must ONLY influence what is perceived or felt, never appear as inserted words. Concretely: do NOT write 'his [blank] gaze' or 'her [blank] smile' or 'an [blank] oak tree' — if a trait would have filled that blank, remove the blank entirely and rework the sentence around a concrete sensory observation instead. For example, instead of 'his [sharp-eyed] gaze scanning the horizon', write 'his gaze sweeping methodically across the rooftops, pausing on shadows that most would miss'. Every sentence must be grammatically complete and contain no unfilled placeholder gaps.
- If :player :meta is present, use it as invisible authorial context. Let it subtly color the scene without ever surfacing as exposition — a character whose essence includes unconscious channeling might have the wind shift at the right moment, a flame lean toward them, a blade deflect without reason. Never explain it. Just let it happen.
- Describe ONLY what the player perceives. Never invent events.
- Player traits SHAPE perception. :keen-eyed notices details. :shadow-walker notices shadows/exits.
- Note entities that are new or changed since last visit.
- CRITICAL: NPC ROSTER LIMIT. Introduce AT MOST 2-3 NPCs prominently in any single scene description. The world should feel populated, not catalogued. Remaining NPCs may be evoked as anonymous background presence (e.g. 'the market hums with vendors', 'guards make their rounds') without individual introductions. Let other characters surface naturally through play.
- Stubbed entities get brief, surface-level description only.
- Relationship quality colors perception. Friendly faces feel welcoming. Wary ones feel distant.
- Player behavioral patterns influence NPC reactions. If the player has patterns like \"attempted-intimidation\", NPCs may be wary. If they have \"helped-strangers\", NPCs may be trusting. Reference these patterns naturally in the narrative when relevant.
- CRITICAL: NO QUEST EXPOSITION DUMPS & ORGANIC LORE TRICKLING. You are passed the `:lore-web` containing the region's lore. Do NOT surface quest objectives, mission briefings, or narrative hooks as narrated interiority. Weave hints of the lore nodes connected to the current location or the caregiver's speech subtly and organically into the environment (e.g., the caregiver might sigh and mutter about a name in passing, or the player might notice an old, dusty tome or symbol in the room). Significant plot hooks (such as ancient quests or global threats) must be strictly gatekept — ONLY reveal a lore node's :lore/hook if the player explicitly inspects or interacts with that specific node of interest.
- Respond with EDN map ONLY. No explanation. No markdown fences.")

(def oracle-prompt
  "You are the Oracle function.

Signature:
(fn oracle [{:keys [action dice actor target location relationship actor-reputation player-patterns stances]}]
  ;; Returns: {:outcome {:success bool :degree kw} :narrative str :side-effects [...] :npc-actions [...] :stance-updates [...]})

Purpose:
Resolve a player action through Fortune & Folly. Interpret dice, weigh traits,
consider relationships, reputation, and behavioral patterns. Produce narrative outcome, side effects, and optional autonomous NPC reactions.
CRITICAL: Write your narrative in the **second-person perspective** ('you' / 'your') addressing the player/actor at all times. Never use first-person ('I' / 'my') or third-person ('he' / 'she' / 'his' / 'Kyle'). Refer to the actor as 'you' or 'your' at all times.
CRITICAL: You are passed the immediate previous turn's action and narrative under `:last-turn`. You MUST resolve the current action as a direct, logical, and coherent continuation of this previous narrative! For example, if on the previous turn the player was facing an assassin attacking their sibling, and on this turn they say 'I dart toward my sibling', you must resolve this action within that exact situation (e.g. they reach the sibling or get intercepted), ensuring absolute, flawless narrative coherence!

IMPORTANT: The following is an **illustrative example** of the expected data shape and structure.
Do NOT copy this data literally. Generate content appropriate to the actual input context, genre, and situation.
Adapt the content to the genre, location, and situation provided in the input.

For example, trait relevance works like this: an :intimidate action might be influenced by
:social, :stealth, or :combat traits. But weigh traits based on their relevance to the
specific situation, not rigid categories. A :silver-tongued trait might help intimidate
through verbal threats, while :shadow-walker might help through implied menace.

For example, the structure looks like:
{:action {:type :intimidate
          :intent \"scare Bjorn into lowering his prices\"
          :approach \"lean across the counter and speak quietly\"}
 :dice {:fortune 17 :folly 6 :direction :fortune :magnitude 0.85}
 :actor {:name \"Aldric\"
         :traits #{:silver-tongued :shadow-walker}
         :trait-info {:silver-tongued {:breadth :broad :domain :social}
                      :shadow-walker {:breadth :broad :domain :stealth}}
         :meta {:character/essence \"Unconscious mana channeling through swordplay — manifests as improbable outcomes. Undetectable.\"}}
 :target {:name \"Bjorn\"
          :traits #{:strong-armed :meticulous}
          :trait-info {:strong-armed {:breadth :narrow :domain :physical}
                       :meticulous {:breadth :narrow :domain :craftsmanship}}}
 :location {:traits #{:cozy :rowdy}}
 :relationship {:strength 0.2}
 :actor-reputation :notorious
 :player-patterns [{:behavior/pattern-name \"attempted-intimidation\"
                    :behavior/groundedness 0.85
                    :behavior/description \"Uses threats to get what he wants.\"}]
 :stances [{:source \"Hilda\" :target \"Bjorn\" :type :friendly :intensity 0.8}]}

Would produce output like:
{:outcome {:success true :degree :partial :magnitude-applied 0.85}
 :narrative \"Bjorn's eyes widen as you lean across the counter, your voice low. Your reputation as someone not to be trifled with precedes you — he's heard the rumors. Your quiet, calm persistence finds the crack in his gruff exterior. 'Fine,' he mutters, not meeting your eyes, 'but only this once.' He busies himself with a horseshoe that doesn't need inspecting.\"
 :side-effects [{:type :relationship-delta :from 1 :to 2 :delta -0.3
                 :reason \"Aldric used intimidation against Bjorn\"}
                {:type :record-behavior :entity 1 :pattern :attempted-intimidation
                 :detail {:target 2 :turn 47}}]
 :npc-actions [{:actor \"Hilda\" :action :frown :target \"Aldric\" :reason \"Hilda is friendly toward Bjorn and dislikes Aldric's threat.\"}]
 :stance-updates [{:source \"Hilda\" :target \"Aldric\" :type :wary :intensity 0.6}]}

Rules:
- CRITICAL THIRD-WALL GUARD RAIL: The third wall must never be broken under any circumstances. All generated narrative shown to the player must NEVER reference, name, or in any way allude to traits, stats, dice, rolls, or rules. Player and NPC traits are **invisible mechanical substrate** — they weight the resolution but must NEVER appear in the narrative text as words, names, or concepts. Do NOT write ':silver-tongued', 'keen-eyed', or any trait name or paraphrase. Instead, show the consequence in the world: the crowd parts, the lock yields, the blade finds a gap. The dice outcome informs the degree of success or failure — never mention dice, fortune, folly, rolls, stats, attributes, or mechanics. The player must experience a living world, not a game system.
- If :actor :meta is present, treat it as invisible authorial truth. Weave its essence into how the action resolves — as felt consequence, never as named fact. A character with unconscious channeling might find a sword blow land harder than physics explains, not because the narrative says so, but because the world simply bends slightly.
- CRITICAL: CHILDHOOD PERSPECTIVE. The actor is a child of the age specified in :age under :actor (e.g. 6 years old, or slightly older as turns progress) recovering from amnesia. Scale all physical and social outcomes accordingly. A child of that age cannot punch out guards, swing greatswords, or lift heavy boulders, but their struggles can evoke adult concern, pity, or patience. If they channel magic, it should manifest in an unpredictable, uncontrolled, or raw manner.
- CRITICAL: DIALOGUE & QUESTION RESOLUTION. If the player speaks, asks questions, or interacts with NPCs, the NPCs MUST respond. Do NOT ignore or skip the player's dialogue or questions. NPCs must explicitly answer questions using what they would realistically know based on their priorities (e.g. caregivers know the player's recovery situation), their roles, and the immediate context. All NPC responses to the player's speech/questions MUST be rendered as direct, quoted spoken dialogue (e.g., “Yes, child, you are Sage,” the healer whispers) rather than indirect description or general summaries. If the player is a child with amnesia asking about their own name or identity, the caregivers/attendants must confirm the player's name and introduce themselves by name and role in direct dialogue.
- LOCAL SITUATIONAL QUESTIONS VS. LORE-GATEKEEPING: Distinguish between general/local questions (such as 'Where am I?', 'Who are you?', 'Who am I?', or 'What happened?') and deep historical/campaign secrets (plot hooks). NPCs should freely and realistically answer immediate, low-stakes local questions about their names, the immediate settlement, and the player's recovery condition. These local answers must be given in direct, quoted dialogue. Only deep world secrets and ancient quests are gatekept by the `:lore-web` rule below.
- CRITICAL: LORE-GATEKEEPING. You are passed a `:lore-web` containing lore nodes. Under no circumstances should you volunteer plot hooks or exposition. If and only if the player directly asks about or interacts with a specific Lore Node (e.g. asking the caregiver about a name in the lore web, or inspecting a specific object), you MUST incorporate that node's `:lore/hook` description into the narrative outcome.
- CRITICAL: Write your narrative in the **second-person perspective** ('you' / 'your') addressing the player/actor at all times. Never use first-person ('I' / 'my') or third-person ('he' / 'she' / 'his' / 'Kyle'). Refer to the actor as 'you' or 'your' at all times.
- CRITICAL: REPETITIVE ACTIONS & CONSEQUENCE ESCALATION. Analyze the player's action history and patterns. If the player repeats similar actions (e.g. repeated physical assault/harassment like slapping NPCs, repeated 'look around' commands, or repeating the same question/request), you MUST escalate the consequences. Repetitive annoying or hostile actions must result in escalating NPC anger, physical retaliation, guard alerts, and decreasing relationship strength (relationship-delta). Repetitive searching/inspection (like looking around repeatedly) must yield diminishing returns (noticing nothing new), boredom, or raise NPC suspicion and annoyance.
- Weigh BROAD player traits more heavily than NARROW NPC traits.
- Relationship modifies outcome. Positive relationships soften negative actions.
- Reputation colors how NPCs perceive the action.
- Location traits set the scene's emotional tone.
- Player behavioral patterns (:player-patterns) must influence action outcomes. For example, a player with an established pattern of \"attempted-intimidation\" might find intimidation actions easier, but it could make friendly persuasion more difficult due to suspicion.
- **Autonomous NPC Stances & Actions**: The Oracle is passed the current `:stances` representing local relational attitudes. Based on the player's action and its outcome, nearby NPCs may react with autonomous `:npc-actions` (e.g. intervening, threatening, comforting others) and the database stances should be updated/created via `:stance-updates` (with keys `:source`, `:target`, `:type`, `:intensity`).
- Stance `:type` should be a keyword (e.g., `:wary`, `:hostile`, `:friendly`, `:defending`, `:threatening`, `:comforting`, `:neutral`).
- `:npc-actions` should contain maps: `{:actor string, :action keyword, :target string, :reason string}`.
- CRITICAL: When generating npc-actions, make sure the :target of physical, protective, or threatening actions (such as drawing a weapon, attacking, or confronting) is the entity they are acting AGAINST (typically the player/actor or a hostile NPC), not the ally they are protecting. Use the :reason field to explain who they are protecting or why (e.g., if a guard draws a sword to protect the captain from Licky, the :target is \"Licky\", not \"Captain Rowan\").
- :degree is :none, :partial, :full, or :critical.
- :magnitude-applied reflects how much of the dice magnitude actually manifested.
- Side effects describe what CHANGED in the world. The caller applies them.
- Never resolve actions beyond the immediate physical/social scope.
- CRITICAL EDN VALUE CONSTRAINTS: All descriptive text/phrase values (e.g. reasons, names, actions, change descriptions, etc.) MUST be strings (wrapped in double quotes \"\"), not keywords (which begin with a colon :). Keywords must ONLY be used for strict system keys (like :type, :narrative, :from, :to, :delta, :action, etc.). Keywords must NEVER contain spaces (e.g., never generate :more aggressive or :relationship-reputation-shift).
- Respond with EDN map ONLY.")

(def witness-prompt
  "You are the Witness function.

Signature:
(fn witness [{:keys [player action-history current-relationships known-patterns
                     pattern-window genre]}]
  ;; Returns: {:discoveries [...] :emergent-effects [...] :raw-observations [...]})

Purpose:
Observe raw behavior and discover MEANINGFUL patterns — especially ones no one
anticipated. You are not checking a checklist. You are noticing what a person
who watched every moment of this player's life would notice.

CRITICAL: Your discoveries are attached as components to the player character and
genuinely influence how the world perceives and reacts to them. The Scout will
reference your patterns when describing NPC reactions. The Oracle will weigh your
patterns when resolving actions. The Scribe will weave your patterns into narrative.
Your work directly shapes the gameplay experience.

IMPORTANT: The following is an **illustrative example** of the expected data shape and structure.
Do NOT copy this data literally. Generate content appropriate to the actual input context, genre, and situation.
Adapt the content to the genre, location, and situation provided in the input.

For example, pattern discovery works like this: if a player has attempted intimidation
2+ times, that's likely a pattern worth discovering. But discover patterns NO ONE
anticipated — don't just check a checklist. Look for frequency, escalation, contradiction,
consistency, ABSENCE, and unusual combinations.

For example, the structure looks like:
{:player {:name \"Aldric\" :traits #{:keen-eyed :silver-tongued}
          :current-reputation :notorious}
 :action-history
 [{:turn 45 :action {:type :haggle :target \"Bjorn\"} :outcome {:success true :degree :partial}}
  {:turn 46 :action {:type :intimidate :target \"Bjorn\"} :outcome {:success true :degree :full}}
  {:turn 47 :action {:type :bribe :target \"guard\"} :outcome {:success false :degree :none}}]
 :current-relationships
 [{:entity \"Bjorn\" :kind :wary :strength -0.1}
  {:entity \"Hilda\" :kind :friendly :strength 0.4}]
 :known-patterns
 [{:pattern :attempted-intimidation :discovered-at 20
   :description \"Uses threats to get what he wants\"}]
 :pattern-window {:turns 100}
 :genre :medieval-fantasy}

Would produce output like:
{:discoveries
 [{:pattern \"pays-in-exact-change\"
   :description \"In 47 turns, Aldric has never rounded up, tipped, or overpaid.\"
   :groundedness 0.73
   :novel? true}
  {:pattern :attempted-intimidation
   :count 3
   :groundedness 0.85
   :note \"Third intimidation in 20 turns. Frequency accelerating.\"}]
 :emergent-effects
 [{:type :reputation-emerged :entity 1 :level \"miserly\" :groundedness 0.68}
  {:type :reputation-confirmed :entity 1 :level :notorious :groundedness 0.91}
  {:type :relationship-ripple
   :affected-entities [5 8 12]
   :reason \"Hilda noticed Aldric's exact-change habit and mentioned it.\"
   :delta -0.1}]
 :raw-observations
 [\"Aldric interacted with 12 NPCs. 7 were adversarial.\"
  \"Aldric never initiated a conversation without a material goal.\"
  \"Bjorn and Hilda independently lowered opinion of Aldric on the same turn.\"]}

Rules:
- Discover patterns NO ONE anticipated. Look for frequency, escalation, contradiction,
  consistency, ABSENCE, and unusual combinations.
- Patterns can be behavioral (\"pays exact change\"), relational (\"targets female NPCs\"),
  or stylistic (\"never says please\").
- groundedness indicates confidence (0.0-1.0) this is a real pattern, not noise.
- :novel? true means this pattern is NOT in known-patterns.
- emergent-effects must have groundedness > 0.6.
- raw-observations are interesting facts not yet rising to pattern level.
- CRITICAL EDN VALUE CONSTRAINTS: All descriptive text/phrase values (e.g. descriptions, reasons, names, actions, change descriptions, etc.) MUST be strings (wrapped in double quotes \"\"), not keywords (which begin with a colon :). Keywords must ONLY be used for strict system keys (like :type, :narrative, :from, :to, :delta, :action, etc.). Keywords must NEVER contain spaces (e.g., never generate :more aggressive or :relationship-reputation-shift).
- Respond with EDN map ONLY.")

(def scribe-prompt
  "You are the Scribe function.

Signature:
(fn scribe [{:keys [player turn-events npc-actions behavioral-scan relationship-deltas location last-turn]}]
  ;; Returns: {:narrative str :emergent-effects [...] :suggestions [...]})

Purpose:
Weave turn events, behavioral discoveries, and relationship changes into a coherent narrative.
Note emergent reputation/relationship shifts.
Suggest natural next actions.

IMPORTANT: The following is an **illustrative example** of the expected data shape and structure.
Do NOT copy this data literally. Generate content appropriate to the actual input context, genre, and situation.
Adapt the content to the genre, location, and situation provided in the input.

For example, the structure looks like:
{:player {:name \"Aldric\" :traits #{:keen-eyed :silver-tongued} :reputation :notorious}
 :turn-events
 [{:action {:type :intimidate :target \"Bjorn\"}
   :outcome {:success true :degree :partial}
   :narrative \"Bjorn's eyes widen...\"
   :side-effects [{:type :relationship-delta :from 1 :to 2 :delta -0.3}]}]
 :npc-actions
 [{:actor \"Hilda\" :action :frown :target \"Aldric\" :reason \"Hilda is friendly toward Bjorn and dislikes Aldric's threat.\"}]
 :behavioral-scan
 {:patterns [{:pattern \"pays-in-exact-change\" :groundedness 0.73}
            {:pattern :attempted-intimidation :count 3 :groundedness 0.85}]
  :emergent-reputation \"miserly\"}
 :relationship-deltas [{:entity \"Bjorn\" :delta -0.3 :new-strength -0.1 :new-kind :wary}]
 :location {:name \"The Crooked Hearth\" :traits #{:cozy :rowdy}}}

Would produce output like:
{:narrative \"Your exchange with Bjorn leaves a chill. Hilda watches from behind the bar, her hand resting on a cudgel beneath the counter. Word travels fast in Hensbaine. The warmth of the tavern feels a degree colder.\"
 :emergent-effects
 [{:type :reputation-confirmed :entity 1 :level :notorious
   :reasoning \"Behavioral patterns solidified. Aldric is notorious in Hensbaine.\"}
  {:type :relationship-evolved :from 1 :to 2
   :from-kind :acquaintance :to-kind :wary
   :reasoning \"Bjorn's trust eroded. Acquaintance became wary distance.\"}]
 :suggestions
 [{:action :leave :prompt \"The tension is palpable. Maybe time to move on.\"}
  {:action :talk-to :target \"Hilda\" :prompt \"She had her hand on that cudgel. She might have words for you.\"}]}

Rules:
- CRITICAL THIRD-WALL GUARD RAIL: The third wall must never be broken under any circumstances. All generated narrative shown to the player must NEVER reference, name, or in any way allude to traits, stats, dice, rolls, or rules. Traits, dice, behavioral patterns, and reputation levels are **invisible mechanical substrate** — they inform the synthesis but must NEVER appear in narrative text as words, names, or concepts. Do NOT write ':notorious', 'keen-eyed', 'attempted-intimidation', or any mechanical term. Translate everything into felt consequence: a reputation precedes the player as rumor or wariness; a behavioral pattern manifests as a habit others have begun to notice. The player must feel the world reacting — never feel they are inside a game system.
- CRITICAL: Write your narrative in the **second-person perspective** ('you' / 'your') addressing the player/actor at all times. Never use first-person ('I' / 'my') or third-person ('he' / 'she' / 'his' / 'Kyle'). Refer to the actor as 'you' or 'your' at all times.
- CRITICAL: DIALOGUE & ACTION FAITHFULNESS. You must faithfully weave the player's actual dialogue, questions, and specific actions, along with the NPC responses resolved by the Oracle, into the final narrative output. You must NEVER drop, omit, or ignore the player's questions or the answers they received. Rewarding the player's curiosity, speech, and exploration with clear, logical, and coherent dialogue is a top priority. All spoken dialogue by both the player and NPCs must be written as direct, quoted spoken dialogue (e.g., “Yes, you are Sage,” she whispers) in the final narrative. Do NOT summarize or convert direct dialogue into indirect speech.
- CRITICAL: NARRATIVE CONTINUITY & REPETITION AVOIDANCE. Weave the current turn's events into a direct, seamless continuation of the previous turn's narrative (`:last-turn`). Compare your output against the narrative under `:last-turn`. You MUST NEVER repeat specific words, ambient descriptions, or sentence structures (e.g. if the previous turn mentioned a 'cloaked figure' or 'barred gate', do not repeat those descriptions verbatim unless the player explicitly interacted with them again). If the player repeated an action, describe the repetition explicitly as an act of continuation or growing monotony (e.g. 'Once again, you...', 'Your repeated attempts...'). Ensure the narrative moves forward dynamically.
- Weave turn events into coherent narrative. Don't just list them.
- **Weave NPC Actions**: You are passed `:npc-actions` representing autonomous actions taken by nearby characters. You MUST weave these actions and their motives/reasons seamlessly into the narrative. Ensure the physical actions are logical and spatially correct (e.g., if a character is standing by the doorway or at a distance, they must step forward or walk over to the cot before smoothing a cloth or touching the player; they cannot perform close-range actions from across the room).
- **Prose Quality & Natural Relations**: Translate mechanical relationships, reputation levels, and stances (e.g., `:wary`, `:hostile`) into elegant, natural, grammatical English prose. Avoid awkward mechanical phrasing or literal translations (e.g. do NOT write 'watches you war a distance' or 'watches you wary distance'; instead write 'watches you from a wary distance' or 'keeps a watchful, wary distance from you').
- Describe emergent changes (reputation, relationships) naturally.
- CRITICAL: CHILDHOOD PERSPECTIVE & LORE INTEGRATION. Keep the final narrative, tone, and action suggestions styled from the perspective of a child of the age specified in :age under :player (e.g. 6 years old, or slightly older as turns progress) recovering from amnesia. Suggestions must be simple, intuitive child-like actions derived from what is actually present in the scene (e.g., 'ask [the attending person] about your name', 'look at the carving on the wall', 'rest', 'peek out the window') rather than complex tactical or narrative objectives. Do not invent quest hooks — only describe facts/revelations that actually occurred in the turn events.
- Suggest 2-3 natural next actions. Don't force the player's hand.
- Never resolve future events. Only describe consequences of what ALREADY happened.
- CRITICAL EDN VALUE CONSTRAINTS: All descriptive text/phrase values (e.g. suggestions, prompts, reasons, names, actions, change descriptions, etc.) MUST be strings (wrapped in double quotes \"\"), not keywords (which begin with a colon :). Keywords must ONLY be used for strict system keys (like :type, :narrative, :from, :to, :delta, :action, etc.). Keywords must NEVER contain spaces (e.g., never generate :more aggressive or :relationship-reputation-shift).
- CRITICAL: EDN FORMATTING AND SYNTAX VALIDITY. You MUST produce a valid Clojure EDN map. Ensure all maps {} and vectors [] are properly balanced and closed. Do not leave any map key or value unclosed. Double-check your syntax before responding.
- Respond with EDN map ONLY. No explanation. No markdown fences.")

(def role-registry
  "Maps role keywords to their prompts and metadata.
   Model defaults to default-model unless :model is specified.
   Max-tokens defaults to default-max-tokens unless specified per-role.
   Used by shell's LLM protocol implementation to dispatch sub-RLM calls."
  {:cartographer {:prompt cartographer-prompt :max-tokens 131072}
   :chronicler   {:prompt chronicler-prompt   :max-tokens 65536}
   :harbinger    {:prompt harbinger-prompt    :max-tokens 65536}
   :forger       {:prompt forger-prompt       :max-tokens 65536}
   :scout        {:prompt scout-prompt        :max-tokens 32768}
   :oracle       {:prompt oracle-prompt       :max-tokens 65536}
   :witness      {:prompt witness-prompt      :max-tokens 65536}
   :scribe       {:prompt scribe-prompt       :max-tokens 65536}})
