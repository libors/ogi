# CLAUDE.md

Ogi is a Robocode bot aimed at the TeamRumble. One robot class, `cz.libors.Ogi`, plays 1v1 and melee; the same code
packaged as `cz.libors.OgiTeam` plays in teams.

## Commands

- `./gradlew build`: compiles, runs unit tests and builds the robot and team jars into `bot/build/robots/`.
- `./gradlew skeletonCheck`: headless battles against the sample bots. It must stay green.
- Add `-PjavaVersion=21` (or 25) to compile and run battles on another JVM. The default is 17.

## Layout

- `bot/`: robot code. Java 17 API only (`--release 17`), no external dependencies.
- `harness/`: battle runner and checks on the Robocode control API.
- `docs/`: read the relevant document before changing that area.
  - Before touching the build, Gradle tasks, toolchains or the Robocode installation, read `docs/build-and-run.md`.
  - Before changing or adding harness code or checks, read `docs/harness.md`.
  - Before writing anything that depends on engine physics, timing or events (movement prediction, waves, bullets,
    radar, team messages), read `docs/engine-model.md`.

## Rules

- **Geometry uses the mathematical convention**: radians, 0 = east, counter-clockwise positive. Convert to and from
  Robocode angles only through `cz.libors.ogi.geom.Angles`.
- **Engine behaviour comes from the Robocode source and is proven by tests against the real engine**, never from
  memory or wiki pages alone. Record every engine surprise as a gotcha in `docs/engine-model.md`, with a test.
- **Score claims come from the harness** on a Robocode version that LiteRumble accepts, never from reasoning alone.
- Code, comments, docs and identifiers are in English.
