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
| `./gradlew build` | Compiles (the benches too), runs unit tests and packages `bot/build/robots/cz.libors.Ogi_<v>.jar`, `cz.libors.OgiTeam_<v>.jar`, `cz.libors.OgiGunLog_<v>.jar` and `cz.libors.OgiGunLogTeam_<v>.jar` |
| `./gradlew skeletonCheck` | Headless battles against the sample bots; see [harness.md](harness.md) |
| `./gradlew recordProbes` | Records probe battles turn by turn into `harness/build/recordings/` and checks integrity, coverage and determinism; see [harness.md](harness.md#recordings) |
| `./gradlew engineTest` | Runs `recordProbes`, then replays the recordings through the bot's engine model (`harness/src/engineTest`) |
| `./gradlew compare -Pbaseline=HEAD` | Paired, sequential comparison of the working tree with a baseline revision; see [harness.md](harness.md#comparing-two-versions) |
| `./gradlew recordBattles -Pversions=<v>,<v>` | Recordings of comparison battles; see [harness.md](harness.md#recording-comparison-battles) |
| `./gradlew recordGunLogs`, `gunBench`, `gunTrend -Plogs=<dir>` | The gun's logs and the gun bench; see [harness.md](harness.md#gun-bench) |
| `./gradlew meleeBench -Plogs=<dir>` | The melee bench; see [harness.md](harness.md#melee-bench) |
| `./gradlew dangerBench -Precordings=<dir>` | The danger bench; see [harness.md](harness.md#danger-bench) |
| `./gradlew positionBench -Precordings=<dir>` | The position bench; see [harness.md](harness.md#position-bench) |
| `./gradlew :harness:baselineJars -Pbaseline=<rev>` | Only builds the robot and team jars of a git revision into `.robocode/baselines/` |
| `./gradlew sandboxCheck` | Checks that robots run in Robocode's sandbox with a minimal environment; see [harness.md](harness.md#foreign-robots) |
| `./gradlew jarCheck -Pjars=<jar or directory>,...` | Checks foreign robot jars against the allowlist before they run |
| `./gradlew decompileJar -Pjar=<jar>` | Decompiles a robot jar for review into `harness/build/decompiled/` |
| `./gradlew skeletonCheck -PjavaVersion=21` | The same on another JVM (17, 21 and 25 are tested) |
| `./gradlew :harness:installRobocode` | Only downloads and unpacks Robocode |
| `./gradlew workerBundle` | Assembles the remote worker's bundle in `harness/build/worker-bundle/`; see [Remote worker](#remote-worker) |
| `./gradlew remoteSkeletonCheck` (and `remoteSandboxCheck`, `remoteRecordProbes`, `remoteCompare`, `remoteRecordBattles`, `remoteRecordGunLogs`) | The same on the Windows worker in a slot's environment; `-PremoteHost=ogi-desktop` for the WSL container |
| `./gradlew queueServer` | The queue's server and dashboard (`http://localhost:8765/`) until stopped; `-Pworkers=ogi-win:12` (default), `local:4`; see [harness.md](harness.md#queue) |
| `./gradlew submit -Pbaseline=HEAD` | Queues a comparison of the working tree (a snapshot, `snapshotJars`) with a baseline; the options of `compare`, plus `-Pnote` |
| `./gradlew submitGunLogs -Popponents=<set>` | Queues the gun's logs of the working tree against a set, for the gun bench, or against a melee or team set for the melee bench (team: add `-Pdebug=true`) |
| `./gradlew submitRecordings -Popponents=<set>` | Queues full recordings of the working tree against a set, for the danger bench; with `-PgunLog=true -Pdebug=true` against a team set, for the position bench |
| `./gradlew queueStatus`, `./gradlew queueCancel -Pjob=<id>` | The queue in a terminal; cancel a job |

For Java 12–23 the harness adds `-Djava.security.manager=allow`, as Robocode's own launch scripts do. Java 24+ refuses
that option, and Robocode has no sandbox there (engine-model G-43): the harness refuses foreign robots on such a JVM.

Battle JVMs get only `PATH`, `LANG`, `LC_ALL`, `LC_CTYPE` and `TZ` from the environment, because robots may read it;
on the Windows worker only `SystemRoot`, `TEMP` and `TMP`.

## Robocode installation

- **Location:** `.robocode/<version>/` in the repo root (git-ignored). The setup jar is cached in
  `.robocode/downloads/` and verified against `robocodeSetupSha256`.
- **Version:** `robocodeVersion` in `gradle.properties`. Results are only accepted from the versions LiteRumble
  allows (see "Allowed Robocode versions" at <https://rumble.robowiki.net/RumbleStats>; 1.10.3, 1.11.0 and 1.11.1 on
  2026-10-08).
- **Deployment:** `deployBots` deletes every `cz.libors.*` jar from `.robocode/<version>/robots/`, copies in the
  freshly built robot, team, gun-log and probe jars and the baseline jars from `.robocode/baselines/`, and deletes the
  robot database cache so Robocode rescans. It also deploys the approved foreign jars from `.robocode/foreign/`, after
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
| `cz.libors.OgiGunLog_<v>.jar` | the classes plus `cz.libors.OgiGunLog`, which logs the gun for the gun bench | none; the build fails if a rumble jar holds it |
| `cz.libors.OgiGunLogTeam_<v>.jar` | the same plus `cz/libors/OgiGunLogTeam.team` (five `cz.libors.OgiGunLog`), for the melee gun's logs of team battles | none |
| `cz.libors.probe_<v>.jar` (built by `probe/`) | the probe robots `cz.libors.probe.*` with their `.properties`, `cz/libors/probe/MessageTeam.team` (three `MessageProbe`) and `cz/libors/probe/SkipTeam.team` (a `SkipProbe` and a `MessageProbe`) | none, local recordings only |

The version and author come from `botVersion` and `botAuthor` in `gradle.properties`.

## Remote worker

The population of foreign robots runs on a separate machine; on the development machine only the few approved jars in
`.robocode/foreign/` run, after the jar check. Code reaches the worker as a **bundle**
built from the working tree (`./gradlew workerBundle`): the harness jar, our robot, team, probe and baseline jars,
the opponent sets, the `worker/` scripts and the Robocode setup jar. The worker never builds and holds no git
repository.

### Native Windows worker (default)

- A standard Windows account runs the harness with its own Java 17 (`C:\ogi\jdk`), whose network a firewall rule
  blocks; ssh with a key, host `ogi-win`. Loopback stays open, which ssh's port forwarding would need.
- The queue's executor (`queue.Executor`) is the worker: the server starts it over ssh and talks to it through the
  session's standard input and output, in ASCII, which the session's PowerShell (CP852 output) leaves alone. It ends
  at the end of its input or when the server falls silent, because Windows keeps a process running after its ssh
  session is gone. See [harness.md](harness.md#queue).
- The bundle is synced by comparing SHA-256 lists and copying the differences with scp (no rsync on Windows);
  the executor unpacks Robocode from the bundle's setup jar into `C:\ogi\robocode\<hash>` once.
- The `remote*` tasks run there by default (`queue.Remote`). They share the bundle, the CPU constant file and the
  measurement home with the queue's executor, so run them while the queue's worker is idle.
- `worker/windows/parallel-check.ps1` and `load-check.ps1` stay as measurement tools; `load-check.ps1` runs its
  robots with the ssh session's environment, not a minimal one.
- `worker/windows/fetch-jars.ps1` downloads foreign jars from a list into a directory on the worker; they still need
  the jar check and an `approved.txt` line before a slot uses them.
- On Windows, `java.io.File` turns a robot's `jar:file:/…!` URL into backslashes; `BattleRunner` converts them back.
- Windows Defender probably scans freshly copied jars: the first battle of a fresh slot took about 9 s, later ones
  1–2 s (lab set, 2026-10-09).

### Container worker in WSL2 (working for trusted robots, not the default)

- `worker/Dockerfile`: Temurin 17 and Robocode 1.11.1 (setup jar checked by SHA-256), base images pinned by digest,
  nothing of ours. `worker/run.sh` is the entry point: a fresh Robocode home on a tmpfs, the CPU constant kept in a
  state directory, the minimal environment for the battle JVM. It copies only the bundle's jars into the home, so
  foreign opponents do not run there; adding them needs the SHA-256 and jar check gate the Windows worker has.
- `worker/remote.sh` runs one harness main class from the Mac: rsync of the bundle, `docker build` if the Dockerfile
  or the setup jar changed, `docker run` with no network, a read-only root, no capabilities and a process limit,
  then the results back into `harness/build/remote/<name>/`.
- The `remote*` tasks wrap it with `-PremoteHost=ogi-desktop` (or `-PremoteKind=container`): `-PremoteCpuset` (pins
  the container to some cores; a `--cpus` quota pauses the container in bursts and robots skip turns),
  `-PremoteBattleCore` (pins the battle's application threads to one core).
- **Why it is not the default:** in the WSL2 VM a thread hand-off between cores, which the engine does twice per
  robot per turn, costs about 55 µs against about 4 µs natively (`worker/diagnostics/PingPong.java`; 12 µs with
  Hyper-V's core scheduler). Several battles at once then skip many turns and hit "Unable to stop thread". Docker
  Desktop's Linux containers run in the same kind of VM.

### Several battles at once

A robot skips a turn when it does not finish within the CPU constant, measured once on an idle machine, in wall
time. Under load every competing thread counts against it. Measured on 2026-10-09 (Ryzen 7 9800X3D, 8 cores, 16
logical, Windows, native):

- One battle JVM takes about 1.7 logical CPUs: about 1 for the battle and 0.6–0.8 for the JIT, because Robocode
  loads the robots' classes with a new class loader in every battle and the JIT compiles them again.
- With the default JVM options, heavy robots skip hundreds of turns per 30 battles at 8 battles at once. Concurrent
  collectors (ZGC, Shenandoah) make it worse; Serial GC, fewer compiler threads and C1 only make it better.
- **Recommended for parallel runs, and what the queue does:** battle JVMs with
  `-XX:+UseSerialGC -XX:TieredStopAtLevel=1`, 12 battles at once, and the CPU constant raised ×1.5 over the median of
  several idle measurements under the same options (Robocode reads it only from `config/robocode.properties`). A
  heavy robot then skips as many turns as alone on an idle machine. A raised constant costs no throughput, because
  the engine waits only until a robot finishes its turn.
- In short battles (the lab set, 1–2 s each) Ogi still skipped 15 turns in 60 battles at 12 at once (2026-10-09):
  Robocode loads the robots' classes anew every battle, so a short battle is mostly warm-up for the JIT.

### Determinism across machines

The same seed gives the same battle on an arm64 Mac (Homebrew 17.0.19) and on x86_64 (Temurin 17.0.20.1) up to the
last bit of some doubles: turns and events agreed in all deterministic probe recordings, but their digests differ.
Results from different machines agree statistically, not bit for bit. Gun logs from one machine still replay with
full parity on the other.

