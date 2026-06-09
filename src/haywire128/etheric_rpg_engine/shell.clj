(ns haywire128.etheric-rpg-engine.shell
  "Imperative shell. All side effects: Datahike, OpenRouter, Aero I/O.
   Implements core protocols: RLMEnv, WorldDB, LLM."
  (:require [datahike.api :as d]
            [haywire128.etheric-rpg-engine.core :as c]
            [haywire128.etheric-rpg-engine.cli :as cli]
            [taoensso.timbre :as timbre]
            [openrouter.core :as or]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [aero.core :as aero]))

(timbre/set-min-level! :warn)

;; ══════════════════════════════════════════════════════════════════════════════
;; LLM Protocol Implementation
;; ══════════════════════════════════════════════════════════════════════════════

(defn- get-api-key
  ([] (System/getenv "OPENROUTER_API_KEY"))
  ([opts] (or (:api-key opts) (get-api-key))))

(defn- extract-edn
  "Parse LLM response string as EDN. Strips markdown fences and reasoning blocks first.
   Throws if result is not a map."
  [content]
  (let [no-think (str/replace content #"(?s)<think>.*?</think>" "")
        cleaned (-> no-think
                    (str/replace #"```edn" "")
                    (str/replace #"```clojure" "")
                    (str/replace #"```" "")
                    str/trim)
        parsed  (edn/read-string {:default (fn [_ _]
                                             (throw (ex-info "Unreadable EDN" {:raw content})))}
                                 cleaned)]
    (if (map? parsed)
      parsed
      (throw (ex-info (str "Expected EDN map, got " (type parsed))
                      {:raw content :parsed parsed})))))

(defrecord OpenRouterLLM []
  c/LLM
  (complete [_ messages model opts]
    (try
      (let [api-key    (get-api-key opts)
            _          (when-not api-key
                         (throw (ex-info "Missing OpenRouter API Key. Please set the OPENROUTER_API_KEY environment variable." {})))
            max-tokens (or (:max-tokens opts) 2048)
            body       (or/chat-completion
                        {:model model
                         :messages messages
                         :api-key api-key
                         :max-tokens max-tokens})
            ;; Check if OpenRouter returned an error block
            api-error  (get-in body ["error" "message"])
            _          (when api-error
                         (throw (ex-info (str "OpenRouter API Error: " api-error) {:body body})))
            content    (or/chat-content body)
            cost       (or (get-in body ["usage" "total_tokens"]) 0)]
        {:content content :cost cost :raw body})
      (catch Throwable t
        (let [cause (.getCause t)
              msg (if cause (.getMessage cause) (.getMessage t))]
          {:content nil :cost 0 :error msg})))))

(defn llm-client
  "Factory for LLM protocol implementation."
  []
  (->OpenRouterLLM))

;; ══════════════════════════════════════════════════════════════════════════════
;; WorldDB Protocol Implementations
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord InMemoryDB [state]
  c/WorldDB
  (q-range [_ _path _depth]
    [])
  (q-entity [_ id]
    (get @state id))
  (q-location [_ _path]
    nil)
  (q-rels [_ _entity-id]
    [])
  (q-patterns [_ _entity-id]
    {})
  (q-history [_ _entity-id _since]
    [])
  (q-player-patterns [_ _player-id]
    [])
  (q-action-history [_ _player-id _window]
    [])
  (transact! [_ tx-data]
    (let [tx-map (if (map? tx-data)
                   tx-data
                   (into {} (map (fn [ent]
                                   [(or (:db/id ent) (:db/id (meta ent))) (dissoc ent :db/id)])
                                 tx-data)))]
      (swap! state (fn [old-state]
                     (reduce-kv (fn [m k v]
                                  (if (and (map? v) (map? (get m k)))
                                    (update m k merge v)
                                    (assoc m k v)))
                                old-state
                                tx-map))))))

(defn in-memory-db
  "Factory for in-memory WorldDB (testing/development)."
  []
  (->InMemoryDB (atom {})))

(defrecord DatahikeDB [conn]
  c/WorldDB
  (q-range [_ path depth]
    (let [db      (d/db conn)
          all-ids (if (empty? path)
                    (map first (d/q '[:find ?e :where [?e :entity/type]] db))
                    (let [path-prefix (str/join "/" (map name path))
                          loc-ids     (->> (d/q '[:find ?e ?p :where [?e :location/path-str ?p]] db)
                                           (filter #(clojure.string/starts-with? (second %) path-prefix))
                                           (map first))
                          tax-ids     (->> (d/q '[:find ?e ?p :where [?e :taxonomy/path-str ?p]] db)
                                           (filter #(clojure.string/starts-with? (second %) path-prefix))
                                           (map first))]
                      (concat loc-ids tax-ids)))]
      (take depth (keep #(c/q-entity _ %) all-ids))))
  (q-entity [_ id]
    (when id
      (let [e (d/pull (d/db conn) '[*] id)]
        (when (seq (dissoc e :db/id)) e))))
  (q-location [_ path]
    (let [db        (d/db conn)
          path-str  (str/join "/" (map name path))
          candidates (d/q '[:find ?e ?p
                            :where [?e :location/path-str ?p]]
                          db)]
      (when-let [eid (some (fn [[e p]] (when (= p path-str) e)) candidates)]
        (c/q-entity _ eid))))
  (q-rels [_ entity-id]
    (let [db (d/db conn)]
      (keep (fn [[e]]
              (when-let [ent (d/pull db '[:relationship/from :relationship/to :relationship/strength :db/id] e)]
                (when (seq (dissoc ent :db/id))
                  ent)))
            (d/q '[:find ?r
                   :where [?r :relationship/from ?e]
                   :in $ ?e]
                 db entity-id))))
  (q-patterns [_ entity-id]
    (when entity-id
      (when-let [e (d/pull (d/db conn) '[:behavior/pattern] entity-id)]
        (seq (:behavior/pattern e)))))
  (q-history [_ entity-id since]
    (try
      (d/q '[:find [(pull ?e [:entity/name]) ...]
             :in $ ?e ?since
             :where [?e :entity/type]
             [(.getTime ?since) ?t]]
           (d/since (d/db conn) since) entity-id since)
      (catch Exception _ [])))
  (q-player-patterns [_ player-id]
    (when player-id
      (let [db (d/db conn)]
        (keep (fn [[e]]
                (when-let [p (d/pull db '[:behavior/pattern-name :behavior/groundedness
                                          :behavior/discovered-at :behavior/description :db/id] e)]
                  (when (seq (dissoc p :db/id))
                    p)))
              (d/q '[:find ?p
                     :where [?p :behavior/player-ref ?e]
                     :in $ ?e]
                   db player-id)))))
  (q-action-history [_ player-id window]
    (when player-id
      (let [db (d/db conn)
            current-turn (or (ffirst (d/q '[:find (max ?t)
                                            :where [_ :action/turn ?t]]
                                          db))
                             0)
            lower (- current-turn (or window 100))]
        (keep (fn [[e]]
                (when-let [a (d/pull db '[:action/turn :action/type :action/target
                                           :action/outcome :db/id] e)]
                  (when (seq (dissoc a :db/id))
                    (update a :action/outcome #(when % (try (edn/read-string %) (catch Exception _ %)))))))
              (d/q '[:find ?a
                     :where [?a :action/player-ref ?e]
                            [?a :action/turn ?t]
                            [(>= ?t ?lower)]
                     :in $ ?e ?lower]
                   db player-id lower)))))
  (transact! [_ tx-data]
    (d/transact conn tx-data)))

(def schema
  "Complete Datahike schema for the RPG engine.
   Path values are stored as pr-str'd vectors (e.g. \"[:kingdom :district :tavern]\").
   Complex nested data (personality, npc-patterns, children, history) is EDN-serialized."
  [{:db/ident :entity/type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :entity/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :entity/profession :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :entity/personality :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :entity/appearance :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :entity/attributes :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :entity/age :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :entity/turns-played :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :stubbed? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :trait/set :db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   {:db/ident :trait/registry :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :location/path-str :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :location/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :location/loc-type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :location/traits :db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   {:db/ident :location/description :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :location/parent-path-str :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :location/entities :db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   {:db/ident :taxonomy/path-str :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :taxonomy/depth :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :taxonomy/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :taxonomy/node-type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :taxonomy/traits :db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   {:db/ident :taxonomy/atmosphere :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :taxonomy/npc-patterns :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :taxonomy/children :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :relationship/from :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :relationship/to :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :relationship/strength :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
   {:db/ident :relationship/history :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :behavior/pattern :db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   {:db/ident :behavior/pattern-count :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :behavior/pattern-name :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :behavior/groundedness :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
   {:db/ident :behavior/discovered-at :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :behavior/player-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :behavior/description :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :action/turn :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :action/type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :action/target :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :action/outcome :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :action/player-ref :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :reputation/level :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :session/turn :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :session/started-at :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}
   {:db/ident :generated/at :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}
   {:db/ident :generated/by :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :forged/at :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}
   {:db/ident :forged/by :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :stance/source :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :stance/target :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :stance/type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :stance/intensity :db/valueType :db.type/double :db/cardinality :db.cardinality/one}
   {:db/ident :lore/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :lore/type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :lore/description :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :lore/connections :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :lore/hook :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   ;; NPC motivational priorities — EDN-serialized vector of priority maps.
   ;; Each map: {:priority/type kw :priority/target-name str :priority/reason kw :priority/urgency kw}
   ;; :priority/type values: :care-for-entity :maintain-routine :protect-entity :serve-faction :pursue-goal
   ;; :priority/reason values: :professional :familial :altruistic :obligatory :fearful :mercenary
   {:db/ident :npc/priorities :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])

(defn datahike-db
  "Factory for Datahike-backed WorldDB. Creates an in-memory database.
   Optionally pass a config map for file-backed storage."
  ([]
   (datahike-db {:store {:backend :mem :id (str "rpg-" (java.util.UUID/randomUUID))}}))
  ([config]
   (d/create-database config)
   (let [conn (d/connect config)]
     (d/transact conn schema)
     (->DatahikeDB conn))))

;; ══════════════════════════════════════════════════════════════════════════════
;; RLMEnv Protocol Implementation
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord AtomRLMEnv [atom]
  c/RLMEnv
  (env-get [_ key]
    (or (get-in @atom [:variables key]) (get @atom key)))
  (env-set [_ key val]
    (swap! atom assoc-in [:variables key] val)
    nil)
  (finalize! [_ val]
    (swap! atom assoc :final val)
    nil)
  (finalized? [_]
    (some? (:final @atom)))
  (summary [_]
    (let [{:keys [variables]} @atom
          prompt (or (:prompt variables) (:prompt @atom))]
      {:prompt-type (:type prompt)
       :prompt-length (count (:raw prompt))
       :variables (into {}
                    (map (fn [[k v]]
                           [k (cond
                                (map? v) (select-keys v [:name :type :explored? :summary])
                                (coll? v) {:count (count v) :sample (take 2 v)}
                                :else v)]))
                    variables)})))

(defn rlm-env
  "Factory for RLMEnv. Seed with initial prompt."
  [prompt]
  (->AtomRLMEnv (atom {:variables {:prompt prompt} :hist [] :iteration 0})))

(defn restore-rlm-env
  "Create an RLMEnv from a restored state map."
  [state-map]
  (->AtomRLMEnv (atom state-map)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Role Dispatch (Sub-RLM)
;; ══════════════════════════════════════════════════════════════════════════════

(defn log-debug
  "Log out-of-character debug messages to data/debug.log instead of stdout."
  [msg]
  (try
    (let [log-file (io/file "data/debug.log")]
      (io/make-parents log-file)
      (spit log-file (str (.toString (java.util.Date.)) " " msg "\n") :append true))
    (catch Exception _ nil)))

(defn complete-with-retry
  "Call LLM complete with retry and exponential backoff."
  [llm hist model opts retries max-retries]
  (let [res (try
              (c/complete llm hist model opts)
              (catch Throwable t
                {:content nil :cost 0 :error (.getMessage t)}))
        {:keys [content cost error]} res]
    (cond
      (not error)
      {:content content :cost cost}

      (< retries max-retries)
      (let [backoff-ms (* 1000 (int (Math/pow 2 retries)))]
        (log-debug (str "  [API ERROR] " error))
        (log-debug (str "  [API Retry] Retrying in " backoff-ms " ms..."))
        (Thread/sleep backoff-ms)
        (recur llm hist model opts (inc retries) max-retries))

      :else
      {:error (str "LLM API Failure after " max-retries " retries: " error)})))

(defn- extract-trait-keywords
  "Recursively find all keywords under trait-related keys in context."
  [m]
  (let [trait-keys #{:traits :trait/set :player/traits}
        find-traits (fn find-traits [x current-key]
                      (cond
                        (keyword? x) (if (trait-keys current-key) #{x} #{})
                        (map? x) (reduce (fn [acc [k v]]
                                           (into acc (find-traits v k)))
                                         #{}
                                         x)
                        (coll? x) (reduce (fn [acc val]
                                            (into acc (find-traits val current-key)))
                                          #{}
                                          x)
                        :else #{}))]
    (find-traits m nil)))

(def ^:private trait-replacements
  {"noble-blood" "heritage"
   "noble blood" "heritage"
   "sword-saint" "mastery of the blade"
   "sword saint" "mastery of the blade"
   "arcane-resonance" "mystical attunement"
   "arcane resonance" "mystical attunement"
   "innate-weaving" "instinctive touch"
   "innate weaving" "instinctive touch"
   "imperceptible-edge" "subtle advantage"
   "imperceptible edge" "subtle advantage"
   "unyielding-adaptation" "resilience"
   "unyielding adaptation" "resilience"
   "proleptic-blur" "blinding speed"
   "proleptic blur" "blinding speed"
   "keen-eyed" "perception"
   "keen eyed" "perception"
   "shadow-walker" "quiet step"
   "shadow walker" "quiet step"
   "silver-tongued" "eloquence"
   "silver tongued" "eloquence"})

(def ^:private general-replacements
  [;; Exact phrases first
   [(re-pattern "(?i)\\bfortune die\\b") "fates"]
   [(re-pattern "(?i)\\bfortune roll\\b") "effort"]
   [(re-pattern "(?i)\\bfolly die\\b") "misfortune"]
   [(re-pattern "(?i)\\bfolly roll\\b") "misstep"]
   [(re-pattern "(?i)\\broll of the dice\\b") "stroke of fate"]
   [(re-pattern "(?i)\\broll of dice\\b") "stroke of fate"]
   [(re-pattern "(?i)\\b20-sided\\b") "unseen"]
   [(re-pattern "(?i)\\bd20\\b") "fate"]
   ;; Individual words
   [(re-pattern "(?i)\\bt_r_a_i_t_s?\\b") "talent"]  ;; Avoid matching actual word trait in code but scrub user output
   [(re-pattern "(?i)\\btraits?\\b") "talent"]
   [(re-pattern "(?i)\\bdice\\b") "fates"]
   [(re-pattern "(?i)\\bdie\\b") "fate"]
   [(re-pattern "(?i)\\brolls?\\b") "attempts"]
   [(re-pattern "(?i)\\brolled\\b") "attempted"]
   [(re-pattern "(?i)\\brolling\\b") "attempting"]
   [(re-pattern "(?i)\\bfolly\\b") "misstep"]
   [(re-pattern "(?i)\\bmechanics?\\b") "principles"]
   [(re-pattern "(?i)\\bstats?\\b") "abilities"]
   [(re-pattern "(?i)\\battributes?\\b") "characteristics"]
   [(re-pattern "(?i)\\bmodifiers?\\b") "influences"]])

(defn scrub-third-wall
  "Scrub any mention or allusion to traits, dice, or rules to protect the third wall."
  [text context]
  (if (string? text)
    (let [traits (extract-trait-keywords context)
          text-with-traits (reduce (fn [t kw]
                                     (let [n (name kw)
                                           n-spaced (str/replace n #"[-_]" " ")
                                           rep-n (get trait-replacements n "")
                                           rep-spaced (get trait-replacements n-spaced "")]
                                       (-> t
                                           (str/replace (re-pattern (str "(?i)\\b" (java.util.regex.Pattern/quote n) "\\b")) rep-n)
                                           (str/replace (re-pattern (str "(?i)\\b" (java.util.regex.Pattern/quote n-spaced) "\\b")) rep-spaced))))
                                   text
                                   traits)]
      (reduce (fn [t [pat rep]]
                (str/replace t pat rep))
              text-with-traits
              general-replacements))
    text))

(defn- scrub-all-strings
  "Recursively walk map/collection and scrub all string values."
  [x context]
  (cond
    (string? x) (scrub-third-wall x context)
    (map? x) (reduce-kv (fn [m k v] (assoc m k (scrub-all-strings v context))) {} x)
    (coll? x) (mapv #(scrub-all-strings % context) x)
    :else x))

(defn sub-rlm
  "Invoke a role-specific sub-RLM. Used by root LLM's generated code.
   Returns parsed EDN from role's response."
  [llm context role-kw]
  (let [{:keys [prompt model max-tokens]} (get c/role-registry role-kw)
        _ (when-not prompt
            (throw (ex-info (str "Unknown role: " role-kw)
                            {:role role-kw :available (keys c/role-registry)})))
        model (or model c/default-model)
        max-tokens (min (or max-tokens c/default-max-tokens) c/default-max-tokens)
        base-messages [{:role :system :content prompt}
                       {:role :user :content (pr-str context)}]]
    (loop [retries 0
           messages base-messages]
      (let [opts {:max-tokens max-tokens}
            api-res (complete-with-retry llm messages model opts 0 3)
            {:keys [content cost error]} api-res]
        (log-debug (str "  [Sub-RLM " role-kw " (attempt " (inc retries) ")] Input: " (pr-str context)))
        (log-debug (str "  [Sub-RLM " role-kw "] Output Raw: " content))
        (if content
          (let [parsed-res (try
                             (let [parsed (extract-edn content)]
                               (if (map? parsed)
                                 {:success true :parsed parsed}
                                 {:success false :error (str "Expected EDN map, got " (type parsed))}))
                             (catch Exception e
                               {:success false :error (.getMessage e)}))]
            (if (:success parsed-res)
              (let [res (scrub-all-strings (:parsed parsed-res) context)]
                (log-debug (str "  [Sub-RLM " role-kw "] Output Parsed: " (pr-str res)))
                res)
              (if (< retries 2)
                (do
                  (log-debug (str "  [Sub-RLM " role-kw "] Parse Error on attempt " (inc retries) ": " (:error parsed-res) ". Retrying with correction prompt..."))
                  (recur (inc retries)
                         (conj messages
                               {:role :assistant :content content}
                               {:role :user :content (str "ERROR: Your response could not be parsed as a Clojure EDN map: "
                                                          (:error parsed-res)
                                                          "\n\nPlease correct the Clojure syntax (ensure all maps/vectors/braces are closed, string values are double-quoted, keywords do not contain spaces or illegal characters) and output the full, corrected EDN map ONLY. No explanation, no markdown fences.")})))
                (do
                  (log-debug (str "  [Sub-RLM " role-kw "] Parse Error: " (:error parsed-res) " after max retries."))
                  {:parse-error (:error parsed-res) :raw content :cost cost}))))
          (do
            (log-debug (str "  [Sub-RLM " role-kw "] Output Error: " (or error :empty-content)))
            {:error (or error :empty-content) :cost cost}))))))

(defn flatten-taxonomy
  "Flatten nested Cartographer nodes into schema-compliant flat entities.
   Each node gets a :taxonomy/path-str based on parent path + name."
  [nodes parent-path depth]
  (let [parent-str (str/join "/" (map name parent-path))]
    (mapcat (fn [node]
              (let [name-str (or (:name node) "unknown")
                    path-str (if (empty? parent-str) name-str (str parent-str "/" name-str))
                    traits (or (:traits node) #{})
                    traits-set (if (coll? traits) (set (map keyword traits)) #{})
                    entity {:taxonomy/path-str path-str
                            :taxonomy/depth (or depth 1)
                            :taxonomy/name name-str
                            :taxonomy/node-type (or (:node-type node) :unknown)
                            :taxonomy/traits traits-set
                            :taxonomy/atmosphere (or (:atmosphere node) "")
                            :taxonomy/npc-patterns (pr-str (or (:npc-patterns node) []))
                            :taxonomy/children (pr-str (or (:children node) []))}]
                (cons entity
                      (when (seq (:children node))
                        (flatten-taxonomy (:children node) (conj (vec parent-path) (keyword name-str)) (inc (or depth 1)))))))
            nodes)))

(defn store-taxonomy!
  "Store Cartographer taxonomy nodes into the database.
   Transforms raw LLM output into schema-compliant entities."
  [db nodes parent-path depth]
  (let [entities (flatten-taxonomy nodes (or parent-path []) (or depth 1))]
    (c/transact! db entities)
    {:stored (count entities) :nodes entities}))

(defn store-location!
  "Store a location entity into the database."
  [db loc]
  (let [path-str (or (:path-str loc) (:name loc) "unknown")
        traits (or (:traits loc) #{})
        traits-set (if (coll? traits) (set (map keyword traits)) #{})
        entities-data (map (fn [e]
                             (let [etraits (or (:traits e) #{})
                                   etraits-set (if (coll? etraits) (set (map keyword etraits)) #{})]
                               {:entity/type (or (:type e) :npc)
                                :entity/name (or (:name e) "unknown")
                                :entity/profession (or (:profession e) :unknown)
                                :entity/personality (pr-str (or (:personality e) {}))
                                :entity/appearance (pr-str (or (:appearance e) {}))
                                :entity/attributes (pr-str (or (:attributes e) {}))
                                :trait/set etraits-set
                                :trait/registry (pr-str (or (:trait-info e) {}))}))
                           (:entities loc))
        entity (cond-> {:location/path-str path-str
                        :location/name (or (:name loc) "unknown")
                        :location/loc-type (or (:loc-type loc) :unknown)
                        :location/traits traits-set
                        :location/description (or (:description loc) "")}
                 (seq entities-data) (assoc :location/entities entities-data))]
    (c/transact! db [entity])
    entity))

(defn store-entity!
  "Store a forged entity into the database. Optionally link it to a location path."
  ([db ent] (store-entity! db ent nil))
  ([db ent loc-path]
   (let [traits (or (:traits ent) #{})
         traits-set (if (coll? traits) (set (map keyword traits)) #{})
         entity {:db/id "temp-ent"
                 :entity/type (or (:type ent) :npc)
                 :entity/name (or (:name ent) "unknown")
                 :entity/profession (or (:profession ent) :unknown)
                 :entity/personality (pr-str (or (:personality ent) {}))
                 :entity/appearance (pr-str (or (:appearance ent) {}))
                 :entity/attributes (pr-str (or (:attributes ent) {}))
                 :trait/set traits-set
                 :trait/registry (pr-str (or (:trait-info ent) {}))}]
     (if loc-path
       (let [path-str (if (string? loc-path) loc-path (str/join "/" (map name loc-path)))
             ;; Find existing location entity-id
             loc-eid (some (fn [[e p]] (when (= p path-str) e))
                           (d/q '[:find ?e ?p :where [?e :location/path-str ?p]] (d/db (:conn db))))]
         (if loc-eid
           (c/transact! db [entity {:db/id loc-eid :location/entities ["temp-ent"]}])
           (c/transact! db [(dissoc entity :db/id)])))
       (c/transact! db [(dissoc entity :db/id)]))
     (dissoc entity :db/id))))

(defn store-discovery!
  "Store a Witness discovery as a behavioral pattern in the database.
   Attaches the pattern to the player entity so it can be perceived downstream."
  [db discovery player-id]
  (let [pattern-val (or (:pattern discovery) (:pattern-name discovery) "unknown")
        pattern-name-str (if (keyword? pattern-val) (name pattern-val) (str pattern-val))
        entity {:behavior/pattern-name pattern-name-str
                :behavior/groundedness (or (:groundedness discovery) 0.5)
                :behavior/discovered-at (or (:discovered-at discovery) 0)
                :behavior/description (or (:description discovery) (pr-str discovery))
                :behavior/player-ref player-id}]
    (c/transact! db [entity])
    {:stored 1 :pattern entity}))

(defn log-action!
  "Log a player action to the database for action history tracking.
   Called after each player action completes."
  [db action-map player-id]
  (let [entity {:action/turn (or (:turn action-map) 0)
                :action/type (or (:type action-map) :unknown)
                :action/target (or (:target action-map) "none")
                :action/outcome (pr-str (or (:outcome action-map) {}))
                :action/player-ref player-id}]
    (c/transact! db [entity])
    {:logged 1 :action entity}))

(defn store-lore!
  "Store a lore node entity into the database."
  [db lore-node]
  (let [entity {:lore/name (:lore/name lore-node)
                :lore/type (keyword (:lore/type lore-node))
                :lore/description (:lore/description lore-node)
                :lore/connections (pr-str (or (:lore/connections lore-node) []))
                :lore/hook (:lore/hook lore-node)}]
    (c/transact! db [entity])
    entity))

(defn q-all-lore
  "Query all lore entities."
  [db]
  (let [db-val (d/db (:conn db))
        res (d/q '[:find ?e :where [?e :lore/name]] db-val)]
    (map (fn [[eid]]
           (let [pulled (d/pull db-val '[:lore/name :lore/type :lore/description :lore/connections :lore/hook] eid)]
             (update pulled :lore/connections #(if % (clojure.edn/read-string %) []))))
         res)))

(defn q-stances
  "Query all stance relations in the database. Returns a sequence of maps:
   {:source str :target str :type keyword :intensity double}"
  [db]
  (let [db-val (d/db (:conn db))]
    (map (fn [[src-name tgt-name type intensity]]
           {:source src-name :target tgt-name :type type :intensity intensity})
         (d/q '[:find ?src-name ?tgt-name ?type ?intensity
                :where [?s :stance/source ?src]
                       [?s :stance/target ?tgt]
                       [?s :stance/type ?type]
                       [?s :stance/intensity ?intensity]
                       [?src :entity/name ?src-name]
                       [?tgt :entity/name ?tgt-name]]
              db-val))))

(defn set-stance-by-names!
  "Update or create a stance using entity names."
  [db source-name target-name type intensity]
  (let [conn (:conn db)
        db-val (d/db conn)
        source-id (ffirst (d/q '[:find ?e :where [?e :entity/name ?n] :in $ ?n] db-val source-name))
        target-id (ffirst (d/q '[:find ?e :where [?e :entity/name ?n] :in $ ?n] db-val target-name))]
    (when (and source-id target-id)
      (let [existing-id (ffirst (d/q '[:find ?s
                                       :where [?s :stance/source ?src]
                                              [?s :stance/target ?tgt]
                                       :in $ ?src ?tgt]
                                     db-val source-id target-id))
            entity (cond-> {:stance/source source-id
                            :stance/target target-id
                            :stance/type type
                            :stance/intensity intensity}
                     existing-id (assoc :db/id existing-id))]
        (d/transact conn [entity])))))


;; ══════════════════════════════════════════════════════════════════════════════
;; RLM Loop
;; ══════════════════════════════════════════════════════════════════════════════

;; flag: instruct to not copy the example (especially the data, could lead to narrow behavior instead of "creativity") input and output in the prompt, but to use them as illustrative examples.
(defn root-system-prompt
  "System prompt for the root LLM."
  []
  (str "You are the root orchestrator of a Clojure RPG engine.
Output executable Clojure code ONLY. No markdown, no explanation.

CRITICAL THIRD-WALL GUARD RAIL: The third wall must never be broken. All generated narrative shown to the player must NEVER reference, name, or in any way allude to traits, stats, dice, rolls, or rules. When orchestrating sub-RLM roles (like :scout, :oracle, :scribe), guarantee that no game-mechanical terms, dice terminology (fortune, folly, roll, dice, twenty-sided), or specific trait keyword names ever leak into player-facing text.

CRITICAL: DYNAMIC CONTEXT RESOLUTION & DYNAMIC ACTIONS
- Do NOT hardcode placeholder values like \"Aldric\", \"Kyle\", \"Guard\", `:intimidate`, or \"Misty Plains\" from the examples.
- You must query the player entity dynamically:
    (let [player (q-entity !db (env-get !env :player-id))
          player-name (:entity/name player)
          player-traits (set (:trait/set player))
          player-age (or (:entity/age player) 6)]
      ...)
- You must query the current location dynamically:
    (let [loc-path (env-get !env :current-location)
          location (q-location !db loc-path)
          loc-name (:location/name location)
          loc-traits (set (:location/traits location))
          loc-atmosphere (:location/atmosphere location)]
      ...)
- Parse the player's action dynamically using `(:player-input start-prompt)` and `(:raw start-prompt)`. Do NOT assume the action type is always `:intimidate` or `:unknown`.

CRITICAL: GAME START UP PROCEDURES
- For game START (when resolving Turn 0/initial startup):
  1. Call Cartographer to generate the world geography.
  2. Call Chronicler to generate the AOT Lore Web, and store all generated lore nodes using `store-lore!`.
  3. Place the player in a contextually appropriate recovery location (a sub-location of the starter settlement). The location's name, type, and traits must emerge from the world genre, player class, and social context — do NOT use a fixed name like 'Caregiver's Cottage'. Store with `store-location!`.
  4. JIT-populate this location using Harbinger, passing `{:recovering-player true}` in the context. Harbinger will generate one or more NPCs. At least one MUST have `:npc/priorities` containing a `:care-for-entity` entry targeting the player. The number, profession, name, appearance, and `:priority/reason` (e.g. `:professional`, `:familial`, `:altruistic`) are fully emergent from genre and world context. Store all entities with `store-entity!`.
  5. Call Scout to perceive the initial scene inside the recovery location (incorporating all generated entities and the lore web) and call `finalize!` to end the turn.

CRITICAL: MOVEMENT & TRAVEL RESOLUTION
- If the player's input indicates moving, walking, or traveling to a new location (e.g., \"walk to the square\", \"carriage travel to Riverhold\", \"go to the inn\"):
  1. Retrieve the player's current location path from env.
  2. Determine the destination path. Check the database and taxonomy to see if a location with that name exists as a child or sibling node. If so, build the destination path vector.
  3. Update the player's location in env: `(env-set !env :current-location destination-path)`.
  4. If this destination has not been JIT-populated as a location in the database (i.e. `(q-location !db destination-path)` returns nil), invoke the `:harbinger` sub-agent to JIT-generate the entities/atmosphere for that location, and use `store-entity!` to save them.
  5. Resolve the movement narrative using `:scout` (to perceive the new scene) or `:oracle` (if the journey itself has risks/actions), and proceed with the standard Scribe/Witness flow to finalize the turn.

IMPORTANT: The following are ALREADY BOUND and available in your scope:
  !env - the environment atom (use env-get, env-set, finalize!)
  !db - the database
  sub-rlm - function to call sub-agents: (sub-rlm context role)
  env-get - function: (env-get !env key)
  env-set - function: (env-set !env key value)
  finalize! - function: (finalize! !env result-map)
  q-entity - function: (q-entity !db entity-id)
  q-location - function: (q-location !db path)
  cast-dice - function: (cast-dice)
  parse-input - function: (parse-input raw-text)
  store-taxonomy! - function: (store-taxonomy! nodes parent-path depth) - stores Cartographer output
  store-location! - function: (store-location! loc-map) - stores a location
  store-entity! - function: (store-entity! entity-map) - stores a forged entity
  store-discovery! - function: (store-discovery! discovery player-id) - stores behavioral pattern discovery from Witness
  log-action! - function: (log-action! action-map player-id) - logs a player action for history
  q-player-patterns - function: (q-player-patterns !db player-id) - queries discovered patterns for player
  q-action-history - function: (q-action-history !db player-id window) - queries action history for player
  q-stances - function: (q-stances !db) - queries all active relational stances in the world
  set-stance-by-names! - function: (set-stance-by-names! !db source-name target-name type intensity) - sets or updates a stance relationship
  store-lore! - function: (store-lore! node) - stores lore web nodes
  q-all-lore - function: (q-all-lore !db) - queries all lore web nodes

DO NOT try to define or rebind these. Just use them directly.
DO NOT use transact! directly - use store-taxonomy!, store-location!, or store-entity! instead.

Available roles for sub-rlm:
  :cartographer - generate world taxonomy (returns {:nodes [...]})
  :chronicler - generate AOT lore web (returns {:lore-nodes [...]})
  :harbinger - generate location entities
  :forger - forge detailed entities
  :scout - perceive location (returns {:narrative \"...\"})
  :oracle - resolve player actions (returns {:narrative \"...\"})
  :witness - observe behavior patterns
  :scribe - generate narrative

The examples below show the **structure** of expected code. Generate code appropriate
to the actual game state and player input. Do not hardcode the example data.
The active player ID is passed in the starting prompt under `[:player :db/id]`. You should store it in the environment using `(env-set !env :player-id player-id)` during startup, and retrieve it using `(env-get !env :player-id)` in subsequent turns. Do not hardcode `:player-id-here` or any other placeholders.

EXAMPLE for game START — generate world geography, AOT lore web, place player in a contextually appropriate recovery location, JIT-populate attendants (with care-for-entity priorities) via Harbinger, and perceive via Scout.
NOTE: The names, locations, and NPC details below are ILLUSTRATIVE ONLY. Do NOT copy them. Generate content appropriate to the actual genre, player class, and social context:

(env-set !env :phase :world-gen)
(let [start-prompt (env-get !env :prompt)
      player-id (get-in start-prompt [:player :db/id])
      ;; Genre must come from player config — validated at startup, never defaulted here
      genre (get-in start-prompt [:player :player/genre])]
  (env-set !env :player-id player-id)
  ;; 1. Cartographer generates world taxonomy
  (let [tax (sub-rlm {:genre genre :parent {:path [] :depth 0 :name \"Eldoria\" :traits #{:ancient}} :target-depth 3} :cartographer)
        stored (store-taxonomy! (:nodes tax) [] 1)
        flat-nodes (:nodes stored)
        ;; Find a leaf settlement or landmark node (depth 3) to place the player
        starter-node (or (first (filter #(= 3 (:taxonomy/depth %)) flat-nodes))
                         (first flat-nodes))
        starter-path-str (:taxonomy/path-str starter-node)
        starter-path (map keyword (clojure.string/split starter-path-str #\"/\"))
        ;; 2. Chronicler generates AOT Lore Web / Web of Interest
        lore-res (sub-rlm {:genre genre :taxonomy flat-nodes} :chronicler)
        _ (doseq [node (:lore-nodes lore-res)]
            (store-lore! node))
        ;; 3. Place player in a contextually appropriate recovery location.
        ;; Name, type, and slug must reflect genre/class (e.g. :infirmary, :solar, :med-bay).
        ;; Do NOT use :caregivers-cottage or any fixed name -- derive from world context.
        recovery-slug :infirmary  ;; REPLACE with a genre/class-appropriate keyword slug
        recovery-path (conj (vec starter-path) recovery-slug)
        _ (env-set !env :current-location recovery-path)
        _ (store-location! {:name \"REPLACE-WITH-CONTEXTUAL-NAME\"
                            :loc-type :infirmary
                            :traits #{:warm :sheltered}
                            :description \"REPLACE-WITH-CONTEXTUAL-DESCRIPTION\"
                            :path-str (clojure.string/join \"/\" (map name recovery-path))})
        ;; 4. JIT-populate via Harbinger. At least one NPC MUST have :npc/priorities
        ;; with :care-for-entity targeting the player. Profession/name/reason are emergent.
        harbinger-res (sub-rlm {:genre genre
                                :location {:name \"REPLACE\" :loc-type :infirmary :traits #{:warm :sheltered}}
                                :npc-patterns []
                                :recovering-player true
                                :player-name player-name
                                :player-class (get-in (env-get !env :prompt) [:player :player/meta])}
                               :harbinger)
        stored-attendants (mapv #(store-entity! % recovery-path) (:entities harbinger-res))
        ;; Find sister regions (same depth/level) as surrounding areas
        sister-nodes (filter #(and (= 2 (:taxonomy/depth %))
                                   (clojure.string/starts-with? (:taxonomy/path-str %) (name (first starter-path))))
                             flat-nodes)
        patterns (q-player-patterns !db player-id)
        player (q-entity !db player-id)
        player-name (:entity/name player)
        player-traits (set (:trait/set player))
        player-age (or (:entity/age player) 6)
        recovery-loc (q-location !db recovery-path)
        scout (sub-rlm {:player {:name player-name :traits player-traits :age player-age :trait-info {}}
                        :location {:name (:location/name recovery-loc)
                                   :loc-type (:location/loc-type recovery-loc)
                                   :traits (set (:location/traits recovery-loc))
                                   :atmosphere (:location/atmosphere recovery-loc)
                                   :lineage recovery-path}
                        :surrounding (map #(select-keys % [:taxonomy/name :taxonomy/node-type :taxonomy/traits :taxonomy/atmosphere]) (take 3 sister-nodes))
                        :entities stored-attendants
                        :relationships []
                        :time {:of-day :DERIVE-FROM-WORLD :weather :DERIVE-FROM-WORLD :elapsed \\\"waking up\\\"}
                        :player-patterns patterns
                        :lore-web (:lore-nodes lore-res)}
                       :scout)]
    (finalize! !env {:narrative (:narrative scout) :success true})))

EXAMPLE for player ACTION — resolve an action dynamically querying the DB, including stances and lore web, run Witness, and then Scribe to synthesize:

(let [player-id (env-get !env :player-id)
      start-prompt (env-get !env :prompt)
      raw-input (:raw start-prompt)
      parsed-input (:player-input start-prompt)
      last-turn (:last-turn start-prompt)
      player (q-entity !db player-id)
      player-name (:entity/name player)
      player-traits (set (:trait/set player))
      player-age (or (:entity/age player) 6)
      loc-path (env-get !env :current-location)
      location (q-location !db loc-path)
      loc-name (:location/name location)
      loc-traits (set (:location/traits location))
      loc-atmosphere (:location/atmosphere location)
      patterns (q-player-patterns !db player-id)
      stances (q-stances !db)
      lore-web (q-all-lore !db)
      dice (cast-dice)
      ;; 1. Retrieve and resolve all entities in current location
      ents (keep #(q-entity !db (:db/id %)) (:location/entities location))
      ;; 2. Dynamically identify target NPC/object from raw player input
      target-candidate (some (fn [e]
                               (when (and (#{:npc :object} (:entity/type e))
                                          (or (clojure.string/includes? (clojure.string/lower-case raw-input) (clojure.string/lower-case (:entity/name e)))
                                              (clojure.string/includes? (clojure.string/lower-case raw-input) (name (:entity/type e)))))
                                  e))
                              ents)
      ;; 3. Retrieve relationships with the target
      relationship (when target-candidate
                     (some (fn [r]
                             (let [to-ent (q-entity !db (:relationship/to r))]
                               (when (= (:entity/name to-ent) (:entity/name target-candidate))
                                 r)))
                           (q-rels !db player-id)))
      target-name (or (:entity/name target-candidate) \"Environment\")
      result (sub-rlm {:action {:type (or (:type parsed-input) :unknown)
                                 :intent raw-input
                                 :raw raw-input}
                       :dice dice
                       :last-turn last-turn
                       :actor {:name player-name
                               :traits player-traits
                               :age player-age
                               :trait-info {}}
                       :target (if target-candidate
                                 {:name (:entity/name target-candidate)
                                  :traits (set (:trait/set target-candidate))
                                  :trait-info {}}
                                 {:name \"Environment\" :traits #{} :trait-info {}})
                       :location {:name loc-name
                                  :traits loc-traits
                                  :atmosphere loc-atmosphere
                                  :lineage loc-path}
                       :relationship {:strength (or (:relationship/strength relationship) 0.0)}
                       :actor-reputation :neutral
                       :player-patterns patterns
                       :stances stances
                       :lore-web lore-web}
                      :oracle)]
  ;; Update stances from Oracle result
  (doseq [su (:stance-updates result)]
    (set-stance-by-names! !db (:source su) (:target su) (:type su) (:intensity su))
    (log-action! {:turn (or (:turn last-turn) 1) :type (or (:type parsed-input) :unknown) :target target-name :outcome result} player-id))
  ;; Witness analyzes behavioral patterns dynamically
  (let [hist (q-action-history !db player-id 100)
        witness-res (sub-rlm {:player {:name player-name :traits player-traits :current-reputation :neutral}
                               :action-history hist
                               :current-relationships []
                               :known-patterns patterns
                               :pattern-window {:turns 100}
                               :genre :medieval-fantasy}
                              :witness)]
    ;; Store any newly discovered patterns
    (doseq [disc (:discoveries witness-res)]
      (store-discovery! disc player-id))
    ;; Extract relationship deltas from Oracle side-effects
    (let [rel-deltas (filter #(= (:type %) :relationship-delta) (:side-effects result))
          scribe-res (sub-rlm {:player {:name player-name :traits player-traits :age player-age :reputation :neutral}
                                :turn-events [{:action :unknown :target target-name} :outcome result]
                                :npc-actions (:npc-actions result)
                                :behavioral-scan witness-res
                                :relationship-deltas rel-deltas
                                :location {:name loc-name :traits loc-traits :atmosphere loc-atmosphere}
                                :last-turn last-turn
                                :lore-web lore-web}
                               :scribe)]
      (finalize! !env {:narrative (:narrative scribe-res) :success true}))))

For START: call Cartographer to create the world, then Scout to perceive.
For ACTION: call Oracle to resolve, log the action, run Witness, store discoveries, run Scribe, then finalize!.
Call finalize! to end the turn. Max " (str c/default-max-iterations) " iterations."))

(defn validate-code
  "Scan LLM-generated code string for dangerous patterns before eval.
   Throws ex-info if any unauthorized patterns are detected."
  [code-str]
  (let [code (str/replace code-str #";.*" "") ;; Strip single-line comments
        dangerous-patterns [#"\bclojure\.java\.shell\b"
                            #"\bclojure\.java\.io\b"
                            #"\bRuntime\b"
                            #"\bProcessBuilder\b"
                            #"\bslurp\b"
                            #"\bspit\b"
                            #"\bSystem/exit\b"
                            #"\bSystem/halt\b"
                            #"\beval\b"
                            #"\bload-string\b"
                            #"\bload-file\b"
                            #"(?<!clojure\.edn/)\bread-string\b"
                            #"\brequire\b"
                            #"\bimport\b"
                            #"\buse\b"]]
    (doseq [pat dangerous-patterns]
      (when (re-find pat code)
        (throw (ex-info (str "Security violation: detected unauthorized pattern '" pat "' in code.")
                        {:pattern pat :code code-str}))))))

(def ^:dynamic *rlmenv* nil)
(def ^:dynamic *rlmdb* nil)
(def ^:dynamic *rlmlm* nil)

(defn rlm-eval
  "Evaluate code generated by the root LLM."
  [code-str llm db env]
  (try
    (validate-code code-str)
    (let [s-ns "haywire128.etheric-rpg-engine.shell"
          c-ns "haywire128.etheric-rpg-engine.core"
          wrapped (str
                   "(let [!env :the-env\n"
                   "      !db :the-db\n"
                   "      sub-rlm (fn [c r] ((requiring-resolve '" s-ns "/sub-rlm) " s-ns "/*rlmlm* c r))\n"
                   "      env-get (fn [_ k] ((requiring-resolve '" c-ns "/env-get) " s-ns "/*rlmenv* k))\n"
                   "      env-set (fn [_ k v] ((requiring-resolve '" c-ns "/env-set) " s-ns "/*rlmenv* k v))\n"
                   "      finalize! (fn [_ v] ((requiring-resolve '" c-ns "/finalize!) " s-ns "/*rlmenv* v))\n"
                   "      cast-dice (fn [] ((requiring-resolve '" c-ns "/cast-dice)))\n"
                   "      parse-input (fn [s] ((requiring-resolve '" c-ns "/parse-player-input) s))\n"
                   "      q-entity (fn [_ id] ((requiring-resolve '" c-ns "/q-entity) " s-ns "/*rlmdb* id))\n"
                   "      q-location (fn [_ p] ((requiring-resolve '" c-ns "/q-location) " s-ns "/*rlmdb* p))\n"
                   "      transact! (fn [_ d] ((requiring-resolve '" c-ns "/transact!) " s-ns "/*rlmdb* d))\n"
                   "      store-taxonomy! (fn [nodes parent-path depth] ((requiring-resolve '" s-ns "/store-taxonomy!) " s-ns "/*rlmdb* nodes parent-path depth))\n"
                   "      store-location! (fn [loc] ((requiring-resolve '" s-ns "/store-location!) " s-ns "/*rlmdb* loc))\n"
                   "      store-entity! (fn ([ent] ((requiring-resolve '" s-ns "/store-entity!) " s-ns "/*rlmdb* ent)) ([ent loc] ((requiring-resolve '" s-ns "/store-entity!) " s-ns "/*rlmdb* ent loc)))\n"
                   "      store-discovery! (fn [discovery player-id] ((requiring-resolve '" s-ns "/store-discovery!) " s-ns "/*rlmdb* discovery player-id))\n"
                   "      log-action! (fn [action-map player-id] ((requiring-resolve '" s-ns "/log-action!) " s-ns "/*rlmdb* action-map player-id))\n"
                   "      q-range (fn [_ p d] ((requiring-resolve '" c-ns "/q-range) " s-ns "/*rlmdb* p d))\n"
                   "      q-rels (fn [_ id] ((requiring-resolve '" c-ns "/q-rels) " s-ns "/*rlmdb* id))\n"
                   "      q-player-patterns (fn [_ player-id] ((requiring-resolve '" c-ns "/q-player-patterns) " s-ns "/*rlmdb* player-id))\n"
                   "      q-action-history (fn [_ player-id window] ((requiring-resolve '" c-ns "/q-action-history) " s-ns "/*rlmdb* player-id window))\n"
                     "      q-stances (fn [_] ((requiring-resolve '" s-ns "/q-stances) " s-ns "/*rlmdb*))\n"
                     "      set-stance-by-names! (fn [_ src tgt typ intensity] ((requiring-resolve '" s-ns "/set-stance-by-names!) " s-ns "/*rlmdb* src tgt typ intensity))\n"
                     "      store-lore! (fn [node] ((requiring-resolve '" s-ns "/store-lore!) " s-ns "/*rlmdb* node))\n"
                     "      q-all-lore (fn [_] ((requiring-resolve '" s-ns "/q-all-lore) " s-ns "/*rlmdb*))]\n"
                    "  " code-str ")")
          eval-fut (future
                     (binding [*rlmenv* env *rlmdb* db *rlmlm* llm]
                       (eval (read-string wrapped))))
          res (deref eval-fut 120000 :timeout-exceeded)]
      (if (= res :timeout-exceeded)
        (do
          (future-cancel eval-fut)
          {:error (str "TimeoutError: Code evaluation exceeded 120000ms threshold.\n\nCode:\n" code-str)})
        res))
    (catch Throwable t
      (let [cause (.getCause t)
            msg (if cause (.getMessage cause) (.getMessage t))
            class-name (.getName (class (or cause t)))]
        {:error (str class-name ": " msg "\n\nCode:\n" code-str)}))))

(defn format-eval-error
  "Format a detailed error message for LLM feedback."
  [t code-str]
  (let [cause (.getCause t)
        msg (if cause (.getMessage cause) (.getMessage t))
        class-name (.getName (class (or cause t)))
        stack-trace (when cause
                      (some->> (.getStackTrace cause)
                               (take 3)
                               (map str)
                               (str/join "\n")))]
    (str "ERROR: Code evaluation failed\n"
         "\n"
         "Exception: " class-name "\n"
         "Message: " msg "\n"
         (when stack-trace (str "Stack trace:\n" stack-trace "\n"))
         "\n"
         "Your code:\n"
         "```clojure\n"
         code-str
         "\n```\n"
         "\n"
         "Common issues:\n"
         "- Unmatched parentheses, brackets, or braces\n"
         "- Using functions that aren't defined (check available functions)\n"
         "- Referencing variables that aren't bound in scope\n"
         "- Syntax errors in map/vector literals (missing colons, wrong structure)\n"
         "- Calling functions with wrong number of arguments\n"
         "\n"
         "Please fix the error and generate corrected code.")))

(defn rlm-step
  "Execute a single iteration of the RLM loop, updating history and environment.
   Returns {:status :continue} or {:status :error :msg msg}."
  [llm db env hist iter root-model max-tokens max-retries]
  (let [api-res (complete-with-retry llm @hist root-model {:max-tokens max-tokens} 0 max-retries)]
    (if (:error api-res)
      {:status :error :msg (:error api-res)}
      (let [{:keys [content cost]} api-res
            code (when content (str/trim content))]
        (log-debug (str "  [RLM iter " iter "] cost: " cost " code: " (count (or code "")) " chars"))
        (if (or (nil? content) (str/blank? code))
          ;; Empty response handling
          (do
            (swap! hist conj
                   {:role :assistant :content (or content "nil")}
                   {:role :user :content "ERROR: Empty response. Please generate valid Clojure code."})
            {:status :continue})
          
          ;; Evaluate the generated code
          (let [out (java.io.StringWriter.)
                result (try
                         (binding [*out* out]
                           (rlm-eval code llm db env))
                         (catch Throwable t
                           (let [msg (format-eval-error t code)]
                             (log-debug (str "  [RLM iter " iter "] EVAL ERROR: " msg))
                             {:error msg})))]
            (if (:error result)
              ;; Evaluation failed
              (do
                (log-debug (str "  [RLM iter " iter "] EVAL ERROR: " (:error result)))
                (swap! hist conj
                       {:role :assistant :content code}
                       {:role :user :content (str "ERROR: Code evaluation failed.\n\n" (:error result))})
                {:status :continue})
              
              ;; Evaluation succeeded
              (do
                (log-debug (str "  [RLM iter " iter "] Eval succeeded"))
                (swap! hist conj
                       {:role :assistant :content code}
                       {:role :user :content (str "SUCCESS: Code evaluation succeeded.\n\nConsole Output:\n" (or (str out) "nil"))})
                {:status :continue}))))))))

(defn rlm-loop
  "Main RLM loop with error feedback and retries."
  [llm db env prompt & {:keys [max-iterations max-retries root-model max-tokens]
                        :or {max-iterations c/default-max-iterations
                             max-retries 3}}]
  (swap! (:atom env) dissoc :final)
  (let [env-config (get @(:atom env) :config)
        root-model (or root-model (:llm/default-model env-config) c/default-model)
        max-tokens (min (or max-tokens (:llm/max-tokens env-config) c/default-max-tokens) c/default-max-tokens)]
    (c/env-set env :prompt prompt)
    (let [system-prompt (root-system-prompt)
          hist          (atom [{:role :system :content system-prompt}
                               {:role :user :content (pr-str prompt)}])]
      (loop [iter 0]
        (cond
          (c/finalized? env) 
          {:success true :result (c/env-get env :final) :iterations iter}
          
          (>= iter max-iterations) 
          {:success false :error :max-iterations :iterations iter}
          
          :else
          (let [step-res (rlm-step llm db env hist iter root-model max-tokens max-retries)]
            (if (= (:status step-res) :error)
              {:success false :error (:msg step-res) :iterations iter}
              (recur (inc iter)))))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Configuration Management (Aero)
;; ══════════════════════════════════════════════════════════════════════════════

(defn load-config
  "Load configuration map using Aero.
   Optionally pass a profile (e.g. :default, :custom) and a custom file path or File object."
  ([] (load-config nil "resources/config.edn"))
  ([profile-or-path]
   (if (or (string? profile-or-path) (instance? java.io.File profile-or-path))
     (load-config nil profile-or-path)
     (load-config profile-or-path "resources/config.edn")))
  ([profile path-or-file]
   (let [file (io/file path-or-file)]
     (if (.exists file)
       (aero/read-config file (when profile {:profile profile}))
       (if-let [res (io/resource (if (string? path-or-file) path-or-file (.getName file)))]
         (aero/read-config res (when profile {:profile profile}))
         (throw (ex-info (str "Configuration file " path-or-file " not found!") {})))))))

(defn validate-config
  "Validate configuration map. Throws descriptive ex-info on failure."
  [config-map]
  (when-not (map? config-map)
    (throw (ex-info "Configuration must be a map" {:config config-map})))
  (let [p-name  (:player/name config-map)
        genre (:player/genre config-map)
        traits (:player/traits config-map)
        max-tokens (:llm/max-tokens config-map)
        model (:llm/default-model config-map)]
    (when (or (nil? p-name) (not (string? p-name)) (str/blank? p-name))
      (throw (ex-info "Configuration must contain a non-empty :player/name string" {:config config-map})))
    (when (or (nil? genre) (not (keyword? genre)))
      (throw (ex-info "Configuration must contain a :player/genre keyword" {:config config-map})))
    (when (and traits (not (and (set? traits) (every? keyword? traits))))
      (throw (ex-info "Configuration :player/traits must be a set of keywords" {:config config-map})))
    (when (and max-tokens (not (and (integer? max-tokens) (pos? max-tokens))))
      (throw (ex-info "Configuration :llm/max-tokens must be a positive integer" {:config config-map})))
    (when (and model (not (string? model)))
      (throw (ex-info "Configuration :llm/default-model must be a string" {:config config-map})))
    ;; Verify it can be read back as valid EDN (round-trip check)
    (let [serialized (pr-str config-map)
          parsed (try (edn/read-string serialized) (catch Exception _ nil))]
      (when-not (map? parsed)
        (throw (ex-info "Configuration fails round-trip serialization check" {:config config-map}))))))

(defn save-config
  "Save the given config map back to resources/config.edn (or a custom path-or-file).
   Before overwriting, copies (archives) the previous version to resources/config-archive/"
  ([config-map] (save-config config-map "resources/config.edn"))
  ([config-map path-or-file]
   (validate-config config-map)
   (let [target-file (io/file path-or-file)
         archive-dir (io/file "resources/config-archive")]
     ;; Ensure archive directory exists
     (when-not (.exists archive-dir)
       (.mkdirs archive-dir))
     ;; If previous file exists, archive it
     (when (.exists target-file)
       (let [timestamp (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss") (java.util.Date.))
             archive-file (io/file archive-dir (str "config-" timestamp ".edn"))]
         (io/copy target-file archive-file)))
     ;; Write new config map as formatted EDN
     (with-open [w (io/writer target-file)]
       (binding [*print-length* nil
                 *print-level* nil]
         (clojure.pprint/pprint config-map w))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Game Entry Point
;; ══════════════════════════════════════════════════════════════════════════════

(defn generate-starting-hook
  "Dynamically generate an inciting incident / starting prologue hook using the LLM."
  [llm config]
  (let [p-name (or (:player/name config) "Aldric")
        genre  (or (:player/genre config) :medieval-fantasy)
        traits (or (:player/traits config) #{})
        model  (or (:llm/default-model config) c/default-model)
        system-prompt "You are the Game Master of a dynamic RPG engine. Your task is to write a single-paragraph inciting incident and opening prologue hook for a new adventure.
Speak directly to the player in the second-person perspective ('you' / 'your') at all times. Never use first-person ('I') or third-person ('he'/'she').
Establish how they got here, their starting status/equipment based on their traits, and a clear immediate objective appropriate to their custom genre.
Write ONLY the single-paragraph narrative. No explanation, no intro, no markdown."
        meta   (:player/meta config)
        user-prompt (str "Player Name: " p-name "\n"
                         "Genre: " (if (keyword? genre) (name genre) (str genre)) "\n"
                         "Traits: " (str/join ", " (map name traits))
                         (when (seq meta)
                           (str "\nCharacter Essence:\n"
                                (str/join "\n" (map (fn [[k v]] (str (name k) ": " v)) meta)))))
        messages [{:role :system :content system-prompt}
                  {:role :user :content (str/trim user-prompt)}]
        res (complete-with-retry llm messages model {:max-tokens 1024} 0 3)]
    (or (:content res) "You stand at the edge of a new world, ready to begin your journey.")))

(defn start-game
  "Initialize the game with player config. Returns game state map ready for the game loop.
   config: Aero-parsed config map with at least {:player/name str :player/genre kw}"
  [config]
  (let [llm     (llm-client)
        db      (datahike-db)
        player-name (or (:player/name config) "Aldric")
        player-traits (or (:player/traits config) #{})
        ;; Generate exciting inciting hook using the LLM dynamically!
        hook    (generate-starting-hook llm config)
        ;; Transact player entity into DB
        player-entity {:entity/type :player
                       :entity/name player-name
                       :trait/set player-traits
                       :entity/age 6
                       :entity/turns-played 0}
        _       (c/transact! db [player-entity])
        ;; Query player entity ID
        player-id (ffirst (d/q '[:find ?e :where [?e :entity/type :player]] (d/db (:conn db))))
        config  (assoc config :player/id player-id :player/hook hook)
        prompt  {:type :start
                 :player (-> (select-keys config [:player/name :player/genre :player/traits])
                             (assoc :db/id player-id)
                             (cond-> (seq (:player/meta config))
                               (assoc :meta (:player/meta config))))
                 :hook hook
                 :raw "Game started"}
        env     (rlm-env prompt)]
    ;; Store config in env state
    (swap! (:atom env) assoc :config config)
    {:llm llm :db db :env env :config config}))

(defn player-action
  "Process a player action through the RLM loop.
   game-state: map from start-game
   input-raw: string from player"
  [game-state input-raw]
  (let [{:keys [llm db env config]} game-state
        last-narrative (or (c/env-get env :last-narrative)
                           (:narrative (c/env-get env :final))
                           (:player/hook config))
        last-action    (c/env-get env :last-action)
        prompt {:type :action
                :player-input (c/parse-player-input input-raw)
                :raw input-raw
                :last-turn {:action last-action
                            :narrative last-narrative}}
        result (rlm-loop llm db env prompt)]
    (when (:success result)
      ;; Store the dynamic outcome in env for the NEXT turn!
      (c/env-set env :last-action input-raw)
      (c/env-set env :last-narrative (get-in result [:result :narrative]))
      ;; Increment turn count & potentially update age
      (let [player-id (or (c/env-get env :player-id)
                          (ffirst (d/q '[:find ?e :where [?e :entity/type :player]] (d/db (:conn db)))))
            player    (c/q-entity db player-id)
            current-turns (or (:entity/turns-played player) 0)
            next-turns (inc current-turns)
            current-age (or (:entity/age player) 6)
            interval (or (:player/turns-per-age-increment config)
                         (:turns-per-age-increment config)
                         10)
            next-age (if (zero? (mod next-turns interval))
                       (inc current-age)
                       current-age)]
        (c/transact! db [{:db/id player-id
                          :entity/turns-played next-turns
                          :entity/age next-age}])))
    result))
