# CLAUDE.md

Ogi is a Robocode bot aimed at the TeamRumble. One robot class, `cz.libors.Ogi`, plays 1v1 and melee; the same code
packaged as `cz.libors.OgiTeam` plays in teams.

## Commands

- `./gradlew build`: compiles, runs unit tests and builds the robot and team jars into `bot/build/robots/`.
- `./gradlew skeletonCheck`: headless battles against the sample bots. It must stay green.
- `./gradlew recordProbes`: turn-by-turn recordings of probe battles plus integrity and determinism checks. It must
  stay green.
- `./gradlew engineTest`: replays the recordings through the bot's engine model (`cz.libors.ogi.physics`) and
  requires bit-identical results. It must stay green.
- `./gradlew compare -Pbaseline=HEAD`: paired, sequential comparison of the working tree with a baseline revision
  against an opponent set (`docs/harness.md`).
- Add `-PjavaVersion=21` (or 25) to compile and run battles on another JVM. The default is 17.

## Layout

- `bot/`: robot code. Java 17 API only (`--release 17`), no external dependencies.
- `harness/`: battle runner, checks and the turn recorder on the Robocode control API.
- `probe/`: probe robots for engine recordings; deployed locally only, never to a rumble.
- `docs/`: read the relevant document before changing that area.
  - Before touching the build, Gradle tasks, toolchains or the Robocode installation, read `docs/build-and-run.md`.
  - Before changing or adding harness code or checks, read `docs/harness.md`.
  - Before writing anything that depends on engine physics, timing or events (movement prediction, waves, bullets,
    radar, team messages), read `docs/engine-model.md`.
  - Before changing enemy fire detection, read `docs/fire-detection.md`.
  - Before changing the gun, the movement, waves or the debug overlay, read `docs/bot.md`.

## Rules

- **Geometry uses the mathematical convention**: radians, 0 = east, counter-clockwise positive. Convert to and from
  Robocode angles only through `cz.libors.ogi.geom.Angles`. The one exception is `cz.libors.ogi.physics`, which
  repeats the engine's arithmetic in the engine's convention to match it bit for bit; convert at its boundary.
- **Randomness:** the bot draws only from its own `java.util.Random` with an explicit seed, never from
  `Utils.getRandom()` (the engine's generator) or `Math.random()`; `RandomnessRulesTest` enforces it. Otherwise
  seeded battles stop replaying (engine-model G-26, G-33, G-34).
- **Engine behaviour comes from the Robocode source and is proven by tests against the real engine**, never from
  memory or wiki pages alone. Record every engine surprise as a gotcha in `docs/engine-model.md`, with a test.
- **Score claims come from the harness** on a Robocode version that LiteRumble accepts, never from reasoning alone.
- Code, comments, docs and identifiers are in English.
