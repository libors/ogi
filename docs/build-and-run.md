# Build and run

## Requirements

- **JDK 17 or newer to run Gradle** (Gradle 9 minimum). Use the wrapper, `./gradlew`.
- **Compilation and battles use a Java 17 toolchain** (`javaVersion` in `gradle.properties`), independent of the JDK
  that runs Gradle and of `JAVA_HOME`.
  - If no JDK 17 is installed where Gradle can detect it, the foojay resolver downloads Temurin 17 into
    `~/.gradle/jdks`.
  - Homebrew's `openjdk@17` is not detected automatically.

## Why Java 17

Rumble clients run various JVMs. A bot compiled for a newer Java than the client crashes there and loses its
battles.

- Saguaro 1.0 and Nullstride 2.3.3 ship Java 17 bytecode and score normally, so Java 17 is proven safe.
- The bytecode target does not limit performance; the client's JIT compiles it.
- The bot module also compiles with `--release 17`, so Java 18+ APIs are unavailable.

## Tasks

| Command | What it does |
|---|---|
| `./gradlew build` | Compiles, runs unit tests and packages `bot/build/robots/cz.libors.Ogi_<v>.jar` and `cz.libors.OgiTeam_<v>.jar` |
| `./gradlew skeletonCheck` | Headless battles against the sample bots; see [harness.md](harness.md) |
| `./gradlew recordProbes` | Records probe battles turn by turn into `harness/build/recordings/` and checks integrity, coverage and determinism; see [harness.md](harness.md#recordings) |
| `./gradlew engineTest` | Runs `recordProbes`, then replays the recordings through the bot's engine model (`harness/src/engineTest`) |
| `./gradlew compare -Pbaseline=HEAD` | Paired, sequential comparison of the working tree with a baseline revision; see [harness.md](harness.md#comparing-two-versions) |
| `./gradlew :harness:baselineJars -Pbaseline=<rev>` | Only builds the robot and team jars of a git revision into `.robocode/baselines/` |
| `./gradlew sandboxCheck` | Checks that robots run in Robocode's sandbox with a minimal environment; see [harness.md](harness.md#foreign-robots) |
| `./gradlew jarCheck -Pjars=<jar or directory>,...` | Checks foreign robot jars against the allowlist before they run |
| `./gradlew decompileJar -Pjar=<jar>` | Decompiles a robot jar for review into `harness/build/decompiled/` |
| `./gradlew skeletonCheck -PjavaVersion=21` | The same on another JVM (17, 21 and 25 are tested) |
| `./gradlew :harness:installRobocode` | Only downloads and unpacks Robocode |

For Java 12–23 the harness adds `-Djava.security.manager=allow`, as Robocode's own launch scripts do. Java 24+ refuses
that option, and Robocode has no sandbox there (engine-model G-43): the harness refuses foreign robots on such a JVM.

Battle JVMs get only `PATH`, `LANG`, `LC_ALL`, `LC_CTYPE` and `TZ` from the environment, because robots may read it.

## Robocode installation

- **Location:** `.robocode/<version>/` in the repo root (git-ignored). The setup jar is cached in
  `.robocode/downloads/` and verified against `robocodeSetupSha256`.
- **Version:** `robocodeVersion` in `gradle.properties`. Results are only accepted from the versions LiteRumble
  allows (see "Allowed Robocode versions" at <https://rumble.robowiki.net/RumbleStats>; 1.10.3, 1.11.0 and 1.11.1 on
  2026-10-08).
- **Deployment:** `deployBots` deletes every `cz.libors.*` jar from `.robocode/<version>/robots/`, copies in the
  freshly built robot, team and probe jars and the baseline jars from `.robocode/baselines/`, and deletes the robot
  database cache so Robocode rescans. It also deploys the approved foreign jars from `.robocode/foreign/`, after
  the jar check (`checkForeignJars`) and a check of their SHA-256, and removes any other foreign jar
  ([harness.md](harness.md#foreign-robots)). Baseline jars are built from git revisions (`baselineJars`) and kept there
  across reinstalls; delete the ones no longer needed by hand.
- **CPU constant:** the engine measures it on first start and stores it in `config/robocode.properties`. It sets
  the per-turn time limit.

To watch battles with the GUI:

```sh
cd .robocode/1.11.1 && ./robocode.sh
```

## Jar contents

| Jar | Contents | Rumble |
|---|---|---|
| `cz.libors.Ogi_<v>.jar` | classes, `cz/libors/Ogi.properties`, manifest `robots: cz.libors.Ogi` | 1v1 and melee |
| `cz.libors.OgiTeam_<v>.jar` | the same classes plus `cz/libors/OgiTeam.team` (five `cz.libors.Ogi` members, the first is the leader) | team |
| `cz.libors.probe_<v>.jar` (built by `probe/`) | the probe robots `cz.libors.probe.*` with their `.properties` | none, local recordings only |

The version and author come from `botVersion` and `botAuthor` in `gradle.properties`.
