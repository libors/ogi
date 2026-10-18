# Movement explorer

Interactive pages of the melee movement (`cz.libors.ogi.move.MeleeMovement`, [bot.md](../../docs/bot.md#melee-movement-meleemovement)):
the risk of every point as a map, this turn's candidates, the choice against the waves in the air, the team pull, and
scenarios with what to look at in each. Robots, waves and bullets can be dragged, added and removed, the parameters
changed live, and *Play* runs the decision turn by turn.

- `team.html`: the TeamRumble's field (1200 × 1200) with teammates, the gang defence, helpers and the finisher.
- `melee.html`: the melee rumble's field (1000 × 1000), our robot among enemies.

Open a page in a browser (`open tools/movement-explorer/team.html`): no build, no server, no dependencies.
`#<scenario>` opens a scenario, `#<scenario>:<turns>` also plays that many turns (`team.html#helper:250`).

## Files

- `model.js`: the decision in JavaScript: the candidates, the risk, the pull and the roles, the choice among the
  candidates (`choose`, as `MeleeMovement.choose`), and a simplified wave damage. `MeleeMovementParameters.DEFAULT` is
  `P`, the constants of `MeleeMovement` and `EnemyWaves` are `C`.
- `explorer.js`, `explorer.css`: the page; each HTML file sets its mode, field and scenarios.
- `parity.js`: checks `model.js` against the bot's logged decisions (below).

## What is exact

The candidates' rule, the risk, the pull and the roles, and the choice among the 8 candidates with the least risk are
the bot's. Simplified: an enemy wave's aim is a normal distribution around a drawn line instead of the shooter's
danger model; the path prediction drives straight without turning; the aim chance uses the robots' current
positions; in *Play* the enemies stand or follow one of ours and nobody fires. The teammates' and the history's
distance exponents are parameters of the page only (the bot has 2). Each page lists this under *What this page
simplifies*.

## Keeping it in step with the bot

When `MeleeMovement`, `MeleeMovementParameters` or the constants the choice uses change, change `model.js` the same
way, then check it on recordings of the changed bot:

```sh
./gradlew submitRecordings -PgunLog=true -Popponents=.robocode/foreign/team-strong-2026-10.txt -Ppairs=4 -Pdebug=true
node tools/movement-explorer/parity.js .robocode/queue/jobs/<id>/recordings/<version> [key=value ...]
```

It replays every logged decision (the `meleemove` debug property of `OgiGunLogTeam`, as the
[position bench](../../docs/harness.md#position-bench) reads it) through `model.js` and needs the logged destination
in every one. The candidates and their waves' damage come from the log, so the random draws do not matter; the
history is replayed from the logged positions. `key=value` sets a parameter to what the recorded bot played with.
It exits with 1 on any difference and prints the first ones.

On 2026-10-18: 332,881 of 332,881 decisions on 48 recordings of `d9b2fa4`'s play (`finishDistance=0`, before the
finisher), among them 5,922 decisions of a ganged robot and 9,965 of a helper. Not covered: the candidates' random
draws, the waves' damage, the melee page's aim chance (only team battles log decisions) and the finisher (no
recording with it yet).
