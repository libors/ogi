# Ogi

A team-first [Robocode](https://robocode.sourceforge.io/) bot. One robot class, `cz.libors.Ogi`, plays 1v1 and melee.
The same code packaged as `cz.libors.OgiTeam` plays in the [TeamRumble](https://rumble.robowiki.net/).

## Layout

| Path | Content |
|---|---|
| `bot/` | Robot code. It is compiled for Java 17 (`--release 17`), the oldest Java the rumble clients run. |
| `harness/` | Headless battle runner on top of the Robocode control API: checks, the turn-by-turn recorder, comparisons, the queue and the offline benches |
| `probe/` | Probe robots that drive the engine with random commands. Their recordings are the ground truth for the bot's engine model (`recordProbes`, `engineTest`); never entered in a rumble |
| `tools/` | Interactive pages of the melee and team movement (`tools/movement-explorer/`) |
| `worker/` | What runs on a remote battle worker: the container, the Windows scripts and diagnostics |
| `docs/` | Build and run, harness, engine model and gotchas, fire detection, the bot |
| `.robocode/` | Official Robocode installation used by the harness (downloaded, git-ignored) |

## Building and checking

Gradle runs on JDK 17 or newer. Compilation and battles use a Java 17 toolchain, matching the oldest rumble clients;
if no JDK 17 is installed, Gradle downloads one. To run the battles on another JVM, use e.g.
`./gradlew skeletonCheck -PjavaVersion=21`.

```sh
./gradlew build          # compile, unit tests, robot and team jars in bot/build/robots/
./gradlew skeletonCheck  # headless battles against the sample bots
./gradlew recordProbes   # turn-by-turn recordings of probe battles, the ground truth for the physics model
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

## Credits and licence

The ideas Ogi takes from other robots are credited in [CREDITS.md](CREDITS.md). The robot (`bot/`) is released
under the [RWPCL](https://robowiki.net/wiki/RWPCL) 1.1, the rest of the repository under the zlib licence; see
[LICENSE](LICENSE).
