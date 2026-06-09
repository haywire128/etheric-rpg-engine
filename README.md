# Etheric RPG Engine

[![Clojure CI](https://img.shields.io/badge/Clojure-1.12-blue.svg)](https://clojure.org/)
[![License](https://img.shields.io/badge/License-EPL%201.0-green.svg)](https://opensource.org/licenses/EPL-1.0)
[![Bling UI](https://img.shields.io/badge/UI-Bling--Powered-magenta.svg)](https://github.com/paintparty/bling)

> "The mists part, revealing a world woven entirely of Clojure EDN and recursive language intelligence..."

The **Etheric RPG Engine** is a state-of-the-art, JIT-generated tabletop role-playing game (TTRPG) engine. It leverages the cutting-edge **Recursive Language Models (RLM)** pattern to construct a dynamic, persistent synthetic world. By treating the game database and player configurations as an external environment, the engine uses a team of highly-specialized, tiered LLM agents to act JIT on behalf of the world. 

With **Datahike** as its audit-trail-enabled graph database, **Aero** for data-driven player profiles, and **Bling** for a gorgeous dark, mystical terminal aesthetic, the Etheric RPG Engine represents a major leap forward in AI-driven procedural storytelling.

---

## ✦ Table of Contents
1. [Quick Start](#-quick-start)
2. [Architecture](#-architecture)
3. [Design Philosophy](#-design-philosophy)
4. [Core Game Mechanics](#-core-game-mechanics)
5. [The Seven Agent Roles](#-the-seven-agent-roles)
6. [Interactive Command Reference](#-interactive-command-reference)
7. [Configuration and aero Customization](#-configuration-and-aero-customization)
8. [Safety, Checks, and Balances](#-safety-checks-and-balances)
9. [Development and Verification](#-development-and-verification)
10. [References and Citations](#-references-and-attribution)

---

## ⚡ Quick Start

### Prerequisites
* **Java Development Kit (JDK)**: Version 17 or higher.
* **Clojure CLI**: Ensure `clojure` is installed and available in your shell.
* **OpenRouter API Key**: Set your API key in your environment:
  ```bash
  export OPENROUTER_API_KEY="your-openrouter-key-here"
  ```

### Installation
Clone the repository and fetch dependencies:
```bash
git clone https://github.com/haywire128/etheric-rpg-engine.git
cd etheric-rpg-engine
```

### Running the Live REPL
Step through the mists into the interactive conversational environment:
```bash
clojure -M:run-m
```

### Single-Turn CLI Driver
If you want to run single-turn state-persistent CLI playtesting (persisting world state to `data/` between runs):
```bash
# Initialize a brand new session
clojure -M -m haywire128.etheric-rpg-engine.interact /start

# Run a player action (state is persisted to disk)
clojure -M -m haywire128.etheric-rpg-engine.interact "Explore the sector terminals"
```

### Executing the Multi-Turn Playtest Script
Run a fully automated 3-turn live gameplay session with real-time LLM feedback and database audits:
```bash
clojure -M resources/live-playtest.clj
```

---

## 🏛 Architecture

The project adheres strictly to the **Functional Core + Imperative Shell** architecture. This boundary is represented across two main namespaces:

```
src/haywire128/etheric_rpg_engine/
├── (REPL Entry Point)              etheric_rpg_engine.clj
├── cli.clj       ← Presentation: Bling styling, terminal banners, and callout helpers.
├── core.clj      ← Pure functional core: Constants, dice math, pure relationship maps,
│                   role prompts with illustrative framing, and player input parser.
│                   No I/O or network connections.
└── shell.clj     ← Imperative shell: Datahike DB connections, OpenRouter LLM complete
                    implementation, RLM step execution, security code-eval validator,
                    and config load/save functions.
```

### The Three Protocols
To ensure complete isolation of concerns, we define exactly three boundaries:
1. `RLMEnv`: Represents the environment atom that the orchestrator LLM manipulates programmatically.
2. `WorldDB`: Abstract boundary for Datalog operations (Datalog queries, entity pulls, transaction vectors).
3. `LLM`: Abstract boundary for text completion via OpenRouter.

### Recursive Language Model (RLM) Loop
Unlike traditional scaffolds that feed huge text history into the prompt (leading to *context rot* and *hallucinations*), the RLM pattern treats the world prompt as part of an external environment:

```
                      +----------------------------------+
                      |         Root Orchestrator        |
                      |        (Clojure Code Gen)        |
                      +-----------------+----------------+
                                        |
                             Evaluates generated code
                                        v
                      +-----------------+----------------+
                      |          Clojure REPL          |
                      |  (Evaluates safely, captures IO)|
                      +--------+----------------+--------+
                               |                |
                Updates RLMEnv |                | Invokes Sub-Agent
                               v                v
                      +--------+-------+  +-----+--------+
                      |  Atom State    |  |  Sub-Agent   |
                      |  (Variables)   |  |  (Scout, etc)|
                      +----------------+  +--------------+
```

1. **Symbolic Handles**: The user prompt and intermediate variables are stored in an environment `RLMEnv` (Clojure atom). The LLM is only given constant-sized metadata (like string lengths) as a symbolic handle.
2. **Programmatic Sub-calls**: Code running inside the REPL can invoke the LLM recursively on programmatically generated snippets of the environment variables (e.g., calling the sub-RLM `Scout` inside loops).
3. **Truncated Feedback**: Output logs are truncated to constant sizes before being fed back into the next iteration of the root LLM, maintaining low token usage.

---

## 🧠 Design Philosophy

The engine's conceptual foundations rest on three major pillars of modern software engineering and computer science:

### 1. Adapted RLM (MIT CSAIL, 2026)
Adapted from the foundational paper *Recursive Language Models*, we treat long context as an external variable. Rather than feeding millions of tokens into the LLM, the orchestrator writes expressive Clojure code to inspect, decompose, and slice the context, executing nested sub-queries as needed. 

### 2. Simula Adaptation (Google Research, 2026)
We frame synthetic world-building as dataset mechanism design. It separates generation into two phases:
* **Ahead-Of-Time (AOT) Generation**: The highest levels of the world taxonomy are expanded recursively down into deep hierarchical branches representing the geographic and structural layers of the world (Kingdoms, Macro-Biomes, Micro-Biomes, Settlements, Landmarks).
* **Just-In-Time (JIT) Generation**: The tangible, immediate inhabitants, physical items, and sensory details are populated on-the-fly *only* when the player character enters their immediate perceptual range.

### 3. Entity Component System (ECS)
We leverage Datahike as our ECS backbone. Every object in the game is an entity, and components are dynamically transacted as namespaced attributes. Relationships are first-class, graph-based edges. As the player interacts with the world, the LLMs situationally upgrade/downgrade components, tracking state changes over time using Datalog.

---

## 🎲 Core Game Mechanics

### Invisible 2d20 (Fortune & Folly)
Whenever a player character attempts an action, the engine rolls two 20-sided dice behind the scenes:
* **Fortune**: Dictates the positive, helper forces.
* **Folly**: Dictates the negative, chaotic, or accidental forces.

The higher of the two dice determines the overall **Direction** of the outcome (`:fortune` or `:folly`). The ratio of the higher die to the maximum possible roll determines the **Magnitude** (`0.0` to `1.0`). An LLM (the `Oracle`) interprets these variables dynamically in the context of the player's traits and active environment.

### The Aperture Trait System
Traits are not just stat modifiers; they are lenses through which the world is perceived and resolved. 
* **Broad Player Traits**: Shape the narrative. A player with `:keen-eyed` perceives hidden physical details. A `:silver-tongued` player sees social openings. 
* **Narrow NPC Traits**: Constraints that shape target behaviors (e.g., a Blacksmith with `:meticulous` craftsmanship).
* **Balanced Generation**: If no traits are specified in the player's Aero config, the system JIT-generates a balanced, rich set of traits tailored to the chosen genre.

### Hierarchical Location Paths
Locations are not coordinate points but hierarchical semantic paths stored as vectors:
`[:Aethelgard :Kingdom-of-Rasmuth :Moss-Garden :South-West :The-Crooked-Hearth]`

Perceptual range is dynamically computed using taxonomy depths. When the player enters a location, the `Scout` JIT-describes the vicinity up to a dynamic perceptual depth.

### Dynamic Reputation & Behavioral Scan
### Persistent Character Aging
To represent long-term narrative progression, the engine dynamically updates and persists the player character's age:
* **Turn & Age Logging**: The player entity records `:entity/turns-played` and `:entity/age`.
* **Automated Progression**: Every successful player action increments `turns-played`. After $N$ turns (configured via `:player/turns-per-age-increment` in `config.edn`), the player's age is incremented.
* **Contextual Parametrization**: Current age is fed to the `Scout`, `Oracle`, and `Scribe` agents, allowing the prose and action suggestions to grow organically with the player.

### Emergent NPC Priorities (Motivations)
Rather than relying on static prompt descriptions to establish NPC behaviors and relationships, the engine utilizes a data-driven priorities system:
* **Schema**: Every NPC holds a `:npc/priorities` attribute containing an EDN-serialized vector of priority maps (e.g. `{:priority/type :care-for-entity, :priority/target-name "Sage", :priority/reason :familial, :priority/urgency :high}`).
* **Types & Motivations**: Supported priority types include `:care-for-entity`, `:maintain-routine`, `:protect-entity`, `:serve-faction`, and `:pursue-goal`.
* **Emergent Relationships**: Start-up scenarios (such as caregiving during convalescence) emerge dynamically at runtime based on generated NPC priorities rather than rigid prompt configurations.

---

## 👥 The Seven Agent Roles

To divide and conquer computational tasks, the engine defines exactly seven specialized roles:

| Role | Trigger | Scope | Model Tier | Output Shape |
|---|---|---|---|---|
| **Cartographer** | Unexplored region | Persisted, once per region | Strong | `{:nodes [...]}` |
| **Harbinger** | Empty location | Persisted, once per location | Medium | `{:entities [...]}` |
| **Forger** | Stubbed entity encountered | Persisted, once per entity | Medium | `{:entity-delta {...}}` |
| **Scout** | Location entry / observation | Ephemeral, cached | Fast | `{:narrative "...", :notable {...}}` |
| **Oracle** | Player action resolution | Ephemeral | Fast | `{:outcome {...}, :narrative "..."}` |
| **Witness** | End of turn | Ephemeral, patterns saved | Medium | `{:discoveries [...], :emergent-effects [...]}` |
| **Scribe** | End of turn (after Witness) | Ephemeral | Medium | `{:narrative "...", :suggestions [...]}` |

---

## 💬 Interactive Command Reference

Inside the interactive REPL (`clojure -M:run-m`), the console shifts into a rich, purple/magenta aesthetic. The following commands are supported:

* `/help` - Show a beautifully formatted table of available commands and descriptions.
* `/status` - Display your character's current active configuration and database parameters printed as syntax-highlighted EDN.
* `/config` - Step into the **World Weaver Conversational Wizard**. The World Weaver guiding persona will converse with you naturally to refine your player traits, world seeds, or genre parameters, automatically validating and saving your configuration.
* `/quit` - Envelope your session in the mists and return to reality.

---

## ⚙️ Configuration and Aero Customization

The system parameters are driven by Aero inside `resources/config.edn`. It represents the "initial conditions" of the dynamical game system.

```edn
{:player/name "Hiro",
 :player/genre :cyberpunk,
 :player/traits #{:hacker :rain-slicked :neon-drenched},
 :world/seed nil,
 :llm/default-model "deepseek/deepseek-v4-pro",
 :llm/max-tokens 65536}
```

### Config Archive-and-Replace
When the World Weaver updates your configuration at runtime, the engine uses an **archive-and-replace strategy**:
1. It copies the old configuration to `resources/config-archive/config-YYYYMMDD-HHMMSS.edn`.
2. It validates the new configuration map against structural schemas.
3. It overwrites `resources/config.edn` with a formatted, updated EDN structure.

---

## 🛡 Safety, Checks, and Balances

The Etheric RPG Engine implements a multi-layered security and validation net:

### 1. Structural Config Validation
Before any configuration is written to disk, `shell/validate-config` verifies:
* The input is a valid Clojure map.
* `:player/name` is a non-empty string.
* `:player/genre` is a keyword.
* `:player/traits` is a set of keywords.
* `:llm/max-tokens` is a positive integer.
* `:llm/default-model` is a valid string.
* Successful round-trip serialization check (written output is parseable EDN).

### 2. LLM Code-Eval Sanitize Sandbox
To prevent malicious code injection or hallucinated side-effects during root LLM evaluation, `shell/validate-code` pre-scans all generated strings and aborts execution if it contains:
* System exit attempts (`System/exit`, `System/halt`).
* Shell execution commands (`clojure.java.shell/sh`, `Runtime`, `ProcessBuilder`).
* Unauthorized file system access (`slurp`, `spit`, `java.io`, `clojure.java.io`).
* Nested runtime evaluations (`eval`, `load-string`, `load-file`, `read-string`).
* Unauthorized runtime imports (`require`, `import`, `use`).

### 3. Eval Execution Timeout
All code evaluations inside `rlm-eval` are executed on an isolated thread via a `future`. The orchestrator enforces a strict **30-second execution timeout threshold**. If a hallucinated infinite loop is evaluated, the future is automatically canceled, and a descriptive `TimeoutError` is fed back into the LLM's history.

### 4. Reasoning Model Compatibility & Token Affordances
To support high-speed reasoning models like **Inception Mercury 2**, the engine implements specific structural affordances:
* **Thinking Block Stripping**: Inside `shell/extract-edn`, we automatically detect and strip `<think>...</think>` tags using multiline regular expressions. This ensures that the engine can ingest advanced reasoning pipelines while still extracting raw, parseable EDN maps without error.
* **Calibrated Token Budgets**: When running on models with a 128K context window (like Mercury 2), large output requests can exhaust the context or violate physical generation limits. We have downscaled the `role-registry` max output tokens (ranging from 4K to 16K) to guarantee compatibility while preserving more than enough room for complete, detailed, procedural EDN trees. This downscaling unlocks Inception's blazingly fast >1,000 tokens/sec speed (completing complex 3-role turns in under 2 seconds!).

---

## 🧪 Development and Verification

### Run the Tests
Execute the comprehensive suite containing 54 tests and 185 assertions:
```bash
clojure -T:build test
```

### Build an Uberjar
Compile the application and build an executable uberjar containing all compiled classes and embedded dependencies:
```bash
clojure -T:build ci
```

---

## 📚 References and Attribution

1. **Recursive Language Models**: Zhang, A. L., Kraska, T., & Khattab, O. (MIT CSAIL, 2026). *Recursive Language Models: Inference-Time Scaling for Infinite Context*. [arXiv:2512.24601v3](https://arxiv.org/abs/2512.24601v3).
2. **Google Simula System**: Davidson, T. R., Seguin, B., Bacis, E., Ilharco, C., & Harkous, H. (Google Research, 2026). *Designing Synthetic Datasets for the Real World*. [TMLR OpenReview](https://openreview.net/pdf?id=NALsdGEPhB).
3. **Bling Library**: Exclusive terminal colorways, banners, and hifi print formatting provided by [paintparty/bling](https://github.com/paintparty/bling).

---

*Woven by Kylehowley © 2026. Distributed under the Eclipse Public License version 1.0.*
