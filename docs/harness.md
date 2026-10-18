# Harness

The harness runs battles headless on the stock Robocode engine through its control API and reports structured
results. Every score claim about the bot must come from here.

## Components (`harness/src/main/java/cz/libors/harness/`)

| Class | Role |
|---|---|
| `BattleSetup` | A battle: label, field size, rounds, contestants (`sample.Walls`, `cz.libors.Ogi 0.1`, teams by name), seed, optional fixed starts |
| `BattleRunner` | Owns one `RobocodeEngine`; resolves contestants from the local repository, clears the robots' data, seeds the battle and runs it synchronously, optionally with extra listeners; lists the robot threads still alive after a battle |
| `BattleOutcome` | Results sorted by score, the seed used, skipped turns per robot, problems (engine errors, exceptions in robot output), robot rounds that missed their fixed start, bullets fired and hits per contestant, our robots' own turn times, elapsed time |
| `Starts` | Fixed starts drawn from a seed, and their engine string |
| `ContestantResult` | Score components per contestant: total, survival, last survivor bonus, bullet damage and bonus, ram damage and bonus, firsts |
| `SkeletonCheck` | Runs a fixed set of checks and writes `harness/build/skeleton-check.json` |
| `ProbeRecordings` | Records probe battles and checks their integrity, coverage and determinism; see [Recordings](#recordings) |
| `StrandedThreadCheck` | Part of `recordProbes`, in a JVM of its own without debug mode: a robot thread that stops calling the API outlives its battle (engine-model G-48) |
| `recording.TurnRecorder` | Battle listener that writes a recording: every robot and bullet in every turn, plus debug properties |
| `recording.Recording` | Reads a recording back into memory; no Robocode classes, so tests can use it without an engine |
| `Json` | Minimal JSON writer and reader for records, lists and maps; one-line ASCII form for the queue's protocol |
| `compare.Compare` | Paired, sequential comparison of two bot versions; see [Comparing two versions](#comparing-two-versions) |
| `compare.Sprt`, `compare.Stratum` | The stratified sequential test and the per-opponent paired statistics |
| `compare.OpponentSet`, `compare.CompareLog` | The opponent set file, and the battle log that makes a comparison resumable |
| `compare.RecordBattles` | Records the battles of a comparison turn by turn; see [Recording comparison battles](#recording-comparison-battles) |
| `gunbench.GunBench`, `gunbench.GunBattle`, `gunbench.GunReplay`, `gunbench.GunTrend` | The offline gun bench (source set `gunBench`): replays the gun's logs with other gun parameters, and the hit rate early against late in battles; see [Gun bench](#gun-bench) |
| `meleebench.MeleeBench`, `meleebench.MeleeBattle`, `meleebench.MeleeReplay` | The offline melee bench (source set `gunBench` too): replays the melee gun's logs of recorded melee battles with other melee gun parameters and scores the bullets against the recorded positions; see [Melee bench](#melee-bench) |
| `dangerbench.DangerBench`, `dangerbench.DangerBattle`, `dangerbench.DangerReplay` | The offline danger bench (source set `gunBench` too): replays the enemy bullets of full recordings through the surfer's danger model; see [Danger bench](#danger-bench) |
| `Sandbox` | Which robots are foreign, and whether Robocode's sandbox is active; `BattleRunner` refuses foreign robots without it. See [Foreign robots](#foreign-robots) |
| `SandboxCheck` | Proves that the battle JVM sandboxes robots and passes them a minimal environment |
| `foreign.JarCheck`, `foreign.ApiRules`, `foreign.ClassFile` | Checks a robot jar against an allowlist of the JDK and Robocode APIs before it runs |
| `queue.Server`, `queue.Dashboard` | The queue's server on this machine: jobs, workers, the web page; see [Queue](#queue) |
| `queue.Executor`, `queue.Slot` | The worker side: slots, each a JVM with a Robocode home of its own, driven over standard input and output |
| `queue.Submit`, `queue.QueueClient`, `queue.Remote` | Queue a job; the queue from a terminal; run one main class on a Windows worker |
| `queue.QueueJob`, `compare.CompareJob`, `compare.RecordJob` | A job in the queue; a comparison run as one, with pairs on many slots at once; recordings as one |
| `compare.MeleeJob`, `compare.MeleeDraws`, `compare.MeleeLog` | A comparison against a melee set: draws of nine opponents, their log; see [A melee comparison](#a-melee-comparison-comparemeleejob) |
| `queue.BattleCache` | Battles already played or being played, shared between jobs; see [Battle cache](#battle-cache-queuebattlecache) |
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
- **Own turn times:** our robots report the wall time of their previous turn in the debug property `cpu`
  ([bot.md](bot.md#debug-overlay-czliborsogitrace)). `BattleOutcome.turnTimes` keeps per robot the number of turns
  reported, the longest, and how many took longer than half the CPU constant of an idle machine and than all of it:
  the home's constant locally, the executor's measured idle constant on a queue worker, whose slots run with a raised
  one. The engine would skip a turn only later than the constant (engine-model G-45), so these counts see a robot
  growing heavy before skipped turns do.
  - On a loaded worker the wall time includes the other battles' load and the garbage collections of the slot's JVM,
    which the opponent's allocations trigger too. With 12 battles at once on the Windows worker (2026-10-09) Ogi's longest
    turns were 23 ms (lab set) and 70 ms (against BeepBoop and Diamond), and 10–37 turns per job went over the idle
    constant. So the gate for Ogi's own weight is the skeleton check on an idle machine; the queue shows the counts
    as health.
- **Matching:** a contestant is matched by name prefix. Team results are named after the team plus an index, e.g.
  `cz.libors.OgiTeam 0.1 (1)`.

## Robot data

Every battle starts with empty robot data, apart from what the exceptions below keep: `BattleRunner` deletes the
home's `robots/.data/` before each battle, and a queue slot again right after each battle.

- **Why:** the engine gives every robot from a jar a data directory, `robots/.data/<package>/<Class>.data/`, and
  keeps it across battles. Some opponents save what they learned about an enemy there and read it in their next
  battle against it, some under a key without our version. Without the clearing, a battle would depend on the
  battles its home played before, e.g. a baseline's battle on the candidate's, and the battles of one executor run
  would not be independent.
- **What it means:** the harness measures every pairing as a first meeting. A rumble client keeps its robots' data,
  so in the rumble such robots meet us with what they saved before.
- Data a robot ships inside its jar stays: the engine copies it from the jar again when the robot starts
  (engine-model G-47).
- Robocode's samples are loaded from class directories and keep their data next to their classes; only
  `sample.SittingDuck` writes any, a battle count it prints.
- **If a file cannot be deleted** (on Windows, one a robot left open: the engine does not close robots' streams;
  seen on the Windows worker on 2026-10-11), `BattleRunner` runs a garbage collection, which closes streams nothing
  references any more, and tries again, three times in all. If the file still cannot be deleted, it throws
  `BattleRunner.RobotDataNotCleared`. A queue slot, which clears right after the battle, then sends that battle's
  result with `leaving` before it and exits with code 3, and the executor restarts it in a fresh home: no other
  battle is charged. Should the clearing before a battle fail anyway, that battle fails and its job tries it again.
  A local `compare` stops with the exception; run it again and it resumes.
- **Checked by** `recordProbes` ("Robot data is kept from round to round and cleared before every battle").
- Logs of `compare` carry `robotData=cleared` in their configuration, so a log from before the clearing is not
  resumed; the battle cache's key has version 2 for the same reason.

## Robot threads that outlive their battle

The engine stops a robot only by interrupting its thread and waiting for it; a robot stuck in a loop that never
calls the robot API keeps its thread, which runs on after the battle and takes a core until its JVM ends
(engine-model G-48). On the queue's worker one such robot slowed all 12 slots: its four battles left 18 threads
computing, and Ogi skipped tens of thousands of turns in the battles of the next 25 minutes (2026-10-11).

- After every battle a queue slot looks for threads of the battle's robots that are still alive
  (`BattleRunner.robotThreadsAlive`: robot threads run in a thread group named after their robot as the battle
  names it, or in a group inside that one). If any is left after 3 s, the slot says `leaving`, sends the result and
  exits with code 4; the executor restarts it in a fresh JVM without counting it against the slot's restart limit.
  The battle itself counts: the stuck robot slowed it as it would slow a rumble client.
- A battle in the harness's own JVM (`compare` on this machine, the checks) leaves such a thread running until the
  JVM ends.
- **Checked by** `recordProbes` ("A robot thread that stops calling the API outlives its battle").

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
| `DataProbe` | like `RandomProbe`; counts the rounds it started in a file in its data directory and reports what the file held (`data`) | shows that robot data persists within a battle and is cleared before the next |
| `SandboxProbe` | none | tries what the sandbox must block; used by `sandboxCheck`, not recorded ([Foreign robots](#foreign-robots)) |
| `SpinProbe` | none | computes for 20 s without calling the robot API after three turns; used by the stranded-thread check, not recorded ([Robot threads](#robot-threads-that-outlive-their-battle)) |
| `MessageProbe` | none | a `TeamRobot` that broadcasts the turn it acts in and reports the messages it received in the turn (`msg`: the turn, then `sender:sentTurn:eventTurn` per message); it spins its radar and fires at enemies it scans. Three of them make the team `MessageTeam` |

- **Commands:** every turn each command channel gets a new random command with a per-round probability: ahead,
  body, gun and radar turns, max velocity, max turn rate, the three adjust flags, fire.
- **Edge cases on purpose:** reversals against the current velocity, short moves, zero and infinite distances and
  turns, body turns ending within 2e-5 rad of a multiple of 90°, out-of-range velocity, turn-rate and power values.
- **Reporting:** a probe reports through `setDebugProperty`, which reaches the recorder in the turn snapshot.
  - `rem`: time, then distance, body, gun and radar turn remaining as the engine left them after this turn's move.
  - `cmd`: time, then the commands sent for the next turn: distance, body, gun and radar turn remaining, max
    velocity, max turn rate (radians), the adjust flags gun-for-robot, radar-for-gun and radar-for-robot, the fire
    power requested (`-` if none) and whether the robot API accepted it.
  - `fireGetters`: in a turn it tries to fire: time, the power requested, `getGunHeat()` and `getEnergy()` before
    `setFireBullet` and after it, and whether the robot API accepted the shot (engine-model G-49).
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
p       round  turn  robot  x  y
end     round  turns
```

- **Turn 0** is the state at round start.
- **A record of turn t** is the state after the engine's update of turn t. The debug properties of turn t are those
  the robot set while acting in turn t; its commands take effect in turn t + 1.
- **Left out:** explosions, and debug properties of dead robots. The engine does not wait for a dead robot's last
  turn, so its debug properties race with the snapshot (engine-model G-34).
- **Robot and bullet states** are the names of Robocode's `RobotState` and `BulletState`; `victim` is a robot index
  or -1.
- **A recording limited to some debug properties** (`TurnRecorder.open` with keys, `-Pkeys`) has no `r` and `b`
  records. With the key `@positions` (`TurnRecorder.POSITIONS`, not a debug property) it has a `p` record for every
  live robot in every turn: about a tenth of an `r` record, which a melee gun log of ten robots needs (the
  [Melee bench](#melee-bench) scores bullets against these positions).

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
| Team messages | `MessageTeam` against `RandomProbe`: in every turn a probe acted in, each message it received was sent in the turn before and stamped with this turn, and every teammate that acted in the turn before is among them (G-23). After the last enemy's death the round runs on without the probes acting; those turns do not count |
| Own jar | the repository lists `cz.libors.Ogi <v>` from the robot jar and the team jar; `BattleRunner` picks the robot jar (G-42) |
| Robot data | two battles in a row of `DataProbe` against `RandomProbe`: in both, the file is empty in round 1 (`getDataFile` creates it, G-47) and holds 1 in round 2 ([Robot data](#robot-data)) |
| Stranded thread | `SpinProbe` stops calling the API and computes for 20 s, `sample.TrackFire` kills it, in a JVM of its own without debug mode (in debug mode the engine waits for the stuck turn instead, G-17): its thread is alive after the battle, takes most of a core and is gone once the probe stops by itself (G-48, [Robot threads](#robot-threads-that-outlive-their-battle)) |

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
never fires, no bullet counted for it. Of our robots' own turns at most 0.5 % may take longer than half the
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

A text file per set in `harness/opponents/`: an optional `title` line that says in words what the set is (the
dashboard shows it), a `format` line (`1v1`: 800×600, 35 rounds; `melee`: 1000×1000, 35 rounds, ten robots, see
[A melee comparison](#a-melee-comparison-comparemeleejob); `team`: 1200×1200, 10 rounds), then one robot or
team per line as Robocode lists it, including its version. A line `[name]` starts a group: opponents under
`[strong]` form a scoreboard after the overall one (and after the rumble estimate, if there are weights), every other
group but `[other]` a scoreboard of its own (e.g. rumble tiers); `[all]` and `[rumble]` are reserved;
a group may start again further down. Lines `weight [name] n` say how many robots of the rumble each group stands
for (e.g. a tier's size); with them every group needs a weight, and the report adds a rumble estimate (below). The
estimate weighs only the set's own groups, so a set that holds some of the rumble's tiers (the strong and medium
bots of a population, say) has no weights: with them its "rumble estimate" would be those tiers' mix, shown as the
rumble's. Only trusted robots are in the repository: `sample-1v1.txt` and
`sample-team.txt` (the sample bots), and `lab-1v1.txt` (the sample bots plus the random-moving probes; their lines
carry `botVersion` and must be edited when it changes), for gun and movement work. Sets of rumble bots stay out of
git, in `.robocode/foreign/` on this machine (`-Popponents=.robocode/foreign/<set>.txt`); the jars they name must be
on the machine that plays the battles (for the queue, the worker's `C:\ogi\rumble`).

### Method

- **Pairs:** both versions fight the same opponent with the same seed (and the same starts with `starts=fixed`); the
  pair's difference is the candidate's score share minus the baseline's, in points (0–100). Which version goes first
  alternates.
- **Estimate:** every opponent weighs the same, as in the rumble's APS. The difference is the mean of the per-opponent
  mean differences, with variance `(1/H²)·Σ s_h²/n_h`. While an opponent has fewer than 5 pairs, its variance is the
  one pooled over all opponents.
- **Rumble estimate** (only with `weight` lines): every opponent weighs the same within its group, and the groups
  weigh as their robots in the rumble; the variance adds the groups' variances times their squared weights. It is
  shown, not tested: the test decides on the equal-weight estimate.
- **Allocation:** 2 pairs per opponent first; then the next pair goes to the opponent furthest below its Neyman
  share (`n_h ∝ s_h`), so opponents where the versions differ little get few pairs.
- **Stopping rule:** fishtest's normal approximation of the generalized SPRT, `LLR = (δ1 − δ0)(2Δ̂ − δ0 − δ1)/(2V)`,
  with α = β = 0.05: H1 at LLR ≥ 2.94, H0 at LLR ≤ −2.94. No decision before the burn-in and before the pooled
  variance has 20 degrees of freedom; with fewer, a variance underestimated by chance ends the test after a few
  pairs. Unit tests simulate the test at both hypotheses: error rates and lengths match the theory for one
  opponent; with 10–30 opponents a simulation gave 7.5–9 % errors at the bounds instead of 5 % (2026-10-11).
- **The interval after a stop** is the fixed-sample one, ±1.96 standard errors at the pairs the test stopped at. A
  sequential stop makes it too narrow (in simulations it covered the true difference 81–92 % of the time instead of
  95 %), so a "Better by" or "Worse by" of a stopped test is weaker evidence than it reads; fixed mode is not
  affected.
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
turn. Against a melee set it records the draws of a [melee comparison](#a-melee-comparison-comparemeleejob)
instead (`-Pdraws=n`, draws 0 to n − 1, into `<out>/<version>/draw<d>.rec.gz`; `only` and fixed starts do not
apply); `harness/opponents/sample-melee.txt` draws nine of Robocode's sample bots. A version is a git revision (built by `baselineJars`), a jar in `.robocode/baselines/` or the working tree's
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
| `draws` | 1 | against a melee set: draws, from draw 0 |
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
./gradlew gunBench -Plogs=.robocode/queue/jobs/<id>/recordings/<version> -Pselection=<tsv> -Ptune=8
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
  `virtualWeight`, `minTries`; the rest come from `GunParameters.DEFAULT`. They are the main gun's: the anti-surfer
  gun keeps its own fixed copy (`GunParameters.ANTI_SURFER`). `-Probot=<prefix>` picks the logged robot by name
  prefix (default `cz.libors.Ogi`).
- **Team battles:** each of our robots that logged is a battle of its own (each has its own gun), against the other
  team, named without the engine's copy number. The models are keyed by the target's base name, as the bot keys them
  (the copies of an enemy share one). Parity holds on `OgiGunLogTeam` logs (240 robot logs of 48 battles against
  ranks 1–12, 2026-10-17).
- **`-Pscope=oneEnemy`** counts the hits of the plans made while one enemy lived only, by our robot's own
  `meleedeath` lines: in a melee or team battle the turns in which this gun aims the bullets (before, the melee gun
  aims them, and this gun's waves at the closest enemy only teach it). Every wave still teaches the model, and every
  plan is still checked for parity. In a 1v1 log it is every plan. A plan logged without the one-enemy flag (before
  2026-10-18) gets it from the same deaths (the flag is logged for the bench only; a gun choice by it was tried and
  left out, 2026-10-18); where the flag was logged, the bench reports any plan whose flag the deaths contradict.
  `-Plearn=scope` lets only those plans' waves
  teach the model, an experiment: the replay then no longer decides as the bot did, so there is no parity check.
  Record such logs with `submitRecordings
  -PgunLog=true -Pkeys=gunshot,gunpass,gunshield,meleedeath -Pdebug=true` (`submitGunLogs` keeps the melee gun's
  keys against a team set).
- **A change of aim needs the bench both ways.** The recorded paths answered the logged gun: a target that reacts
  to being hit (it reverses, steps aside, or, as a surfer, avoids where it was hit) did so after the logged gun's
  hits and never after another gun's, so any other gun looks better on the replay. Queue `submitGunLogs` for both
  versions on the same seeds and replay each with the other's parameters: the candidate's gain on the baseline's
  logs is effect + bias, the baseline's gain on the candidate's logs is −effect + bias, so half their difference is
  the effect and half their sum the bias. Measured twice on the population, the bias was larger than the effects:
  - 2026-10-10, a retuned gun: +2.32 points of `fired %` on the old logs, −0.54 on the new ones, so about +0.9 real
    and 1.4 bias; the battles agreed with the +0.9.
  - 2026-10-13, a gun change: +0.86 on the old logs, +1.26 for the old gun on the new ones, so −0.20 real and 1.06
    bias (1.8 against the strong tier, 0.8 against the weak one); the battles' hit rate moved −0.24, and the
    one-way +0.86 had sent the change to battles.
  A random offset of the aim does not show the bias: it hits where the logged gun hit.
- **`-Pselection=<tsv>`** (columns `stratum` and `name`) adds the mean `fired %` per stratum.
- **`-Ptune=<passes>`:** a coordinate search from the given parameters that maximises the mean over opponents of
  `fired %` on half of the opponents, and reports both halves with the start and the tuned parameters: per stratum
  the means, the mean per-opponent change with its 95 % interval, and how many opponents went up and down. Each
  parameter in turn takes the best of its candidates if that beats the current value by more than 0.01 points; a
  value of 0 tries 0.05 to 2. It ends with the tuned parameters as `-P` options, every digit printed.
  - **Left out:** opponents that shoot down more than `-PmaxShielded` (default 0.15) of our bullets, because they aim
    at our aim and the replay cannot answer for a new one; and opponents that never get a bullet. Accidental bullet
    collisions stay below ~14 %; bullet shielders are well above.
  - **Split:** the rest is shuffled per stratum (`-PsplitSeed`, default 1) and every other opponent goes to the
    training half, with all its battles. The other half tests whether the numbers carry over to robots the search
    never saw; a split by battles of the same robots would not show that.
  - **Search:** each parameter in turn tries 0.5, 0.7, 1.4 and 2 times its value (weights, `virtualWeight` and
    `minTries` also 0, `virtualWeight` also 1) and keeps the best that raises the objective by more than 0.01; after a
    pass without a gain it tries 0.85 and 1.18 times, and stops after such a pass gains nothing. One evaluation
    replays the training half: about 9 s for 110 opponents on the Mac.

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
  by the target's position, so before `gunshield` such bullets counted as hits: against three such robots the bench
  showed 23–37 % fired against the harness's 6–12 % (2026-10-09). Left out, the bench shows 7.6–13.3 %. The harness
  counts them as misses, so its rate stays a little lower against these robots.
- `./gradlew gunTrend -Plogs=<dir>` compares the fired hit rate in the first 10 rounds of each battle with the last 10
  (`-Prounds`), per opponent, sorted by the change over its standard error.
- **`fired %` against `harness %`:** checked bullet by bullet on four lab battles (2026-10-09), before the aim spread
  and the shadow shots: every bullet whose wave passed the target got the verdict the engine gave it, except bullets
  destroyed by an enemy bullet, which the bench leaves out (`shielded`). Since then the turret fires a few pixels off
  the gun's aim (aim spread), or at another peak (shadow shots), and the log keeps only the gun's aim: `fired %` scores
  the guns' aims, as they learn them, not the bullets that flew. The two counts differ only in which bullets they include:
  - the bullet that kills the target counts for the harness, but its wave never passes (a dead target is not
    scanned), so the bench and the bot never see it: one hit per round won;
  - a bullet that has passed the target but is still flying when the round is decided counts for the bench, not for
    the harness.

  Against the lab set the bench's `fired %` is therefore 1–4 points lower.
- **Limits:**
  - Against a target that reacts to our aim or our hits the replay is optimistic: the recorded movement answered
    the logged gun, not the replayed one, so any change looks better than it is, by about a point of `fired %` on
    the population; see "A change of aim needs the bench both ways" above. The bias is largest against wave surfers
    and smallest against simple movers.
  - A replayed aim the turret could not have reached in time would not have been fired that turn; the replay fires
    it anyway.
  - A recording can lose a turn's debug properties, and with them a plan: after a skipped turn, and rarely without
    one (2026-10-10, 1 battle in 450: the snapshot of a turn repeated the previous turn's properties). The replay
    then reports an orphan wave and a few `differ` after it.

## Melee bench

The melee bench replays the melee gun's aims of recorded melee battles with other melee gun parameters
(`MeleeGunParameters`: the feature weights, the neighbours, the ranges, the softening, how an enemy's distance
weighs, the kernel's width, the age limit, the horizon and the records kept), in seconds instead of battles. It runs
the bot's own `MeleeGun` on what the bot logged, so with the logging bot's parameters it aims exactly as the bot did,
and it flies each bullet along the replayed aim against every robot's recorded positions.

```sh
./gradlew submitGunLogs -Popponents=.robocode/foreign/rumble-melee-2026-10.txt -Pdraws=100   # on the queue
./gradlew recordGunLogs -Pversions=0.1 -Popponents=harness/opponents/sample-melee.txt -Pdraws=2  # here
./gradlew meleeBench -Plogs=.robocode/queue/jobs/<id>/recordings/<version>
./gradlew meleeBench -Plogs=<dir> -Pneighbours=24 -PenemyDistancePower=2
./gradlew meleeBench -Plogs=<the base's logs> -PotherLogs=<the variant's logs> -Pneighbours=24   # both ways
./gradlew meleeBench -Plogs=<dir> -Ptune=4
```

- **The logs:** against a melee set `OgiGunLog` logs the melee gun's inputs and decisions (`MeleeGunLog`: keys
  `meleescan`, `meleedeath`, `meleeaim`, `meleefire`, `meleeend`, see [bot.md](bot.md#debug-overlay-czliborsogitrace)),
  and the recording keeps every live robot's position in every turn (`@positions`, [Format](#format)): about 1.1 MB
  per 10 rounds against the sample bots.
- **Team battles:** against a team set `submitGunLogs` records five `OgiGunLog` as a team (`cz.libors.OgiGunLogTeam`)
  with the same keys. Each of our robots' logs is replayed as a battle of its own, with a memory of its own, as each
  robot played (`MeleeBattle.readAll`); a bullet whose first robot is a teammate of the logging robot counts as a
  miss; the robots of one recorded battle stay together, as one cluster in the interval and in one half of the
  tuning's split. The bot plays team battles with `MeleeGunParameters.TEAM`: once it differs from `DEFAULT`, give it
  as `-Pbase`. Record them with `-Pdebug=true`: without debug mode five logging robots of ours, each logging 5–10
  scans a turn, lose turns' logs (a lost turn repeats the turn before's log), and 0.9 % of the replayed aims differed
  (7 battles, 2026-10-17). The index of a team recording has no turn counts of ours (`skippedTurns`, `turns` … are
  0): `RecordJob` looks for robots named after the team, not its members.
- **Replay:** per battle a fresh `MeleeGun.Memory`, per round a new `MeleeGun`, fed the logged scans and deaths in
  their order. At each aim that a bullet was fired on, the replayed gun aims. Bullets of the duel at the end come from
  the duel's guns and are not scored.
- **Scoring:** the bullet flies along the replayed aim from where the bot fired it, with its power, by the engine's
  rules (`BulletMotion`, `RobotBox`: the move of turn t against the boxes at the end of turn t − 1, engine-model
  G-02): the first robot's box it touches is a hit, the wall a miss. Left out are bullets an enemy bullet destroyed
  (they ended in `meleeend` with `b`: the aim did not decide them) and bullets still in the air at the round's end. A
  shot the replayed gun has no aim for counts as a miss (`no aim`).
- **Parameters:** `-Pweights` (12, in the order of the features; missing ones are 0, so older command lines keep
  their meaning), `-Pneighbours`, `-PwallRange`, `-PneighbourRange`,
  `-PdistanceSoftening`, `-PenemyDistancePower`, `-PkernelWidth`, `-PmaxAge`, `-Phorizon` and `-PmaxRecords` give the
  variant over the base. `-Pbase="neighbours=24;horizon=98"` gives the parameters the logging bot played with, over
  `MeleeGunParameters.DEFAULT`. `-Probot=<prefix>` picks the logged robot (default `cz.libors.Ogi`).
- **Parity:** with the base parameters every replayed aim must equal the logged one within 1e-9 rad, and the robot
  it was aimed at must be the same; the task fails if more than one aim in 1,000 differs. Battles in which the
  logging robot skipped a turn (the index's `skippedTurns`) are left out of the check: without debug mode a skipped
  turn can lose its log, and the rest of that battle replays from other records. Without debug mode a turn's log is
  also lost now and then where no skipped turn was reported (58 of 127,929 aims apart in 81 such battles,
  2026-10-16); base and variant replay the same inputs, so comparisons are not affected. Logs from the Windows
  worker (x86_64) replayed on the Mac (arm64) differ in the last bit of some doubles
  ([build-and-run.md](build-and-run.md#determinism-across-machines)), and such a difference can tip a nearly tied
  peak of the density: 3 of 157,114 aims of 99 rumble draws (2026-10-15); logs from the same machine replay bit for
  bit. `MeleeGunTest` checks the replay on a simulated fight. `-PdebugDiff=true` prints the first differing aims
  of each battle and every real bullet that ends otherwise.
- **Real bullets:** each bullet whose end the bot learned (a hit, the wall) also flies along its real heading and must
  end as in the engine. On two local battles against the sample bots all 965 did; on 99 rumble draws 67 of 132,755
  did not (2026-10-15): a robot that another bullet killed earlier in the same turn still stands in the replay (the
  engine moves the bullets of a turn in random order, engine-model G-04).
- **Output:** for the base and the variant the bullets scored, `fired %` (hits per bullet scored), the damage per
  bullet scored, and the bullets with no aim, shot down, open and with an aim that differs from the log; then the
  variant's change of `fired %` with its 95 % interval over the battles as clusters (per battle each side's
  linearised residual (h − R·s)/Σs, differenced).
- **Both ways** (`-PotherLogs`: logs of a bot that played with the variant, on the same draws): as on the
  [Gun bench](#gun-bench), a target that reacts to being hit reacted to the logged gun's hits, so any other aim looks
  better on the replay. The variant's gain on the base's logs is effect + bias, the base's gain on the variant's logs
  is −effect + bias; the bench prints both, the effect and the bias.
- **`-Ptune=<passes>`:** the gun bench's coordinate search over the 17 parameters, on half of the battles (shuffled
  by `-PsplitSeed`, default 1), reported on both halves against the base. Integer parameters are rounded; the
  weights and `enemyDistancePower` may also go to 0. It ends with the tuned parameters as `-P` options.
  `-Pfixed=neighbours,maxRecords,...` leaves those parameters as given (`weights` for all eight): the search knows
  nothing of the gun's time, and left alone it raised the counts to 12 times the gun's time for little.
- **`-Ppowers=1.5,1.92,2.4,3`:** the same shots at other bullet powers (aimed and flown at that power): per power
  and band of the distance to the robot aimed at, the hit rate, the damage per bullet, the damage per turn of the
  gun's heat (1 + p/5 per bullet, cooling 0.1 a turn) and the energy a bullet nets (3p back per hit, p paid). A guide
  for queue tests: fewer shots, the energy and the kills are not replayed.
- **Limits:** the movement, the fire timing and the bullet power stay as recorded; a replayed aim the turret could not
  have reached in time is fired anyway; a hit the replay adds or takes away does not change the battle's later course
  (energy, who dies when), and the bench scores the hit rate, not the score.

## Position bench

The position bench replays the melee movement's decisions of recorded team battles with other
`MeleeMovementParameters` and scores the destinations chosen against what the recorded battle held for them.

```sh
./gradlew submitRecordings -PgunLog=true -Popponents=.robocode/foreign/team-strong-2026-10.txt -Ppairs=4 -Pdebug=true
./gradlew positionBench -Precordings=.robocode/queue/jobs/<id>/recordings/<version> \
    "-Pvariants=teammateWeight=1|closerCountsEnemies=false" [-Pbase=<k=v;...>] [-Phorizon=30]
```

- **Input:** full recordings of `OgiGunLogTeam` with its `meleemove` log ([bot.md](bot.md#debug-overlay-czliborsogitrace)),
  recorded in debug mode.
- **Replay:** per round and robot a `MeleeMovement` with the parameters, fed each logged decision: the gang defence's
  switch, the position into the history, then `choose` over the logged candidates with their logged waves' damage.
  **Parity:** with `-Pbase` (the parameters the bot played with, over `DEFAULT`) every choice must be the logged one;
  the task fails otherwise (332,881 of 332,881 on 48 battles against ranks 1–12, 2026-10-17).
- **Outcome of a destination**, over the horizon, driving straight there by the engine's rules and standing:
  - bullets in the air at the decision, the enemies' and the teammates': exact, from the recording, each move against
    the box of the turn before (G-02); a bullet that hit this robot flies on;
  - the enemies' later bullets, at their recorded shots: the chance that this robot was the target (the enemy team's
    share of shots at our closest robot if it is the closest there, the rest split over the others) times the enemies'
    hit rate at that distance times the damage;
  - our robot's own later bullets, at the enemy each was aimed at: our hit rate at that distance times the damage.

  The shares and hit rates come from the same recordings (the robot a bullet was aimed at is the opposing robot whose
  bearing at f − 2 is nearest its heading, as in `tools/team_position_analysis.py`). The model's damage from bullets
  in the air matches what the logged choices met: 1.64 a decision against 1.68 within 30 turns (2026-10-17).
- **Report:** per variant, the share of decisions it changes and, per decision, the change of damage taken from
  bullets in the air, from later bullets, friendly fire, damage dealt and net, with the 95 % interval over the
  battles as clusters; then per enemy team.
- **Limits:** the enemies do not react to the replayed positions, and each decision is scored as if the robot kept
  its destination for the whole horizon, while the bot decides anew every turn: the bench sees a destination's next
  seconds, not where a whole battle of such choices takes the team (formation, who gets ganged, kill order).
- **Measured against the battles (2026-10-17):** both ways on 48 battles each, the teammate weight 1 against 3 came
  out +0.015 ± 0.004 damage a decision for 1 (bias +0.013), and counting only our robots as closer to an enemy
  (on the queue −1.64 APS) +0.006 ± 0.004; the queue had measured weight 3 at +2.10 APS over 1. The recordings
  themselves show the queue's direction: robots with weight 1 met 9 % more damage from bullets in the air. So the
  bench's numbers are a diagnosis of a decision's next seconds, not a test of movement parameters.

## Danger bench

The danger bench scores the surfer's danger model ([bot.md](bot.md#movement-czliborsogimove)) on where the enemy
really aimed. A full recording holds every enemy bullet's heading, also of the bullets that missed, which the bot
never sees. The bench replays each battle through the bot's own `DangerModel`, `TargetingFeatures` and
`RobotHistory`, with given `DangerParameters`.

```sh
./gradlew submitRecordings -Popponents=<set> -Ppairs=2       # full recordings, on the queue
./gradlew dangerBench -Precordings=.robocode/queue/jobs/<id>/recordings/<version> -Ppreset=histogram
./gradlew dangerBench -Precordings=<dir> -PknnShare=0.8 -PknnRamp=20 -Ptune=4
```

- **Input:** full recordings (`recordBattles`, `submitRecordings`) of `cz.libors.Ogi` (`-Probot`) in 1v1; without
  `-Precordings` it reads `harness/build/battle-recordings/<botVersion>`, the default `recordBattles` output. Gun
  logs have no robots or bullets.
- **Replay** (`DangerBattle`, `DangerReplay`): every enemy bullet becomes a wave as the surfer sees one with radar
  lock ([fire-detection.md](fire-detection.md)): detected in its first recorded turn f, fired in f − 1 from the
  enemy's position then, aimed from our state of f − 2. In each turn the model first learns the bullets that ended
  on us or on one of our bullets after their wave appeared, then gives each new wave its profile. One model per
  battle, as the bot keeps one per enemy for the battle.
- **Score per bullet:** the profile's share over the bullet's guess factor ± half our width seen from the origin
  (`DangerModel.mass`, the sum the surfer takes over a hit range; the surfer's hit ranges are about 1.3 times as wide
  as this window). The report gives per opponent the mean logarithm of that share (`mean ln`, higher is better; a
  uniform profile scores about −2.3 to −2.4 on real recordings), its mean (`mass %`)
  and its mean over the bullets that hit us (`hits %`); then the mean over opponents, per stratum with
  `-Pselection=<tsv>` (columns `stratum` and `name`).
- **Parameters:** `-Ppreset=default|histogram` (`DangerParameters.DEFAULT` or the single histogram the surfer had
  until 2026-10-09), then `weights` (10, in the order of `TargetingFeatures`), `neighbourScale`, `maxNeighbours`,
  `bandwidth`, `knnShare`, `knnRamp`, `priorWeight`, and the ensemble's `simplePrior`, `simpleBandwidth`, `temper`,
  `decay`, `share`, `flatPrior`, `flatWindow`.
- **`-Pbullets=<tsv>`:** also writes every bullet's score with where it was aimed, one row per bullet: the recording,
  the opponent, the round, the turn it was fired in, its origin, our position when the enemy aimed and our lateral
  velocity then (positive counter-clockwise around the origin), its guess factor, half our width, whether it hit us,
  the share and its logarithm (`DangerBench.BULLET_COLUMNS`). Reports by situation (walls, distance) group these rows.
- **Simple guns and the flattener:** a shot also carries where the `SimpleAims` would have aimed, from the enemy's
  recorded position of turn f − 2. A wave that passes us (`HitRange` over our recorded positions, as the surfer
  advances it) teaches the flattener where we were, also when the surfer learned its bullet before.
- **`-Ptune=<passes>`:** a coordinate search from the given parameters that maximises the mean over opponents of
  `mean ln` on the recordings of pair 0, and reports the start, the result and the histogram on pair 0 and on the
  other pairs (other seeds of the same opponents). An opponent whose bullets score NaN with some parameters makes the
  objective NaN, so such parameters never win.
- **Our lateral velocity** goes into the pattern and averaged guns' history only in the turns our radar saw the enemy
  and while we live, as online (the bench replays the engine's scan, `RadarScan`).
- **Limits:**
  - The recorded movement came from the recorded bot's surfer. Another model moves us elsewhere, and a learning gun
    then learns other things; the bench shows how well a model predicts, not the score. `compare` decides.
  - Fire detection is taken as exact; the bot misses about 0.1 % of the shots with radar lock.

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
  - **Team jars** may carry their members as jars inside the jar: Robocode reads every entry named `*.jar` or
    `*.zip` as a jar of its own and loads robots from it (`JarRoot.readJarStream`). Such a member jar is checked
    like a jar of its own, its classes judged against its own classes only (a robot's class loader reaches only the
    archive it was found in); findings and robots name it, e.g. `Member-1.0.jar!/a.Member`. An archive inside a
    member jar, or under another name, is denied. The report lists a jar's teams as `<team> (team)`.
  - **Why references are enough:** without reflection and class loading, both denied, code can only call what its
    constant pool names, lambdas and bootstrap methods included.
  - **What it cannot see:** where a robot reads and writes files at run time; that stays with the sandbox.
  - The rules are in `foreign.ApiRules`. Checked as our own (`JarCheck.check(jar, true)`, without the package
    rule), Ogi's jars, the probes (except `SandboxProbe`) and the sample robots pass; `sample.SittingDuck` and
    `cz.libors.probe.DataProbe` are flagged for reading their data files.
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
  process for `local`. It keeps no job state between runs (only the CPU constant it measured). It prepares one
  Robocode home per slot, starts the slots, runs each battle the server sends on a free slot and sends the outcome
  back. A slot that ends is restarted in a fresh home; one that has to be restarted more than 10 times (its own
  `leaving` exits not counted), or that does not start within 3 minutes 10 times, ends the executor, so that the
  server starts a fresh one later. The executor ends, with its slots, at the end of its input, on `shutdown`, or
  after 90 s without word from the server (which pings every 20 s): Windows does not end a process when its ssh
  session drops.
- **Slot exit codes:** 0 at the end of its input, 1 when it fails (with the stack trace in its log), 3 and 4 after a
  battle whose robot data cannot be cleared or whose robot threads outlive it ([Robot data](#robot-data),
  [Robot threads](#robot-threads-that-outlive-their-battle)).
- **Slot** (`queue.Slot`): one JVM with one engine, one battle at a time. Robocode keeps static state in the JVM, so
  battles at once need JVMs of their own.
- The server pushes work to the workers, because it can reach them over ssh while they cannot reach it (the worker's
  Java has no network, and this machine moves). Another worker is a line in `-Pworkers`.

### Workers

`-Pworkers=<name>:<slots>,...` (default `ogi-win:12`):

| Name | Where | Foreign robots |
|---|---|---|
| `local` | this machine, as a local process, in `.robocode/queue/workers/local/` | no: statistics against foreign robots run on the Windows worker |
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
  gives the same version; jobs queued from different working trees never share a jar name. Comments count too: an
  edit of a comment alone gives a new version, whose battles are not in the
  [battle cache](#battle-cache-queuebattlecache). The working tree's own version (`botVersion`) names different code
  after every edit, so the queue does not use it.
- A job is a directory `.robocode/queue/jobs/<id>/`: `job.properties` (as submitted), `opponents.txt`,
  `status.properties` (state, times, order), and what the job writes. `submit` works whether the server runs or not;
  the server picks a job up within seconds. Its jars are copied into `.robocode/queue/jars/`, where a name stands for
  one content.
- Options: those of `compare` (`opponents`, default the lab set; `bounds`, `maxPairs`, `pairsPerOpponent`, `starts`,
  `seed`, `rounds`, `metric`), `note` (shown on the dashboard) and `timeout` (seconds per battle; default 900 for a
  comparison, 300 for a recording, where a hung robot in debug mode holds its slot until then). Options are checked
  before a job is queued: whole numbers in range, `maxPairs` at least the test's burn-in of 2 pairs per opponent, our
  own robots as subjects.
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
  constant and over it; `failed: …`; `lost: …`). A battle in `battles.tsv` with no `ok` line here came from the
  battle cache. `problems.log` as in `compare`.
- The job's health on the dashboard adds the versions' own turn times from the battles of the current server
  run; they are not in the log, so a restarted server starts counting anew.
- Checked on 2026-10-09: a job of 2 pairs per opponent on four local slots gave, battle for battle, the same seeds,
  score shares and shots as the sequential `compare`. On the Windows worker the same job differs in some battles
  (the machines differ in the last bit of some doubles, [build-and-run.md](build-and-run.md#determinism-across-machines)),
  and with 12 slots Ogi skipped a few turns in the short lab battles, so that 5 of 30 pairs of identical code
  differed. A restarted server and a dropped connection both resumed with every pair counted once.

### A melee comparison (`compare.MeleeJob`)

`submit` with a set whose format is `melee` queues a melee comparison: the same command and versions, but each battle
puts the subject among nine opponents of the set, as the melee rumble does. Only the queue runs it; `compare`
refuses melee sets. `recordBattles` and the recording jobs record a melee set's draws.

```sh
./gradlew submit -Pbaseline=HEAD -Popponents=.robocode/foreign/rumble-melee-2026-10.txt -Pbounds=nonregression
./gradlew submit -Pbaseline=HEAD -Popponents=.robocode/foreign/rumble-melee-2026-10.txt -Pdraws=60
```

- **What the rumble measures:** a melee battle is ten robots drawn uniformly from all participants. For every pair of
  robots in it the rumble takes 100·S_a/(S_a + S_b) of their battle totals; a pairing's score is the mean over the
  battles the two shared, and APS the mean over a robot's pairings. So the mean over uniform random fields of the
  subject's mean pairwise share in its battle estimates its APS, with no weights.
- **Draws** (`MeleeDraws`): draw d of the run seed gives nine opponents, the subject's place among the ten robots and
  the battle's seed. Both versions play the same draw, so a draw is a pair, and in later jobs the baseline's battles
  come from the [battle cache](#battle-cache-queuebattlecache) as in 1v1 (the cache key takes the robots in the
  battle's order).
  - A melee set needs a `weight` for each group: the rumble's participants the group stands for (e.g. ranks 1–30,
    31–100, ...). A draw gives each group a quota of the nine places, 9·w_g/W on average: the whole part of it, and
    the places left by systematic sampling over the fractional parts, so a group's count is exact on average and
    varies by one at most. Within a group the opponents are drawn uniformly without repeats.
  - Each participant of a group thus appears with probability 9·w_g/(W·n_g), the rumble's 9/W when the set holds the
    whole group; participants the set lacks (jars denied) are stood in for by the rest of their group.
  - The quotas take out the noise of how many strong opponents a field happens to hold; the estimate stays the
    rumble's.
  - The subject joins the opponents at a random place, since the engine places the robots of a round in index order
    (engine-model G-41).
- **Starts:** random only. The two battles of a draw share round 1's starts and then diverge, because the engine's one
  generator also serves the robots (G-33); fixed starts would repeat one layout in every round, which the rumble does
  not do.
- **Measure and test:** a battle's measure is the subject's mean pairwise share against its nine opponents. The test
  is the sequential test of [Method](#method) on one stratum, the draws (a decision needs 21 draws), on the
  difference new minus old per draw; `-Pdraws=n` instead plays n draws with no test. `bounds`, `maxPairs` (draws),
  `seed`, `rounds`, `timeout` (default 1800 s per battle), `reuse` and `note` work as in 1v1; `metric` is `score`.
- **Scoreboards**, in the shapes of 1v1, so the dashboard shows them alike:
  - *All*: each version's mean measure over the draws, its rumble APS estimate, and the difference with the test's
    interval.
  - One row per group: the pairwise share against the group's opponents over all their appearances, a ratio of sums
    over the draws; its interval treats the draws as clusters, because the opponents of one battle share its luck.
  - *Survival*: the subject's first places in % of the rounds (the melee rumble's second column), per draw.
  - Wins are pairwise (more points than that opponent in the battle). A melee battle does not tell who hit whom, so
    only the overall row has hit rates (the subject's over all its bullets) and none has "hit by".
- **Each opponent:** every opponent that appeared, with its appearances as pairs; a few each, so a diagnostic, not a
  test.
- **Log:** `battles.tsv` (`MeleeLog`): one line per battle with the draw, the side, the seed, the subject's place and
  score components, shots, skipped turns, problems and time, then every opponent's name, score, firsts and skipped
  turns. The
  configuration line adds the draw scheme. A battle whose results lack one of the ten robots is played again, like a
  failed one.

### Recordings as a job (`compare.RecordJob`)

`./gradlew submitGunLogs -Popponents=<set> -Ppairs=2` queues the gun's logs of the working tree (a snapshot's
`OgiGunLog`, keys `gunshot,gunpass,gunshield`; against a melee or a team set the melee gun's keys, and against a team
set five of them as `OgiGunLogTeam`, see [Melee bench](#melee-bench)) against a set, on the seeds of `compare` and `recordBattles`; `gunBench
-Plogs=.robocode/queue/jobs/<id>/recordings/<version>` then replays them here. `./gradlew submitRecordings
-Popponents=<set> -Ppairs=2` queues full recordings of the working tree's `Ogi` (robots, bullets and every debug
property, about 2.3 MB per 1v1 battle; `-Pkeys` keeps only those debug properties and drops robots and bullets). The
job type `record` takes any robot and keys (`queue.Submit`).

```sh
./gradlew submitRecordings -Popponents=.robocode/foreign/<set>.txt -Pdebug=false
./gradlew submitRecordings -Psubject="kc.serpent.Hydra 0.21" -Popponents=.robocode/foreign/<set>.txt -Pdebug=false
./gradlew submitRecordings -PgunLog=true -Popponents=.robocode/foreign/team-strong-2026-10.txt -Ppairs=4 -Pdebug=true
```

`-PgunLog=true` records `OgiGunLog` (five of them against a team set) instead of `Ogi`: the full recording then holds
its logs too, such as the melee movement's decisions for the [position bench](#position-bench).

- **The subject** is the working tree's `Ogi`, shipped with the job, or with `-Psubject="<robot> <version>"` a robot
  the worker has: no jar is shipped, and the server checks that the worker lists the subject, as it checks the
  opponents ("robots missing on …"). A foreign subject needs a worker that may run foreign robots and runs in the
  sandbox like an opponent. `Submit` refuses a subject in our or the sample packages without its jar, and a subject
  that is in the opponent set (both robots would get the same name).
- Each battle runs on any free slot; the slot records it into a temporary file and sends it back in Base64 inside
  its result, so the worker keeps nothing. The job writes `recordings/<dir>/<opponent>_p<pair>.rec.gz` and a line
  to `recordings/<dir>/battles.tsv`; `<dir>` is the version for a shipped subject and `<robot>_<version>` for one
  from the worker (`kc.serpent.Hydra_0.21`).
- **Index columns** (`RecordJob.INDEX_COLUMNS`): the columns of `recordBattles` (file, opponent, pair, seed, the
  subject's bullets fired and hits, skipped turns, problems), the opponent's skipped turns, our robot's own turn
  times, whether the battle ran in debug mode, the subject's score components (score, survival, last survivor bonus,
  bullet damage and bonus, ram damage and bonus, rounds won), the opponent's, the opponent's bullets fired and hits,
  the battle's wall time and the turns of every round (comma-separated, from the recording's `end` records). Shots
  count as in [Shots](#how-results-are-gathered).
- Battles go out pair by pair over all opponents, so a job stopped early covers every opponent alike. A recording
  that exists is not played again: a restarted server goes on where it stopped.
- **Against a melee set** a job records draws instead (`-Pdraws=n`, the fields of a
  [melee comparison](#a-melee-comparison-comparemeleejob) with the same seed) into `recordings/<dir>/draw<d>.rec.gz`.
  Its index has columns of its own (`RecordJob.MELEE_INDEX_COLUMNS`): the file, the draw, the seed, the subject's
  place among the ten, its bullets fired and hits, skipped turns, problems, the opponents' skipped turns, its own
  turn times, debug mode, its score components, its mean pairwise share against the nine (the battle's measure in a
  melee comparison), the wall time, the turns of every round and the opponents, separated by ` | `.
  `submitGunLogs` against a melee set records the melee gun's log ([Melee bench](#melee-bench)).
  - **Without debug mode by default** (`-Pdebug=true` asks for it): in debug mode a robot whose thread never stops
    (SHAM.WOW 1.4) holds its battle until the timeout, because the engine waits for every turn, and among ten robots
    such a robot is common (2026-10-15: it held two jobs for 10 and 20 minutes, and comparisons waited behind them,
    since a worker plays one mode at a time). Without debug mode a turn Ogi skips can lose that turn's log; the
    melee bench leaves such battles out of its parity check (Ogi skipped no turn in 100 draws of a comparison).
  - A melee battle's default timeout is 600 s; the longest of 199 draws took 185 s in debug mode.
- **Against a team set** the subject is the working tree's `OgiTeam` (or a foreign team with `-Psubject`), and the
  battles are pairs as against a 1v1 set, with the same index columns; the subject's numbers are its team's (the
  runner names a team's result after the team). Like melee draws they run without debug mode and with a 600 s
  timeout by default: ten robots, most of them foreign.
- **In debug mode by default**, like `recordBattles` (engine-model G-17): a battle can ask for it
  (`BattleSpec.debug`), and the executor restarts an idle slot with `-Ddebug=true`, because the engine reads the flag
  once per JVM. A job's battles all want the same mode, and a worker plays one mode at a time: while battles of one
  mode are under way on it, jobs of the other mode wait (mixing them made the slots switch back and forth). Without
  debug mode a robot that skips a turn can lose that turn's debug properties (its late commands are replaced, as in
  engine-model G-34): on 2026-10-09 the gun bench's parity failed against 4 of 125 opponents, each one where Ogi had
  skipped turns (141 of 2.4 million decisions, one wave without its plan).
- **`-Pdebug=false`** records under the turn time limit instead (the slots' CPU constant, as in a comparison). Use it
  to see how robots play as they would in a rumble: in debug mode the engine waits up to 600 s for each robot's turn
  (`Battle.wakeupSerial`), so a heavy robot never skips a turn. Debug properties of skipped turns can be lost then,
  so the gun's logs keep debug mode.

### Battle cache (`queue.BattleCache`)

The server keeps every battle a comparison played in `<state>/battle-cache.jsonl`, and a comparison takes a battle
from there instead of playing it again. After A vs B, a comparison of B with C plays only C's battles: a pair's seed
comes from the run seed, the opponent and the pair's number, so B meets the same opponents on the same seeds.

- **The same battle:** the key's version (2 since every battle starts with empty robot data, see
  [Robot data](#robot-data); battles of version 1 stay in the file and are no longer used), the field, the rounds,
  the seed, the start positions, each participant's name and the SHA-256 of its jar (ours from the job, the opponents' as each slot resolves them, `BattleRunner.repositoryJars`; "builtin"
  for Robocode's own robots), and the worker with its operating system, Java, Robocode, JVM options, CPU factor and
  sandbox. The CPU constant is left out: the executor measures it once and keeps it (`state/cpu-constant.properties`),
  so it is the same for every battle; after measuring it again, raise `BattleCache.VERSION`. A harness change that
  alters battles raises it too (version 2: robot data cleared before every battle).
- **Jobs that run at once** share battles: a job claims a battle before it plays it, and another job that needs the
  same battle waits for that outcome (counted as reused); if it does not come back, the waiting job plays it. Claims
  live in memory only.
- **Never cached:** a battle against an opponent whose jar the worker did not report, recorded battles and battles in
  debug mode.
- A cached outcome is one sample of its battle, not a replay of it (robots that skip turns do not repeat bit for bit),
  so it serves as well as a new one. `-Preuse=false` plays every battle anew and neither reads nor writes the cache;
  another `-Pseed` gives other battles.
- The job counts reused battles (`reusedBattles`, shown on the dashboard); its rate and ETA count only battles played.

### Dashboard

`http://localhost:8765/` (`-Pport`), on 127.0.0.1 and ::1 only (another program, e.g. a Docker port, may hold the
port on one of them; the server then stops with an error).

It is written for a human watching the queue: words first, numbers in tooltips and in "Technical details". It
refreshes every 2 s and changes only the parts of the page whose content changed, so an opened section, a text
selection, a tooltip and a table's scroll position stay wherever the content stays the same.

- **Workers:** state (offline, connecting, starting, ready, backoff, stopping), the slots (blue: playing a battle),
  battles over all jobs (those since the server started in a tooltip) and battles per second, failures and slot
  restarts, the last error with its time (grey once the worker has brought a battle back, run or stopped normally
  since); under "more" the CPU constant, Java and JVM options and the battles running.
- **Totals, under the workers** (`queue.BattleTotals`): every battle a worker played to its end over all the jobs on
  disk, with its slot hours; per battle format (the set's `format` line) on the format's tab and, in its tooltip,
  per kind of job (comparisons, recordings); a worker's card shows its own share. The server reads the jobs' `dispatch.tsv` once at
  its start and adds each battle it logs there, so a restart does not reset them. A battle taken from the [battle cache](#battle-cache-queuebattlecache) is
  in no dispatch log and counts only in the job that played it; a deleted job takes its battles with it.
- **A tab per battle format** (1v1, melee, team; the format of the job's opponent set, kept in the URL): the lists
  below show that format's jobs only, and each tab says how many of its jobs run or wait. The queue itself is one:
  jobs of all formats wait in one order and share the workers' slots.
- **Jobs, on the left:** running and waiting, then the last 20 finished ones (all of them after "Show all"). Each
  shows its note (`-Pnote`) as its title, the test and the opponents in words (the set's `title`), and the result in
  words: "Better by" or "Worse by" only when the whole 95 % interval of the estimate says so, otherwise "No clear
  difference"; the card is tinted green, red or grey to match. A running job shows the evidence as a marker between
  the test's two answers (the LLR between its bounds), its progress and the time left; a finished one how the test
  ended. Buttons move a waiting job up or down or cancel it.
- **The job on the right:** the clicked one (kept in the URL), else the running one of the tab's format. The result and the test in
  words, the scoreboards (all, the rumble estimate if the set has weights, strong, every other named group of the
  set: old and new score share, the change with its interval, how often they hit us, how often we hit them, wins),
  each opponent (worst change first; a click on a column's name sorts by it, a second click reverses; the table
  scrolls on its own and keeps its head in view) and the technical details (job id, the job as submitted, the
  decision, LLR, noise, health, own turns, the directory, the workers' environment, problems, the last battles).
- **Statistics** (`/stats`, `queue.Statistics`, linked from the header): per format the jobs submitted (comparisons
  and recordings, by state), the battles played with their slot hours and the battles taken from the cache, and a
  chart of APS against the battles it took. Each point of the chart is a comparison that ended normally and whose
  set measured the rumble's APS; its place on the horizontal axis is every battle the workers had played in the
  format by the job's end (subsets and recordings included), or the slot hours, or the date. The line follows the
  version the jobs compared against as it changed (a job that went back to an older version is a lighter dot off the
  line); rings are the new versions. A point opens its job on the dashboard.
  - **Which sets measure the rumble:** a set stands for as many robots of the rumble as its weight lines add up to,
    or without weights as it has opponents (`OpponentSet.standsFor`). The largest number among the format's jobs is
    taken as the whole rumble, and a set that stands for at least 99 % of it counts: the whole rumble, or a sample
    whose groups weigh as their tiers (`pop-1v1-2026-10`). A subset stands for less (the strong and medium tiers stand
    for 300 of the 1v1 rumble's 1216, a set without its top 30 for 1186), so its "rumble estimate" is that subset's
    mix and is left out. A newer ranking with a few more robots keeps the older sets in; one with more than 1 % more
    would push them out, and the rule then needs another look. The page lists every set with its verdict.
  - The APS is the scoreboard that estimates the rumble's: the rumble estimate of a weighted set, otherwise all
    opponents (a team set weighs every team the same; a melee comparison's draws give every group its share).
  - The jobs' sets and summaries are read on the first request (about half a second for 400 jobs), outside the
    server's lock, and kept: a job that has ended does not change.
- `./gradlew queueStatus` prints the same in a terminal; actions are POSTs
  (`/api/action?action=cancel|up|down&id=<job>`) that need the header `X-Requested-With: ogi`, so another web page
  cannot trigger them.

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
- **Jobs that reuse the same cached baseline share its luck.** Their differences per opponent move together (two
  candidates at 4 pairs per opponent: correlation 0.53, 2026-10-11), so two such jobs that both look better are less
  evidence than two independent runs. To choose between two candidates, compare them with each other.
- A team jar lists its robots in the repository under the same name and version as the robot jar
  (engine-model G-42). `BattleRunner` picks the jar named after the robot.
- `BattleResults.getRank()` in melee does not always follow the score order (engine-model G-31). Do not rely on it
  in new checks; rank by score.
- The output-based skipped-turn detection depends on the engine's message text (`BattleRunner.skippedTurn`). Our
  robots now report their own turn times, but skipped turns, of every robot, still come from the text.
- The engine swallows exceptions thrown by battle listeners. `TurnRecorder` keeps the first failure and rethrows it
  from `close()`.

