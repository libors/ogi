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
| `Json` | Minimal JSON writer for records, lists and maps |
| `compare.Compare` | Paired, sequential comparison of two bot versions; see [Comparing two versions](#comparing-two-versions) |
| `compare.Sprt`, `compare.Stratum` | The stratified sequential test and the per-opponent paired statistics |
| `compare.OpponentSet`, `compare.CompareLog` | The opponent set file, and the battle log that makes a comparison resumable |
| `compare.RecordBattles` | Records the battles of a comparison turn by turn; see [Recording comparison battles](#recording-comparison-battles) |

Differential tests of the bot's engine model live in the `engineTest` source set (`harness/src/engineTest/`) and run
with `./gradlew engineTest`, after `recordProbes`. They read the recordings through `Recording` and need no engine.
The same source set measures enemy fire detection (`FireDetectionTest`, see [fire-detection.md](fire-detection.md)).

The probe robots live in their own module, `probe/` (package `cz.libors.probe`). They are packaged as
`cz.libors.probe_<v>.jar` and deployed only into the local Robocode installation, never to a rumble.

## How results are gathered

- **Score components** come from `BattleCompletedEvent.getSortedResults()`.
- **Skipped turns** are counted from each robot's per-turn console output, where the engine writes "skipped turn".
- **Problems:** output lines containing `Exception` or `Error` are recorded with round and tick; so are
  `BattleErrorEvent`s.
- **Shots:** every bullet in the turn snapshots counts once as fired by its owner's contestant, and once as a hit when
  it hits a robot of another contestant (friendly fire does not count).
  - Only bullets that end while the round is open count. In the turn in which one contestant is left, the bullets
    still in the air are taken back, and bullets fired later are ignored. They can no longer hit an enemy, and
    without this rule a contestant that fights from further away would show a lower hit rate with the same aim,
    because more of its bullets are in flight at the kill (5–16 % of Ogi's bullets against the lab set).
  - Hit rates are therefore "hits per bullet that could still hit". A stationary target that is hit every time
    shows 100 %.
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
| Replay | the first seed of each scenario, run again, gives the same digest (SHA-256 of the uncompressed text) |
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

All checks also require zero skipped turns and no problems. The process exits non-zero on any failure.

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
| `only` | all | comma-separated opponents of the set to record |
| `pairs` | 1 | pairs per opponent, from pair 0 |
| `seed`, `rounds`, `starts` | as in `compare` | |
| `out` | `harness/build/battle-recordings` | writes `<out>/<version>/<opponent>_p<pair>.rec.gz` |

The recordings have the format above. Ogi sets the debug property `gun` every turn it has a target, with the plan
for the next turn's shot (see [bot.md](bot.md#debug-overlay-czliborsogitrace)), so a bullet first recorded in turn
f was fired from the plan of turn f − 2, whose line in turn f − 1 says it was fired.

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
- The output-based skipped-turn detection depends on the engine's message text. Replace it with an explicit signal
  when the bot reports its own timing.
- The engine swallows exceptions thrown by battle listeners. `TurnRecorder` keeps the first failure and rethrows it
  from `close()`.

## Planned

- A job queue so a remote machine can run battle batches.
