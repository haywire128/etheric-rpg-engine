# MIT's RLM paper.
https://arxiv.org/html/2512.24601v3

## RLM adaptation:
In this system: 
- a clojure repl replaces python's.
- structured data is used instead of unstructured text, but the same principles apply.
- Context management system wite is ***ABSOLUTLEY CRITICAL*** to the system, and is designed to mitigate both context bloat and hallucination, by ensuring that the system only has access to the information that is relevant to the current situation, and that the information is presented in a way that is easy for the system to understand and use.

# Google simula system.
https://research.google/blog/designing-synthetic-datasets-for-the-real-world-mechanism-design-and-reasoning-from-first-principles/

## simula adaptation:
- the same principles apply, but the system is *NOT* generating a synthetic dataset, but rather a synthetic world.
- AOT generation: Granularity Highest level of a world taxonomy all the way down to: people, places, and things of interest.
- JIT generation: the immediate tangible people, places, and things within the perceptual range of the player character.

### A note on location:
- instead of coordinates, location is a heirarchical path, EDN example: :Aethelgard/Kingdom-Of-Rasmuth/Moss-Garden/HensBaine-District/South-West/The-Crooked-Hearth/My-Rented-Bedroom
- this allows for a more flexible and human readable system, and also allows for a more dynamic world, where locations can be created and destroyed on the fly.

# ECS (entity component system)
https://en.wikipedia.org/wiki/Entity_component_system

# Technology choices
- datahike
- openrouter
- Aero

## Rationale:
- datahike: a datalog database that can be used as the ECS backbone and when combined with the adapted RLM pattern, mitigates context bloat AND halluciantion, flexible schema is important for this system as it is highly dynamic and swaths of the world (around the player) are generated on the fly, and the system needs to be able to easily adapt to new types of data as they are generated.
- openrouter: a LLM API that can be used to generate the JIT content, and also to generate the AOT content when needed.
- Aero: a Clojure library for building data-driven applications, can be used by the player as more of a canvas than a spec - think of it with this analogy: imagine the whole application is a dynamical system, and the config as the initial conditions.
    - required: player name and game genre.
    - optional: EVERYTHING ELSE. The player can choose to specify as much or as little as they want, and the system will fill in the rest. This allows for a more personalized experience, and also allows for a more dynamic world, where the player can have a significant impact on the world around them.
    - Tied to one specific mechanic: The Aperture Trait System - this is a way for the player to dial in how overpowered or underpowered they want their character to be, by specifying many broadly applicable traits, their in-game options for any given situation will be expanded in trait specific ways, and the system will fill in the rest. This allows for a more personalized experience, and also allows for a more dynamic world, where the player can have a significant impact on the world around them.
        - Empty means no traits specified
        - N traits means N traits specified, and the system will fill in the rest.

        ### More on traits and world building:
        - traits are not just for the player character, but also for NPCs, locations, and items. This allows for a more dynamic world, where the player can have a significant impact on the world around them, and also allows for a more personalized experience, where the player can specify the traits of the world around them, and the system will fill in the rest.
        - NPCs have few and narrowly focused traits that aren't broadly applicable, i.e. a blacksmith might have traits related to their profession, but not traits related to combat or magic. This allows for a more dynamic world, where the player can have a significant impact on the world around them, and also allows for a more personalized experience, where the player can specify the traits of the world around them, and the system will fill in the rest. The way to think about this is - the character (who is not aware of their traits) might naturally find that they are good at something, statistically speaking, in aggregate characters professions or hobbies will likely loosely correlate to the nature of their traits, but this is not a hard and fast rule, and the player can choose to specify traits that don't necessarily correlate to their profession or hobbies, and the system will fill in the rest. This allows for a more dynamic world, where the player can have a significant impact on the world around them, and also allows for a more personalized experience, where the player can specify the traits of the world around them, and the system will fill in the rest.
        - If the player config doesn't specify any traits, the system will generate a set of balanced traits that seem compeeling based on the surrounding config context.
        - traits are taken into account in all interactions, and can have a significant impact on the outcome of any given situation. This allows for a more dynamic world, where the player can have a significant impact on the world around them, and also allows for a more personalized experience, where the player can specify the traits of the world around them, and the system will fill in the rest.

    # The fortune and folly mechanic (dice):
    two 20-sided die (one named fortune and another named folly) are cast for every action, the "max"die dictates the direction of the outcome, and the total / total-possible ratio dictates themagnitude of the outcome. This allows for a more dynamic world, where the player can have asignificant impact on the world around them, and also allows for a more dynamic experience.
    - This mechanic is completely invisible to the player, but is a core part of the system, An LLM isused to roll the dice, and also to interpret the results of the dice rolls in the context of theplayer's traits and the situation at hand, and then to generate a narrative description of theoutcome. This allows for a more dynamic world, where the player can have a significant impact onthe world around them, and also allows for a more personalized experience, where the player canspecify the traits of the world around them, and the system will fill in the rest. (simplifiedbecause the RLM pattern, subagents, and controlflow are not considered at the time of writing this,but the same principles apply, and the system can be easily adapted to incorporate those featureswhen they are implemented).

    # Random details (assume a config that is suggestive of a medieval fantasy setting):
    - the player has stolen high value items more that twice (discovered by some system that has scanned the DB for behavioral patterns) the system "decides" (suggesting LLM intelligence) that a reputation component should be added to the player character, and that the player's reputation should be "notorious", and that this should have an impact on how NPCs interact with the player, and also on the types of quests and opportunities that are available to the player. This allows for a more dynamic world, where the player can have a significant impact on the world around them, and also allows for a more personalized experience, where the player can specify the traits of the world around them, and the system will fill in the rest.
    - a key point to notice, scanning is happening (for enrichment and derivation purposes), and compaction should happen (if possible) in a way that doesn't reintroduce the problems that RLM solves. which means the "types" of compaction implemented must be very well designed and carefully considered. If in doubt... RAM is plentiful these days.
    - Aero config (even though it's EDN formatted) holds creative intent, not templates.
    - Datahike: the world is auditable, queries become an LLM superpower, the schema is flexible which makes the world flexible, relationships are first class citizens which allows for a more dynamic world, and also allows for a more personalized experience, where the player can specify the traits of the world around them, and the system will fill in the rest. (repeated interactions will cause an LLM to situationally upgrade or downgrade relationships between entities, and this will have a significant impact on the world around the player, and also allows for a more personalized experience.)
    - Simula, enables a rich and cohesive world and player experience.
    - LLM roles (and associated subagent finctionality) will need to be carefully designed such that the system as a whole is maximally leveraging RLM.
    - everything exists at some level of abstraction, at some particular time/location, yet nothing is ever truly static, or scripted a priori.

    # Cost Management ***Critical***:
    - do not simulate the world (in real time) outside of the player's perceptual range. instead, leverage the temporal nature of datahike and *infer* (using an LLM) the passage of time and its associated events **ONLY** when it may become relevant to the player.
    - derive NPC patterns (activities over time) AOT at the third lowest level of the location heirarchy (once the player arrives)- for example: if the world generator LLM settled on a location hierarchy of galax/planet/land-mass/macr-biome/micro-biome/settlement/Landmark then NPC patterns would be generated AOT at the micro-biome level, and then JIT at the settlement level.

    # Flexibility is king!
    Regardless of what RPG experience you want to have, the system should be able to accomodate it, and the player should be able to specify as much or as little as they want, and the system will fill in the rest.
