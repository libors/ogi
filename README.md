# Ogi

A team-first [Robocode](https://robocode.sourceforge.io/) bot. One robot class, `cz.libors.Ogi`, plays 1v1 and melee.
The same code packaged as `cz.libors.OgiTeam` plays in the [TeamRumble](https://rumble.robowiki.net/).

## About

I played with Robocode years ago, and this project returns to it with modern tools. The aim is a team that clearly
excels in the TeamRumble, built from new ideas rather than by retuning an existing bot. The 1v1 setting serves as a
lab for getting physics, waves, targeting and movement right before more robots join the game.

Principles:
- **Strategies are pluggable and configured as data.** 1v1, melee and team modes are combinations of components, not
  rewrites.
- **The physics model is verified against the real engine.** The in-bot simulator is tested against recordings from
  the actual Robocode engine, never against assumptions about it.
- **Decisions are explainable.** Every decision leaves a trace: the candidates considered and how each was scored.
  A toggleable overlay and a replay viewer will show why the bots do what they do.
- **Every change is measured.** A headless harness with statistically sound comparisons decides what stays.

Status: walking skeleton. Radar lock or sweep, circular targeting, orbiting movement and scans shared between
teammates, plus a headless harness that checks the bot against the Robocode sample bots.

## Layout

| Path | Content |
|---|---|
| `bot/` | Robot code. It is compiled for Java 17 (`--release 17`), the oldest Java the rumble clients run. |
| `harness/` | Headless battle runner on top of the Robocode control API, plus the skeleton check |
| `docs/` | Build and run, harness, engine model and gotchas |
| `.robocode/` | Official Robocode installation used by the harness (downloaded, git-ignored) |

## Building and checking

Gradle runs on JDK 17 or newer. Compilation and battles use a Java 17 toolchain, matching the oldest rumble clients;
if no JDK 17 is installed, Gradle downloads one. To run the battles on another JVM, use e.g.
`./gradlew skeletonCheck -PjavaVersion=21`.

```sh
./gradlew build          # compile, unit tests, robot and team jars in bot/build/robots/
./gradlew skeletonCheck  # headless battles against the sample bots
```

`skeletonCheck` does the following:
1. Downloads the official `robocode-1.11.1-setup.jar` and verifies its SHA-256.
2. Unpacks it into `.robocode/` and deploys the freshly built jars.
3. Runs 1v1 battles against each sample bot, a 10-bot melee and a team battle against `sampleteam.MyFirstTeam`.

The check fails if the bot loses, skips a turn or prints an exception. Details, including the score components, go
to `harness/build/skeleton-check.json`.

## Conventions

- **Angles:** internal geometry uses the mathematical convention: radians, 0 = east, counter-clockwise positive.
  Conversion to Robocode's convention (0 = north, clockwise) happens only at the API boundary, in
  `cz.libors.ogi.geom.Angles`.
- **Robocode versions:** results are only accepted from the versions LiteRumble allows, currently 1.10.3–1.11.1.
