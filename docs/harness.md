# Harness

The harness runs battles headless on the stock Robocode engine through its control API and reports structured
results. Every score claim about the bot must come from here.

## Components (`harness/src/main/java/cz/libors/harness/`)

| Class | Role |
|---|---|
| `BattleSetup` | A battle: label, field size, rounds, contestants (`sample.Walls`, `cz.libors.Ogi 0.1`, teams by name), seed |
| `BattleRunner` | Owns one `RobocodeEngine`; resolves contestants from the local repository, seeds the battle and runs it synchronously, optionally with extra listeners |
| `BattleOutcome` | Results sorted by score, the seed used, skipped turns per robot, problems (engine errors, exceptions in robot output), elapsed time |
| `ContestantResult` | Score components per contestant: total, survival, last survivor bonus, bullet damage and bonus, ram damage and bonus, firsts |
| `SkeletonCheck` | Runs a fixed set of checks and writes `harness/build/skeleton-check.json` |
| `ProbeRecordings` | Records probe battles and checks their integrity, coverage and determinism; see [Recordings](#recordings) |
| `recording.TurnRecorder` | Battle listener that writes a recording: every robot and bullet in every turn, plus debug properties |
| `recording.Recording` | Reads a recording back into memory; no Robocode classes, so tests can use it without an engine |
| `Json` | Minimal JSON writer for records, lists and maps |

Differential tests of the bot's engine model live in the `engineTest` source set (`harness/src/engineTest/`) and run
with `./gradlew engineTest`, after `recordProbes`. They read the recordings through `Recording` and need no engine.

The probe robots live in their own module, `probe/` (package `cz.libors.probe`). They are packaged as
`cz.libors.probe_<v>.jar` and deployed only into the local Robocode installation, never to a rumble.

## How results are gathered

- **Score components** come from `BattleCompletedEvent.getSortedResults()`.
- **Skipped turns** are counted from each robot's per-turn console output, where the engine writes "skipped turn".
- **Problems:** output lines containing `Exception` or `Error` are recorded with round and tick; so are
  `BattleErrorEvent`s.
- **Matching:** a contestant is matched by name prefix. Team results are named after the team plus an index, e.g.
  `cz.libors.OgiTeam 0.1 (1)`.

## Seeds and determinism

- **Every battle is seeded.** `BattleRunner` calls `RandomFactory.resetDeterministic(seed)` before each battle,
  with the setup's seed or a fresh one. `BattleOutcome.seed()` reports it, so any battle can be rerun.
- **What the seed controls:** the single random generator shared by the harness, the engine (start positions, the
  order in which robots and bullets are processed) and robots that call `Utils.getRandom()` (engine-model G-33).
- **What it does not control:** `Math.random()` and robots' own unseeded generators (G-26). A battle with such a
  robot does not replay. Ogi's movement uses `new Random()` today, so Ogi battles do not replay yet.
- **A robot's draws from `Utils.getRandom()` shift the engine's later draws**, e.g. the next round's start
  positions. Two bot versions compared on the same seed only share start positions as long as they draw the same
  numbers from it.
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
| `MathRandomProbe` | `Math.random()` | shows that a seed does not control `Math.random()` |
| `UtilsRandomProbe` | `Utils.getRandom()` | shows that a seed controls `Utils.getRandom()` |
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
| Shared generator | `RandomProbe` and `UtilsDrawProbe` with one seed start round 1 at the same positions and round 2 elsewhere |

Scenarios: `duel` (2 probes, 800×600, 3 rounds), `crowd` (6 probes, 400×400, 3 rounds, many collisions) and
`melee` (10 probes, 1000×1000, 2 rounds), each with seeds 1, 2 and 3. The task also prints how often the edge cases
occurred (wall hits, robot collisions, velocity sign flips, stops, bullet outcomes, deaths, disabled turns, events)
and writes everything to `harness/build/recordings/summary.json`.

## Skeleton check

| Battles | Field | Rounds | Pass condition |
|---|---|---|---|
| 1v1 against each of 13 sample bots | 800×600 | 10 | score share ≥ 75 %, rank 1 |
| Melee against 9 sample bots | 1000×1000 | 10 | rank 1 |
| `cz.libors.OgiTeam` vs `sampleteam.MyFirstTeam` | 1200×1200 | 10 | score share ≥ 70 % |

All checks also require zero skipped turns and no problems. The process exits non-zero on any failure.

## Known quirks

- `BattleResults.getRank()` in melee does not always follow the score order (engine-model G-31). Do not rely on it
  in new checks; rank by score.
- The output-based skipped-turn detection depends on the engine's message text. Replace it with an explicit signal
  when the bot reports its own timing.
- The engine swallows exceptions thrown by battle listeners. `TurnRecorder` keeps the first failure and rethrows it
  from `close()`.

## Planned

- Paired, sequential comparison of two bot versions against a fixed opponent population, with stopping rules.
- A job queue so a remote machine can run battle batches.
