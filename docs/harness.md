# Harness

The harness runs battles headless on the stock Robocode engine through its control API and reports structured
results. Every score claim about the bot must come from here.

## Components (`harness/src/main/java/cz/libors/harness/`)

| Class | Role |
|---|---|
| `BattleSetup` | A battle: label, field size, rounds, contestants (`sample.Walls`, `cz.libors.Ogi 0.1`, teams by name) |
| `BattleRunner` | Owns one `RobocodeEngine`; resolves contestants from the local repository and runs a battle synchronously |
| `BattleOutcome` | Results sorted by score, skipped turns per robot, problems (engine errors, exceptions in robot output), elapsed time |
| `ContestantResult` | Score components per contestant: total, survival, last survivor bonus, bullet damage and bonus, ram damage and bonus, firsts |
| `SkeletonCheck` | Runs a fixed set of checks and writes `harness/build/skeleton-check.json` |
| `Json` | Minimal JSON writer for records, lists and maps |

## How results are gathered

- **Score components** come from `BattleCompletedEvent.getSortedResults()`.
- **Skipped turns** are counted from each robot's per-turn console output, where the engine writes "skipped turn".
- **Problems:** output lines containing `Exception` or `Error` are recorded with round and tick; so are
  `BattleErrorEvent`s.
- **Matching:** a contestant is matched by name prefix. Team results are named after the team plus an index, e.g.
  `cz.libors.OgiTeam 0.1 (1)`.

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

## Planned

- Paired, sequential comparison of two bot versions against a fixed opponent population, with stopping rules.
- Recording per-tick ground truth (robots and bullets) for the engine-model differential tests.
- A job queue so a remote machine can run battle batches.
