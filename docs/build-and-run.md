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
| `./gradlew skeletonCheck -PjavaVersion=21` | The same on another JVM (17, 21 and 25 are tested) |
| `./gradlew :harness:installRobocode` | Only downloads and unpacks Robocode |

For Java 12–23 the harness adds `-Djava.security.manager=allow`, as Robocode's own launch scripts do. Java 24+ refuses
that option.

## Robocode installation

- **Location:** `.robocode/<version>/` in the repo root (git-ignored). The setup jar is cached in
  `.robocode/downloads/` and verified against `robocodeSetupSha256`.
- **Version:** `robocodeVersion` in `gradle.properties`. Results are only accepted from the versions LiteRumble
  allows (see "Allowed Robocode versions" at <https://rumble.robowiki.net/RumbleStats>; 1.10.3, 1.11.0 and 1.11.1 on
  2026-10-08).
- **Deployment:** `deployBots` copies the freshly built jars into `.robocode/<version>/robots/` and deletes the robot
  database cache so Robocode rescans.
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

The version and author come from `botVersion` and `botAuthor` in `gradle.properties`.
