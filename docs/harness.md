# Harness

The harness runs battles headless on the stock Robocode engine through its control API and reports structured
results. Every score claim about the bot must come from here.

## Components (`harness/src/main/java/cz/libors/harness/`)

| Class | Role |
|---|---|
| `BattleSetup` | A battle: label, field size, rounds, contestants (`sample.Walls`, `cz.libors.Ogi 0.1`, teams by name), seed, optional fixed starts |
| `BattleRunner` | Owns one `RobocodeEngine`; resolves contestants from the local repository, seeds the battle and runs it synchronously, optionally with extra listeners |
| `BattleOutcome` | Results sorted by score, the seed used, skipped turns per robot, problems (engine errors, exceptions in robot output), robot rounds that missed their fixed start, bullets fired and hits per contestant, elapsed time |
| `Starts` | Fixed starts drawn from a seed, and their engine string |
| `ContestantResult` | Score components per contestant: total, survival, last survivor bonus, bullet damage and bonus, ram damage and bonus, firsts |
| `SkeletonCheck` | Runs a fixed set of checks and writes `harness/build/skeleton-check.json` |
| `ProbeRecordings` | Records probe battles and checks their integrity, coverage and determinism; see [Recordings](#recordings) |
| `recording.TurnRecorder` | Battle listener that writes a recording: every robot and bullet in every turn, plus debug properties |
| `recording.Recording` | Reads a recording back into memory; no Robocode classes, so tests can use it without an engine |
| `Json` | Minimal JSON writer and reader for records, lists and maps; one-line ASCII form for the queue's protocol |
| `compare.Compare` | Paired, sequential comparison of two bot versions; see [Comparing two versions](#comparing-two-versions) |
| `compare.Sprt`, `compare.Stratum` | The stratified sequential test and the per-opponent paired statistics |
| `compare.OpponentSet`, `compare.CompareLog` | The opponent set file, and the battle log that makes a comparison resumable |
| `compare.RecordBattles` | Records the battles of a comparison turn by turn; see [Recording comparison battles](#recording-comparison-battles) |
| `gunbench.GunBench`, `gunbench.GunBattle`, `gunbench.GunReplay` | The offline gun bench (source set `gunBench`): replays the gun's logs with other gun parameters; see [Gun bench](#gun-bench) |
| `Sandbox` | Which robots are foreign, and whether Robocode's sandbox is active; `BattleRunner` refuses foreign robots without it. See [Foreign robots](#foreign-robots) |
| `SandboxCheck` | Proves that the battle JVM sandboxes robots and passes them a minimal environment |
| `foreign.JarCheck`, `foreign.ApiRules`, `foreign.ClassFile` | Checks a robot jar against an allowlist of the JDK and Robocode APIs before it runs |
| `queue.Server`, `queue.Dashboard` | The queue's server on this machine: jobs, workers, the web page; see [Queue](#queue) |
| `queue.Executor`, `queue.Slot` | The worker side: slots, each a JVM with a Robocode home of its own, driven over standard input and output |
| `queue.Submit`, `queue.QueueClient`, `queue.Remote` | Queue a job; the queue from a terminal; run one main class on a Windows worker |
| `queue.QueueJob`, `compare.CompareJob` | A job in the queue; a comparison run as one, with pairs on many slots at once |
| `queue.Protocol`, `queue.BattleSpec`, `queue.Ssh`, `queue.WorkerConfig` | Line protocol, a battle and its outcome on the wire, ssh and scp to Windows, where things are on a worker |

Differential tests of the bot's engine model live in the `engineTest` source set (`harness/src/engineTest/`) and run
with `./gradlew engineTest`, after `recordProbes`. They read the recordings through `Recording` and need no engine.
The same source set measures enemy fire detection (`FireDetectionTest`, see [fire-detection.md](fire-detection.md)).
The gun bench has a source set of its own, `gunBench`. Both use the bot's classes, which the main source set must
not have: a battle JVM would then load our robots from its class path instead of their jars.

The probe robots live in their own module, `probe/` (package `cz.libors.probe`). They are packaged as
`cz.libors.probe_<v>.jar` and deployed only into the local Robocode installation, never to a rumble.

## How results are gathered

- **Score components** come from `BattleCompletedEvent.getSortedResults()`.
- **Skipped turns** are counted from each robot's per-turn console output, where the engine writes
  `SYSTEM: <name> skipped turn <turn>` (`RobotPeer`). Only that line counts: some robots print their own statistics,
  e.g. "skipped turns: 0" once a round, which an earlier substring match counted as one skipped turn per round
  (Hydra, Horizon, deBroglie, LifelongObsession; found 2026-10-09 because they still "skipped" 35 turns per battle in
  debug mode).
- **Problems:** output lines containing `Exception` or `Error` are recorded with round and tick; so are
  `BattleErrorEvent`s.
- **Shots:** every bullet in the turn snapshots counts once as fired by its owner's contestant, and once as a hit when
  it hits a robot of another contestant (friendly fire does not count). Bullet id 0 is no shot: the engine uses it
  for a dying robot's explosion and for the energy a dying leader takes from its teammates.
  - Only bullets that end while the round is open count. In the turn in which one contestant is left, the bullets
    still in the air are taken back, and bullets fired later are ignored. They can no longer hit an enemy, and
    without this rule a contestant that fights from further away would show a lower hit rate with the same aim,
    because more of its bullets are in flight at the kill (5–16 % of Ogi's bullets against the lab set).
  - Hit rates are therefore "hits per bullet that could still hit". A stationary target that is hit every time
    shows 100 %.
- **Own turn times (P-04):** our robots report the wall time of their previous turn in the debug property `cpu`
  ([bot.md](bot.md#debug-overlay-czliborsogitrace)). `BattleOutcome.turnTimes` keeps per robot the number of turns
  reported, the longest, and how many took longer than half the CPU constant of an idle machine and than all of it:
  the home's constant locally, the executor's measured idle constant on a queue worker, whose slots run with a raised
  one. The engine would skip a turn only later than the constant (engine-model G-45), so these counts see a robot
  growing heavy before skipped turns do.
  - On a loaded worker the wall time includes the other battles' load and the garbage collections of the slot's JVM,
    which the opponent's allocations trigger too. With 12 battles at once on the desktop (2026-10-09) Ogi's longest
    turns were 23 ms (lab set) and 70 ms (against BeepBoop and Diamond), and 10–37 turns per job went over the idle
    constant. So the gate for Ogi's own weight is the skeleton check on an idle machine; the queue shows the counts
    as health.
- **Matching:** a contestant is matched by name prefix. Team results are named after the team plus an index, e.g.
  `cz.libors.OgiTeam 0.1 (1)`.

## Seeds and determinism

- **Every battle is seeded.** `BattleRunner` calls `RandomFactory.resetDeterministic(seed)` before each battle,
  with the setup's seed or a fresh one. `BattleOutcome.seed()` reports it, so any battle can be rerun.
- **What the seed controls:** the single random generator shared by the harness, the engine (start positions, the
  order in which robots and bullets are processed) and robots that call `Utils.getRandom()` (engine-model G-33).
- **What it does not control:** `Math.random()` and robots' own unseeded generators (G-26). A battle with such a
  robot does not replay. Most foreign bots are such robots.
- **A robot's draws from `Utils.getRandom()` shift the engine's later draws**, e.g. the next round's start
  positions, and a draw in its last turn after death races with the engine (G-34), so the battle stops replaying.
- **Our robots** therefore draw only from their own `java.util.Random`, seeded from the round and the start
  position. `RandomnessRulesTest` in `bot/` fails on `Math.random()`, `Utils.getRandom()`, unseeded `new Random()`
  and similar sources in the bot's code; `recordProbes` checks that a seeded Ogi battle replays.
- **Pairing:** two bot versions run on one seed share at most the first round's start positions; their battles
  diverge as soon as their behaviour differs. Fixed starts (`BattleSetup.starts`) give both versions the same start
  in every round, except when a robot stands in the way (engine-model G-41).
- **Tested:** `recordProbes` replays seeded battles and compares digests (see below). Recordings were bit-identical
  on Java 17, 21 and 25 on the same Mac.

## Recordings

`./gradlew recordProbes` runs battles of probe robots, writes a recording of each one to `harness/build/recordings/`
and checks them. It is the ground truth for the engine-model tests; recordings are regenerated, not committed.

It runs the engine in debug mode (`-Ddebug=true`): the engine then waits for every live robot without a time limit
(engine-model G-17), so no robot can skip a turn and the recordings do not depend on machine load. Debug mode changes
no rule of the game; the digests are identical with and without it.

### Probe robots

| Robot | Source of randomness | Purpose |
|---|---|---|
| `RandomProbe` | own `Random`, seeded from the round and the start position | random commands for the recordings |
| `PassiveProbe` | like `RandomProbe`, but never fires | battles that reach the inactivity penalty |
| `WatchingProbe` | like `RandomProbe`, with a bot's radar: locked in 1v1, spinning in melee | realistic scans for fire detection |
| `MathRandomProbe` | `Math.random()` | shows that a seed does not control `Math.random()` |
| `UtilsRandomProbe` | `Utils.getRandom()`, none once dead (engine-model G-34) | shows that a seed controls `Utils.getRandom()` |
| `UtilsDrawProbe` | like `RandomProbe`, plus one discarded `Utils.getRandom()` draw per number used | shows that robots share the engine's generator |
| `SandboxProbe` | none | tries what the sandbox must block; used by `sandboxCheck`, not recorded ([Foreign robots](#foreign-robots)) |

- **Commands:** every turn each command channel gets a new random command with a per-round probability: ahead,
  body, gun and radar turns, max velocity, max turn rate, the three adjust flags, fire.
- **Edge cases on purpose:** reversals against the current velocity, short moves, zero and infinite distances and
  turns, body turns ending within 2e-5 rad of a multiple of 90°, out-of-range velocity, turn-rate and power values.
- **Reporting:** a probe reports through `setDebugProperty`, which reaches the recorder in the turn snapshot.
  - `rem`: time, then distance, body, gun and radar turn remaining as the engine left them after this turn's move.
  - `cmd`: time, then the commands sent for the next turn: distance, body, gun and radar turn remaining, max
    velocity, max turn rate (radians), the adjust flags gun-for-robot, radar-for-gun and radar-for-robot, the fire
    power requested (`-` if none) and whether the robot API accepted it.
  - `ev`: one per event received (scans, hits, collisions, misses, deaths, skipped turns): type, event time, fields.
  - Values are comma-separated; numbers are printed by `Double.toString`; angles are in radians, Robocode's
    convention.

### Format

Gzip-compressed UTF-8 text, one record per line, fields separated by tabs. Numbers parse back to the exact value.
Angles are in radians and in Robocode's convention (0 = north, clockwise), as the engine reports them.

```
#ogi-recording  1
battle  label=…  seed=…  width=…  height=…  rounds=…  gunCoolingRate=…  inactivityTime=…  hideEnemyNames=…
robot   index  name  teamIndex  teamName  droid
r       round  turn  robot  state  x  y  bodyHeading  velocity  energy  gunHeat  gunHeading  radarHeading
b       round  turn  owner  bulletId  state  power  x  y  heading  victim
d       round  turn  robot  key  value
end     round  turns
```

- **Turn 0** is the state at round start.
- **A record of turn t** is the state after the engine's update of turn t. The debug properties of turn t are those
  the robot set while acting in turn t; its commands take effect in turn t + 1.
- **Left out:** explosions, and debug properties of dead robots. The engine does not wait for a dead robot's last
  turn, so its debug properties race with the snapshot (engine-model G-34).
- **Robot and bullet states** are the names of Robocode's `RobotState` and `BulletState`; `victim` is a robot index
  or -1.

### Checks

| Check | Pass condition |
|---|---|
| Integrity, per recording | every live probe has a `cmd` of the current turn while two or more robots are alive; no skipped turns or problems |
| Different seeds | three seeds give three different digests, per scenario |
| Replay | the first seed of each scenario, run again, gives the same digest (SHA-256 of the uncompressed text, without the debug properties that measure time: Ogi's `cpu`) |
| `Utils.getRandom()` | two runs of `UtilsRandomProbe` with one seed are identical |
| `Math.random()` | two runs of `MathRandomProbe` with one seed differ |
| Ogi | two runs of Ogi against `RandomProbe` with one seed are identical |
| Shared generator | `RandomProbe` and `UtilsDrawProbe` with one seed start round 1 at the same positions and round 2 elsewhere |
| Fixed starts | 8 probes on 400×400 with a start each, one on top of another and one with an exponent in its heading: every robot round starts exactly there unless a robot is in the way (engine-model G-41), and each case occurs |
| Own jar | the repository lists `cz.libors.Ogi <v>` from the robot jar and the team jar; `BattleRunner` picks the robot jar (G-42) |

Scenarios, each with seeds 1, 2 and 3: `duel` (2 probes, 800×600, 3 rounds), `crowd` (6 probes, 400×400, 3 rounds,
many collisions), `melee` (10 probes, 1000×1000, 2 rounds), `inactive` (2 passive probes, 800×600, 1 round),
`watch-duel` (2 watching probes, 800×600, 3 rounds) and `watch-melee` (8 watching probes, 1000×1000, 2 rounds). The task also prints how often the edge cases
occurred (wall hits, robot collisions, velocity sign flips, stops, bullet outcomes, deaths, disabled turns, events)
and writes everything to `harness/build/recordings/summary.json`.

## Skeleton check

| Battles | Field | Rounds | Pass condition |
|---|---|---|---|
| 1v1 against each of 13 sample bots | 800×600 | 10 | score share ≥ 75 %, rank 1 |
| Melee against 9 sample bots | 1000×1000 | 10 | rank 1 |
| `cz.libors.OgiTeam` vs `sampleteam.MyFirstTeam` | 1200×1200 | 10 | score share ≥ 70 % |

All checks also require zero skipped turns and no problems, and in the battle against `sample.SittingDuck`, which
never fires, no bullet counted for it. Of our robots' own turns (P-04) at most 0.5 % may take longer than half the
CPU constant and at most 0.05 % longer than all of it; Ogi has 0–5 over half per battle (2026-10-09), once a single
turn of 2.1 ms against a 2.0 ms constant (a garbage collection or the JIT), and an Ogi that spends 1.2 ms per turn
fails every battle without a single skipped turn. The process exits non-zero on any failure.

## Comparing two versions

`./gradlew compare -Pbaseline=<rev>` measures whether a candidate version of the bot scores better than a baseline
against a fixed opponent set. By default the candidate is the working tree (version `botVersion`), so the usual run
compares uncommitted changes with `HEAD`:

```sh
./gradlew compare -Pbaseline=HEAD
./gradlew compare -Pbaseline=HEAD -Pbounds=nonregression -Pstarts=fixed
./gradlew compare -Pbaseline=7df5ba7 -Pcandidate=main -Popponents=harness/opponents/sample-team.txt
./gradlew compare -Pbaseline=HEAD -Popponents=harness/opponents/lab-1v1.txt -Pmetric=hits
```

### Versions

- **`-Pbaseline` and `-Pcandidate`** take a git revision or a bot version. `baselineJars` builds the robot and
  team jars of a revision from `git archive` with its short hash as the version (e.g. `cz.libors.Ogi 7df5ba7`) into
  `.robocode/baselines/`, unless they are there already. A value that is not a revision is the version of a jar in
  that directory. `deployBots` deploys these jars next to the current ones.
- **Several versions in one installation:** robots are keyed by name and version. A team loads its members from its
  own jar, so team versions do not mix (engine-model G-42).
- **Identity:** the comparison records the SHA-256 of both jars. The jars are reproducible, so the same code gives
  the same hash.

### Options

| Property | Default | Meaning |
|---|---|---|
| `opponents` | `harness/opponents/sample-1v1.txt` | opponent set file, see below |
| `bounds` | `gainer` | hypotheses in score-share points: `gainer` [0, 0.5], `screening` [0, 1.5], `nonregression` [−0.75, 0.25], or `δ0,δ1` |
| `maxPairs` | 1000 | stop undecided after this many pairs |
| `pairsPerOpponent` | 0 | fixed mode: this many pairs per opponent and no stopping rule, for measuring noise |
| `starts` | `random` | `fixed`: both battles of a pair start every round at the same positions, drawn from the pair's seed |
| `seed` | 1 | run seed; a pair's seed depends on it, the opponent and the pair's index |
| `rounds` | from the format | rounds per battle |
| `metric` | `score` | what the test runs on: `score` (score share), `hits` (the subject's hits per bullet, %; see [Shots](#how-results-are-gathered)), `dodge` (100 minus the opponent's hit rate on the subject, %; 100 against a robot that never fires) |
| `out` | `harness/build/compare` | root of the output directories |

### Opponent sets

A text file per set in `harness/opponents/`: a `format` line (`1v1`: 800×600, 35 rounds; `team`: 1200×1200, 10
rounds), then one robot or team per line as Robocode lists it, including its version. Opponents under `[strong]` form
the second scoreboard; `[other]` starts the rest. Only trusted robots are in the repository: `sample-1v1.txt` and
`sample-team.txt` (the sample bots), and `lab-1v1.txt` (the sample bots plus the random-moving probes, whose version
follows `botVersion`), for gun and movement work. Sets of rumble bots belong next to their jars on the machine that
runs them.

### Method

- **Pairs:** both versions fight the same opponent with the same seed (and the same starts with `starts=fixed`); the
  pair's difference is the candidate's score share minus the baseline's, in points (0–100). Which version goes first
  alternates.
- **Estimate:** every opponent weighs the same, as in the rumble's APS. The difference is the mean of the per-opponent
  mean differences, with variance `(1/H²)·Σ s_h²/n_h`. While an opponent has fewer than 5 pairs, its variance is the
  one pooled over all opponents.
- **Allocation:** 2 pairs per opponent first; then the next pair goes to the opponent furthest below its Neyman
  share (`n_h ∝ s_h`), so opponents where the versions differ little get few pairs.
- **Stopping rule:** fishtest's normal approximation of the generalized SPRT, `LLR = (δ1 − δ0)(2Δ̂ − δ0 − δ1)/(2V)`,
  with α = β = 0.05: H1 at LLR ≥ 2.94, H0 at LLR ≤ −2.94. No decision before the burn-in and before the pooled
  variance has 20 degrees of freedom; with fewer, a variance underestimated by chance ends the test after a few
  pairs. Unit tests simulate the test at both hypotheses: error rates and lengths match the theory.
- **Scoreboards:** the test measure of both versions over all opponents and over the strong ones, with the
  difference and its 95 % interval; beside it APS (mean score share), PWIN (share of battles won), the version's hit
  rate and the opponents' hit rate on it, each opponent weighing the same. Hit rates pool the bullets of all pairs.
- **Fixed starts** make every round of a battle start alike, which the rumble does not do. Against the sample bots
  (2026-10-08) they raised the per-battle SD and the correlation of a pair's battles alike, and the noise of the
  difference stayed the same, so random starts are the default.

### Output

`<out>/<set>/<baseline>-<jar hash>_vs_<candidate>-<jar hash>_<starts>_s<seed>/`:

- `battles.tsv`: one line per battle, appended as it finishes: opponent, pair, side, seed, score share, the
  subject's score components, both sides' firsts, bullets fired and hits, skipped turns, problems, start mismatches,
  time. Running the same comparison again resumes from it, with any `metric`; a pair with only one battle logged is
  left out. The first lines hold the format version and the configuration; a log of another version or configuration
  is refused.
- `summary.json`: the configuration, environment (Robocode and Java version, CPU constant), the decision, both
  scoreboards, per-opponent statistics (means, standard deviations, the correlation of a pair's two battles), noise
  (σ per pair, expected pairs to a decision) and health (skipped turns per side, problems, start mismatches).
- `problems.log`: exceptions and engine errors, if any.

The run prints one line per pair and the tables at the end. It does not fail on a decision: read the result line.

### Recording comparison battles

`./gradlew recordBattles -Pversions=<v>,<v>` records each version against each opponent of a set on the pair seeds
that `compare` uses with the same run seed, so a pair from a comparison log can be replayed and examined turn by
turn. A version is a git revision (built by `baselineJars`), a jar in `.robocode/baselines/` or the working tree's
`botVersion`. Like `recordProbes`, it runs the engine in debug mode.

```sh
./gradlew recordBattles -Pversions=HEAD,0.1 -Ponly=sample.SpinBot,sample.TrackFire -Ppairs=3
```

| Property | Default | Meaning |
|---|---|---|
| `versions` | required | comma-separated versions of `cz.libors.Ogi` |
| `opponents` | `harness/opponents/lab-1v1.txt` | opponent set file; gives the format |
| `only` | all | comma-separated opponents of the set to record, named as the set lists them; an unknown name is an error |
| `pairs` | 1 | pairs per opponent, from pair 0 |
| `seed`, `rounds`, `starts` | as in `compare` | |
| `out` | `harness/build/battle-recordings` | writes `<out>/<version>/<opponent>_p<pair>.rec.gz` |

The recordings have the format above. Ogi sets the debug property `gun` every turn it has a target, with the plan
for the next turn's shot (see [bot.md](bot.md#debug-overlay-czliborsogitrace)), so a bullet first recorded in turn
f was fired from the plan of turn f − 2, whose line in turn f − 1 says it was fired.

Each version's directory also gets `battles.tsv`, one line per battle recorded: the recording's file name, the
opponent, the pair, the seed, the robot's bullets fired and hits as [Shots](#how-results-are-gathered) counts them,
its skipped turns and problems. A battle recorded again appends a new line; the last one for a file describes it.

## Gun bench

The gun bench replays the gun's decisions of recorded battles with other gun parameters (feature weights, the
neighbour count, the virtual weight, the tries before switching guns), in seconds instead of battles. It runs the
bot's own `GunModel` on what the bot logged, so with the bot's parameters it decides exactly as the bot did.

```sh
./gradlew recordGunLogs -Pversions=0.1 -Ppairs=2      # the working tree's version, lab set by default
./gradlew gunBench                                      # replays harness/build/gun-logs/<botVersion>
./gradlew gunBench -Pweights=3,3,1.5,2.5,2,1.5,3,1,1.5,2 -PneighbourScale=0.7
```

- **`recordGunLogs`** is `recordBattles` (same options, same seeds) for `cz.libors.OgiGunLog`, which plays like Ogi
  and logs its gun ([bot.md](bot.md#debug-overlay-czliborsogitrace)). Against foreign robots, queue them on the
  desktop with `submitGunLogs` ([Recordings as a job](#recordings-as-a-job-comparerecordjob)). Its recordings keep
  only the debug properties `gunshot`, `gunpass` and `gunshield`, about 0.9–1.7 MB per 35-round battle, in
  `harness/build/gun-logs/<version>/`.
  Only the working tree has an `OgiGunLog` jar.
- **`gunBench`** reads every `*.rec.gz` of `-Plogs=<dir>,...` and replays each battle with a fresh model per target:
  in each turn the waves that passed teach the model, then the turn's plan asks it. The movement, the fire timing
  and the bullet power stay as recorded.
- **Parameters:** `weights` (10, in the order of `TargetingFeatures`), `neighbourScale`, `maxNeighbours`,
  `virtualWeight`, `minTries`; the rest come from `GunParameters.DEFAULT`.

| Column | Meaning |
|---|---|
| `shots` | plans replayed |
| `gf tries`, `gf hit %` | passed waves whose plan had a guess factor, and how many it would have hit |
| `gf real %` | the same over waves that carried a bullet |
| `fired %` | waves with a bullet, aimed by the gun the replay chose; bullets an enemy bullet destroyed are left out |
| `shielded` | waves whose bullet an enemy bullet destroyed (`gunshield`): the aim did not decide them |
| `harness %`, `bullets/harness` | the harness's hit rate of the same battles, and both bullet counts |
| `differ` | plans whose guess factor or gun differ from the log |

- **Parity:** with the bot's parameters every replayed decision must equal the logged one, bit for bit; the task
  fails otherwise. `GunLogTest` checks the same on a simulated fight.
- **Bullets shot down:** some robots shoot our bullets down with theirs (bullet shielding). The bench scores a wave
  by the target's position, so before `gunshield` such bullets counted as hits: against DrussGT, Saguaro and
  Roborio the bench showed 23–37 % fired against the harness's 6–12 % (2026-10-09). Left out, the bench shows 7.6,
  13.3 and 12.0 %. The harness counts them as misses, so its rate stays a little lower against these robots.
- `./gradlew gunTrend -Plogs=<dir>` compares the fired hit rate in the first 10 rounds of each battle with the last 10
  (`-Prounds`), per opponent, sorted by the change over its standard error.
- **`fired %` against `harness %`:** checked bullet by bullet on four lab battles (2026-10-09). Every bullet whose
  wave passed the target got the verdict the engine gave it, except bullets destroyed by an enemy bullet, which the
  bench now leaves out (`shielded`). The two counts differ only in which bullets they include:
  - the bullet that kills the target counts for the harness, but its wave never passes (a dead target is not
    scanned), so the bench and the bot never see it: one hit per round won;
  - a bullet that has passed the target but is still flying when the round is decided counts for the bench, not for
    the harness.

  Against the lab set the bench's `fired %` is therefore 1–4 points lower.
- **Limits:**
  - Against a target that reacts to our aim the replay is optimistic: the recorded movement dodged the logged
    gun, not the replayed one.
  - A replayed aim the turret could not have reached in time would not have been fired that turn; the replay fires
    it anyway.

## Foreign robots

A foreign robot is any robot or team that is neither ours (`cz.libors.*`) nor one of Robocode's sample robots
(`sample.*`, `sampleex.*`, `samplesentry.*`, `sampleteam.*`). Foreign jars are checked before they run, run only
in Robocode's sandbox, and see only a minimal environment. Trust goes by package, so the jar check denies a foreign
jar with anything in those packages, and `deployBots` runs the jar check.

- **Sandbox:** Robocode's sandbox needs a JVM that still has a Security Manager: Java 17, or 18–23 with
  `-Djava.security.manager=allow`, which the harness adds (engine-model G-43). `BattleRunner` refuses a battle with
  foreign robots unless Robocode's Security Manager is installed, so they cannot run with `-PjavaVersion=25`.
- **Environment:** robots may read environment variables, so the battle JVM gets only `PATH`, `LANG`, `LC_ALL`,
  `LC_CTYPE` and `TZ` (if set). macOS adds `__CF_USER_TEXT_ENCODING` to every process.
- **Sandbox check:** `./gradlew sandboxCheck` runs `SandboxProbe` against `sample.SittingDuck` and requires:

  | Check | Pass condition |
  |---|---|
  | Security Manager | Robocode's is installed |
  | Attempts | a connect to a closed loopback port, reading `$JAVA_HOME/release`, writing a file in the temporary directory with `FileOutputStream` and with `RobocodeFileOutputStream`, starting `true`, `checkExit(0)` and `setAccessible` are all blocked |
  | Threads | the 6th extra thread is refused |
  | Environment | the robot sees no variable beyond the list above |
  | Jar check | Ogi's jar is clean; the probe jar is denied, in `SandboxProbe` only |

  - Every attempt is harmless if it succeeds: it asks the Security Manager about exit instead of exiting, and it
    deletes the file it wrote.
  - The first blocked attempt kills the probe, but its code runs on and its next `execute()` still delivers the
    report, which only the snapshot of that turn can show (engine-model G-34). So the check runs in debug mode, and
    the probe waits 20 ms before its first attempt, until the engine waits for its turn. It prints the turns of the
    probe's death and of the report, and a stack dump of the probe's threads if the report does not come.
  - On Java 24+ the check fails by design, showing what a robot can do there.
- **Jar check:** `./gradlew jarCheck -Pjars=<jar or directory>,...` reads every class in each jar and prints a
  verdict with the findings. It exits non-zero if a jar is denied.

  | Verdict | Meaning |
  |---|---|
  | CLEAN | only allowed APIs |
  | FLAGGED | allowed, but worth a look: threads, file access (data files), serialization, its own resources |
  | DENIED | reflection, class loading, processes, exit, environment variables, network, file writes outside `RobocodeFileOutputStream`, native methods, internal JDK or Robocode engine APIs, AWT and Swing beyond painting, an API not on the allowlist; a class in a package Robocode takes from the JDK or the engine (`java.`, `javax.`, `kotlin.`, `robocode…`), anything in our or the sample packages, or two class files of the same class |

  - **What is judged:** every field and method each class file references, by its own rule or its class's; the
    classes it extends or implements; native methods; and jar entries that could hide code (class files under
    other names, nested archives, service registrations, multi-release classes, duplicates). A class the robot
    class loader would take from the JDK or the engine never counts as the jar's own, so it cannot hide references
    to the real one.
  - **Why references are enough:** without reflection and class loading, both denied, code can only call what its
    constant pool names, lambdas and bootstrap methods included.
  - **What it cannot see:** where a robot reads and writes files at run time; that stays with the sandbox.
  - The rules are in `foreign.ApiRules`. Checked as our own (`JarCheck.check(jar, true)`, without the package
    rule), Ogi's jars, the probes (except `SandboxProbe`) and the sample robots pass; `sample.SittingDuck` is
    flagged for reading its data file.
- **Reading the code:** `./gradlew decompileJar -Pjar=<jar>` decompiles a jar with Vineflower into
  `harness/build/decompiled/<jar name>/`. Source files shipped in the jar are left out, so what you read is what
  runs.
- **Approved jars:** foreign jars live outside git, in `.robocode/foreign/`. `approved.txt` there lists one jar per
  line, `<jar> <sha256> <notes>`.
  - `deployBots` first runs the jar check on the listed jars (`checkForeignJars`) and stops on a denied one. It
    copies only the listed jars into the installation, fails if a jar's SHA-256 differs from the listed one, and
    removes every other foreign jar from the installation.
  - An opponent set for them can live next to them and be passed as `-Popponents=.robocode/foreign/<set>.txt`.

## Queue

The queue runs comparisons on remote workers while development goes on: a job is queued from the working tree in one
command, a worker plays its battles on many slots at once, and a web page shows what ran, how long it took and what
is waiting.

```sh
./gradlew queueServer                                  # in a terminal of its own; http://localhost:8765/
./gradlew submit -Pbaseline=HEAD -Pnote="wider bins"   # the working tree against HEAD, lab set
./gradlew submit -Pbaseline=HEAD -Popponents=.robocode/foreign/vetted-1v1.txt -Pbounds=screening
./gradlew queueStatus
./gradlew queueCancel -Pjob=261009-185919-657
```

### Roles

- **Server** (`queue.Server`, on this machine): keeps the jobs in `.robocode/queue/`, starts each worker's executor
  when there is work for it, hands out battles while the worker has free slots, merges the results into each job's
  log, runs the test and serves the dashboard on the loopback interface only. It stops a worker's executor after five
  idle minutes. Everything a job knows is on disk, so a restarted server resumes its jobs.
- **Executor** (`queue.Executor`, on the worker): started by the server, over ssh for a Windows worker, as a local
  process for `local`. It keeps no state between runs and knows nothing about jobs. It prepares one Robocode home per
  slot, starts the slots, runs each battle the server sends on a free slot and sends the outcome back. It ends, with
  its slots, at the end of its input, on `shutdown`, or after 90 s without word from the server (which pings every
  20 s): Windows does not end a process when its ssh session drops.
- **Slot** (`queue.Slot`): one JVM with one engine, one battle at a time. Robocode keeps static state in the JVM, so
  battles at once need JVMs of their own.
- The server pushes work to the workers, because it can reach them over ssh while they cannot reach it (the worker's
  Java has no network, and this machine moves). Another worker is a line in `-Pworkers`.

### Workers

`-Pworkers=<name>:<slots>,...` (default `ogi-win:12`):

| Name | Where | Foreign robots |
|---|---|---|
| `local` | this machine, as a local process, in `.robocode/queue/workers/local/` | no (D-020: statistics against foreign robots run on the desktop) |
| any other | an ssh host with the native Windows layout: Java 17 in `C:\ogi\jdk`, the executor's files under `C:\ogi`, approved foreign jars in `C:\ogi\rumble` | yes |

- **Bundle:** at each connection the server makes the worker's `C:\ogi\bundle` equal to `harness/build/worker-bundle`
  (SHA-256 lists compared, differences copied with scp; Windows has no rsync). `queueServer` builds the bundle from
  the working tree when it starts, so restart the server after changing harness code.
- **A job's jars** go to `C:\ogi\jars` and from there into every slot's robots directory; the engine rescans that
  directory before each battle, so no slot restarts. A jar a slot already has stays (Windows does not let a jar the
  engine holds open be replaced); a jar of the same name with other content is an error.
- **Foreign jars:** copied into the slots only if `approved.txt` in the foreign directory lists them with their
  SHA-256 and the jar check does not deny them; the executor reports the jars it left out.
- **Slot JVMs:** `-XX:+UseSerialGC -XX:TieredStopAtLevel=1` (plus `-PqueueJvm`; `-Ddebug=true` for battles that ask
  for the engine's debug mode), `-Xmx1g`, the Security Manager
  allowed, and a minimal environment: on Windows `SystemRoot` and a temporary directory of the slot's own (`TEMP`,
  `TMP`), elsewhere the list of [Foreign robots](#foreign-robots). `sandboxCheck` accepts exactly these.
- **CPU constant:** the executor measures it five times in fresh JVMs under the slots' options on an idle machine,
  keeps the median in `<root>/state/cpu-constant.properties` (per Java, options and Robocode), and gives every slot
  the median times `-PcpuFactor` (default 1.5). Measure again by deleting the file; the measurement must run while
  the machine is otherwise idle. The factor and both constants are in each job's summary.

### Jobs

- **`submit`** builds the jars of `-Pbaseline` (and `-Pcandidate`) like `compare` does. Without `-Pcandidate` it
  takes a snapshot of the working tree (`snapshotJars`): its jars get the version `w` plus 7 hex digits of a SHA-256
  over `bot/` and the build files, tracked or not (ignored files left out), in `.robocode/snapshots/`. The same code
  gives the same version; jobs queued from different working trees never share a jar name. The working tree's own
  version (`botVersion`) names different code after every edit, so the queue does not use it.
- A job is a directory `.robocode/queue/jobs/<id>/`: `job.properties` (as submitted), `opponents.txt`,
  `status.properties` (state, times, order), and what the job writes. `submit` works whether the server runs or not;
  the server picks a job up within seconds. Its jars are copied into `.robocode/queue/jars/`, where a name stands for
  one content.
- Options: those of `compare` (`opponents`, default the lab set; `bounds`, `maxPairs`, `pairsPerOpponent`, `starts`,
  `seed`, `rounds`, `metric`), `note` (shown on the dashboard) and `timeout` (seconds per battle, default 900).
- **Order:** jobs run in queue order, the first job that has work for a free slot gets it. Moving a job up on the
  dashboard lets it take the slots as they free up; the job it passed keeps its state. A job waits while its jars
  are being copied, and later jobs use the slots meanwhile.

### A comparison as a job (`compare.CompareJob`)

The same pairs, seeds, log, test and summary as [Comparing two versions](#comparing-two-versions), and the same
configuration line, so its `battles.tsv` reads like a local one:

- A pair's two battles go to one worker (to any of its slots). Several pairs are under way at once; the next
  opponent is chosen as in `compare`, counting the pairs under way as played (`Sprt.next` with pending pairs), so that
  pairs handed out at once spread over the opponents like pairs handed out one by one.
- The test is checked after every complete pair. Once it decides, no more pairs go out; battles still being played
  are logged when they come back, while `summary.json` and `summary.txt` are those of the decision.
- A failed battle (an exception, a slot that died, a timeout) is retried on the same worker, twice; a third failure
  fails the job with the reason. A worker that goes away loses its battles: their pairs are dropped and new pairs
  take their place (a battle of a dropped pair that came back stays in the log as an incomplete pair, which the test
  ignores).
- `dispatch.tsv`: one line per battle: when it ended, the worker and slot, the battle, how long it waited for a slot,
  how long it took, and how it ended (`ok`, with our robots' longest own turn and the turns over half the idle
  constant and over it; `failed: …`; `lost: …`). `problems.log` as in `compare`.
- The job's health on the dashboard adds the versions' own turn times (P-04) from the battles of the current server
  run; they are not in the log, so a restarted server starts counting anew.
- Checked on 2026-10-09: a job of 2 pairs per opponent on four local slots gave, battle for battle, the same seeds,
  score shares and shots as the sequential `compare`. On the Windows worker the same job differs in some battles
  (the machines differ in the last bit of some doubles, [build-and-run.md](build-and-run.md#determinism-across-machines)),
  and with 12 slots Ogi skipped a few turns in the short lab battles, so that 5 of 30 pairs of identical code
  differed. A restarted server and a dropped connection both resumed with every pair counted once.

### Recordings as a job (`compare.RecordJob`)

`./gradlew submitGunLogs -Popponents=<set> -Ppairs=2` queues the gun's logs of the working tree (a snapshot's
`OgiGunLog`, keys `gunshot,gunpass,gunshield`) against a set, on the seeds of `compare` and `recordBattles`; `gunBench
-Plogs=.robocode/queue/jobs/<id>/recordings/<version>` then replays them here. The job type `record` takes any robot
and keys (`queue.Submit`).

- Each battle runs on any free slot; the slot records it into a temporary file and sends it back in Base64 inside
  its result, so the worker keeps nothing. The job writes `recordings/<version>/<opponent>_p<pair>.rec.gz` and a line
  to `recordings/<version>/battles.tsv`: the columns of `recordBattles`, then the opponent's skipped turns and our
  robot's own turn times.
- Battles go out pair by pair over all opponents, so a job stopped early covers every opponent alike. A recording
  that exists is not played again: a restarted server goes on where it stopped.
- **In debug mode**, like `recordBattles` (engine-model G-17): a battle can ask for it (`BattleSpec.debug`), and
  the executor restarts an idle slot with `-Ddebug=true`, because the engine reads the flag once per JVM. A job's
  battles all want the same mode, so each slot switches once when such a job starts. Without debug mode a robot that
  skips a turn can lose that turn's debug properties (its late commands are replaced, as in engine-model G-34): on
  2026-10-09 the gun bench's parity failed against 4 of 125 opponents, each one where Ogi had skipped turns (141 of
  2.4 million decisions, one wave without its plan).

### Dashboard

`http://localhost:8765/` (`-Pport`), on 127.0.0.1 and ::1 only (another program, e.g. a Docker port, may hold the
port on one of them; the server then stops with an error).

- **Workers:** state (offline, connecting, starting, ready, backoff, stopping), busy slots, battles and battles per
  second, failures and slot restarts, the CPU constant, Java and JVM options, the last error, the battles running.
- **Queue:** waiting and running jobs with what they compare, how long they waited and ran, pairs (with an estimate
  of the total: exact in fixed mode, otherwise the expected length of the test at the current estimate, rough while
  the estimate is), the estimate of the difference with its 95 % interval, the LLR between its bounds, the job's rate
  and ETA; buttons to move a job up or down and to cancel it.
- **History:** finished jobs with their decision, waiting and running time, pairs, estimate and health (skipped
  turns, problems, failed battles).
- **A job** (click its row): the decision, noise, health, the workers' environment, both scoreboards, the
  per-opponent table, problems and the last battles.
- `./gradlew queueStatus` prints the same in a terminal; actions are POSTs that need the header
  `X-Requested-With: ogi`, so another web page cannot trigger them.

### Remote checks

`remoteSandboxCheck`, `remoteSkeletonCheck` and the other `remote*` tasks run one main class on the Windows worker in
a slot's environment (`queue.Remote`, the executor's `run`): the bundle is synced, the class runs in a fresh home
`C:\ogi\run\<name>` and its output directory `C:\ogi\out\<name>` comes back to `harness/build/remote/<name>/`.
`-PremoteHost=ogi-desktop` runs them in the WSL container instead ([build-and-run.md](build-and-run.md#remote-worker)).

## Known quirks

- **The hit rate depends on the distance, not only on the gun.** At a longer distance the target has more time to
  move, so the same aim hits less. A movement change therefore moves `-Pmetric=hits` without any change in the gun
  (against the moving lab opponents, ~100 px more cost 3–10 points of hit rate). Compare guns with the same
  movement on both sides, and when the movement differs, check the distance at fire time in recordings
  ([Recording comparison battles](#recording-comparison-battles)).
- A team jar lists its robots in the repository under the same name and version as the robot jar
  (engine-model G-42). `BattleRunner` picks the jar named after the robot.
- `BattleResults.getRank()` in melee does not always follow the score order (engine-model G-31). Do not rely on it
  in new checks; rank by score.
- The output-based skipped-turn detection depends on the engine's message text (`BattleRunner.skippedTurn`). Our
  robots now report their own turn times (P-04), but skipped turns, of every robot, still come from the text.
- The engine swallows exceptions thrown by battle listeners. `TurnRecorder` keeps the first failure and rethrows it
  from `close()`.

