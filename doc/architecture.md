# Etheric RPG Engine — Architecture

Based on: RLM (Recursive Language Models, MIT 2026), Simula (Google 2026), ECS pattern.

## 1. Architecture

Two namespaces. Functional core + imperative shell.

```
src/haywire128/etheric_rpg_engine/
├── (main entry, game REPL)         etheric_rpg_engine.clj
├── core.clj       ← Pure fns, data shapes, 3 protocols, constants, role registry.
│                    Zero I/O.
└── shell.clj      ← 3 protocol impls, PREPL lifecycle, RLM loop, game loop.
                     All side effects here.
```

## 2. Three Protocols

- **RLMEnv** — RLM environment atom. Root LLM manipulates via generated code in PREPL.
- **WorldDB** — Single boundary for all Datahike operations.
- **LLM** — Single boundary for all LLM inference via OpenRouter.

## 3. Dependencies

openrouter (local), fuzzable-predicate (local), datahike, aero, parinferish.

## 4. Seven Roles

| # | Role | Trigger | Lifecycle | Model Tier |
|---|------|---------|-----------|-------------|
| 1 | Cartographer | Unexplored region | Persisted, once per region | Strong |
| 2 | Harbinger | Empty location | Persisted, once per location | Medium |
| 3 | Forger | Stubbed entity | Persisted, once per entity | Medium |
| 4 | Scout | Location entry | Ephemeral, cached | Fast/cheap |
| 5 | Oracle | Player action | Ephemeral | Fast/cheap |
| 6 | Witness | End of turn | Ephemeral, discoveries persisted | Medium |
| 7 | Scribe | End of turn (after Witness) | Ephemeral | Medium |

Each role prompt uses code-as-prompt format: Clojure function signature + EDN examples.

## 5. RLM Integration

PREPL (clojure.core.server/io-prepl), EDN-native, same-JVM.
Root LLM generates code → PREPL evaluates → stdout truncated to constant-size metadata → fed back.

## 6. Cost Control

Model tiering, Scout caching, trait relevance filtering, stub batching, no off-screen simulation.

## 7. Error Recovery

PREPL errors → metadata → root LLM retries. Timeouts. OpenRouter backoff.

## 8. Character Age Tracking

- **Persistent State**: The player entity tracks `:entity/age` (starting at 6) and `:entity/turns-played` in the Datahike database.
- **Automated Aging**: The engine increments `:entity/turns-played` on every successful action and increments `:entity/age` every $N$ turns (configured via `:player/turns-per-age-increment` in `config.edn`).
- **Dynamic Parameterization**: Current age is injected into Scout, Oracle, and Scribe prompt contexts, ensuring player narrative and action suggestions scale with age progression.

## 9. NPC Priorities (Emergent Relationships)

- **Priorities Schema**: Every NPC holds a `:npc/priorities` attribute containing an EDN-serialized vector of priority maps.
- **Priority Structure**: Each map defines a motivation: `{:priority/type kw, :priority/target-name str, :priority/reason kw, :priority/urgency kw}`.
  - Types: `:care-for-entity`, `:maintain-routine`, `:protect-entity`, `:serve-faction`, `:pursue-goal`
  - Reasons: `:professional`, `:familial`, `:altruistic`, `:obligatory`, `:fearful`, `:mercenary`
- **Emergent Scenarios**: The start-up caregiving relationship is driven via the `recovering-player` context rule in Harbinger, generating NPCs with `:care-for-entity` priorities rather than hardcoding names or roles in the prompts.
