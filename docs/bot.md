# The bot

How `cz.libors.Ogi` decides, turn by turn. The engine rules it relies on are in [engine-model.md](engine-model.md),
enemy fire detection in [fire-detection.md](fire-detection.md), and how to measure a change in
[harness.md](harness.md#comparing-two-versions).

## A turn

Robocode delivers a turn's events before the robot acts, highest priority first, so hits and rams (20–55) arrive
before scans (10), and painting (5) comes last (engine-model G-40). Then `act()` runs:

1. **Our state** goes to the surfer, and the enemy waves advance to our position at the end of this turn.
2. **Target:** the closest enemy seen in the last 8 turns, from our scans or a teammate's.
3. **Radar:** locked on the target in 1v1, spinning otherwise.
4. **Movement:** the surfer's commands while an enemy wave is in the air, otherwise orbiting the target
   (`OrbitMovement`). From these commands `RobotMotion` predicts our position at the end of the next turn, exactly
   unless we ram someone.
5. **Fire:** last turn's plan is fired if the gun is cool and has reached the planned heading.
6. **Gun waves** advance; last turn's plan becomes this turn's wave.
7. **Aim:** the plan for the next turn's shot, from the predicted position.

## Waves and hit ranges (`cz.libors.ogi.wave`)

- **`Wave`:** every bullet a robot could have fired in one turn. It leaves from the shooter's position at the end of
  the fire turn and has travelled `(t − fireTime) × speed` by the end of turn t.
- **`HitRange`:** the headings at which a wave's bullet hits a robot, accumulated turn by turn while the wave passes
  it ("precise intersection"). Each turn it takes the box's part between the bullet's two radii, as seen from the
  origin, and removes the headings whose move ends within 3 px of a wall, because the engine tests walls first.
  - Angles are relative to a reference, normally the bearing to the robot at fire time.
  - Tested against single bullets flown with the engine's arithmetic (`HitRangeTest`) and against every recorded
    bullet of the probe battles (`HitRangeReplayTest`): no disagreement.

## Gun (`cz.libors.ogi.gun`)

- **Timing:** the gun fires along the heading it reached in the turn of the fire command (engine-model G-01). So the
  aim for turn t+1's shot is chosen in turn t, from our predicted position at the end of t+1 and the target's scan of
  t. That plan becomes turn t+1's wave, fired or not, with the same reference.
- **Data:** every turn has a wave. When it has passed the target, its hit range in guess factors and the shot's
  features (`TargetingFeatures`) are stored per enemy (`GunData`). A wave with a real bullet weighs 1, a virtual one
  0.5. The data lasts for the battle (a static map; statics are per robot, engine-model G-22).
- **Features:** bullet flight time, lateral and advancing velocity, acceleration, turns since the last reversal and
  the last deceleration, room to the wall ahead and behind (in escape angles), distance moved over 10 turns, and how
  the path curves.
- **Aim:** the nearest √n stored shots (at most 50), weighted by distance, vote with their whole hit ranges; the gun
  aims at the guess factor most weight covers. The search runs only once the gun heat is at most 4 cooling steps:
  the 4 turns before the gun is cool and the turns it stays cool.
- **Virtual guns:** every plan also holds the circular aim (`CircularAim`). A passed wave scores both guns only if
  the guess-factor gun searched for its plan (at power 1.9, 5 waves in 14): would each aim have been inside the hit
  range? Per enemy, the gun with the better weighted record fires; circular targeting until the guess-factor gun
  has 20 weighted tries (a real bullet counts 1, a virtual wave 0.5).
- **One model for the bot and the bench:** what the gun knows about an enemy and how it decides (the data, the
  virtual scores, the choice of gun, learning a passed wave) is `GunModel`. The tunable numbers (feature weights,
  the neighbour count, the virtual weight, the tries before switching) are `GunParameters`; the bot plays with
  `GunParameters.DEFAULT`. `GuessFactorGun` computes the features and the hit ranges and hands them to the model, so
  the offline gun bench can replay a battle through the same code ([harness.md](harness.md#gun-bench)).

## Movement (`cz.libors.ogi.move`)

- **Enemy waves** come from `FireDetector` (per enemy and round). A wave's frame is the bearing from its origin to
  where we were at the end of the turn before the shot, and our lateral direction then.
- **Danger** (`DangerStats`, per enemy, for the battle): a smoothed histogram of the guess factors at which the enemy's
  bullets passed us, learned from bullets that hit us and bullets one of ours hit. It starts with all weight at guess
  factor 0, a gun aiming straight at us.
- **A dead enemy's waves stay** until they pass us: its bullets keep flying (engine-model G-44).
- **Surfing** (`WaveSurfer`): for the wave that reaches us first, three ways to move are predicted with
  `RobotMotion` until the wave has passed: orbiting its origin either way, or stopping. The orbit steers towards
  400 px, and drives backwards rather than turning around. Where a wall is ahead it turns away from the wave's origin
  until there is room, so near walls it drifts outwards and stays further than 400 px. `HitRange` gives the guess
  factors that would hit us on each path; the way with the least danger wins, and its first turn's commands are used.
  Ties keep the last way.
- **Without an enemy wave** the bot orbits the target and changes direction at random intervals, from its own seeded
  generator. It also steers towards 400 px, but turns towards the target at walls, so it stays closer than the surfer.
- **The distance moves the gun's hit rate:** a longer flight gives the target more room, so a movement change shows
  up in `-Pmetric=hits` too ([harness.md](harness.md#known-quirks)).

## Debug overlay (`cz.libors.ogi.trace`)

- **`Trace`** holds what the bot decided this turn, as consumers see it; the gun and the surfer describe themselves
  into it. **`Overlay`** is the one place that paints it.
- **Robocode mirrors robot graphics** to battlefield coordinates (y up), so points in the mathematical convention are
  drawn as they are; arcs are polylines, never `Arc2D`.
- **What is drawn:** our gun waves (grey, a fired one orange) with their hit ranges on the target (green), enemy waves
  (red) with their hit range on us (yellow), the gun's reference and escape angles, each gun's aim (guess factor
  green, circular blue; the chosen one thick), and per target the guns' virtual hit rates and the data size.
- **To see it:** start the GUI (`cd .robocode/1.11.1 && ./robocode.sh`), run a battle with `cz.libors.Ogi`, open its
  console from the robot buttons on the right and press "Paint". Robocode paints before the robot acts, so the plan on
  screen is the shot of the current turn.
- **For the harness:** every turn with a target the bot sets the debug property `gun` (`Trace.gunLine`): the turn
  of the plan, whether last turn's plan was fired in this turn, the chosen gun, each gun's aim, the planned origin,
  the reference, the direction, the power, the data size, each gun's virtual hit rate and the target. A recording
  keeps it ([harness.md](harness.md#recording-comparison-battles)).
- **For the gun bench:** `cz.libors.OgiGunLog` is Ogi that also logs its gun's inputs and decisions (`GunLog`): one
  `gunpass` property per gun wave that passed its target in this turn (the plan's turn, whether it carried a bullet,
  the wave's reference and escape angle, the hitting headings), then `gunshot` with the turn's plan (the features,
  the direction, the reference, the escape angle, whether the guess-factor gun searched, the circular aim, the guess
  factor found and the chosen gun). That is everything `GunModel` needs to replay the battle.
  - `OgiGunLog` also logs `gunshield` when an enemy bullet destroys one of its bullets (the plan's turn and the
    shooter): Ogi hands every bullet it fires to the hook `fired(Bullet, Plan)`, which only `OgiGunLog` uses. Robots
    that shoot bullets down (DrussGT, Saguaro, Roborio) would otherwise look easy to hit on the bench.
  - Only `OgiGunLog` logs, and its jar (`bot/build/robots/cz.libors.OgiGunLog_<v>.jar`) never goes to a rumble; the
    rumble jars leave the class out. Formatting the log costs 0.1–0.25 ms per turn while the JVM is cold, and with it
    Ogi skipped a turn in the first battle of every `skeletonCheck` run (2026-10-09).
- **Without the GUI:** `OverlayRenderTest` paints a simulated fight into `bot/build/overlay/gun.png`. The image is
  mirrored by hand, so its text is upside down.
- **Own turn time (P-04, `TurnClock`):** the bot measures the wall time from its first event of a turn (`onStatus`,
  which the engine delivers before every other event) to its `execute()`, and reports the previous turn's time in µs
  as the debug property `cpu`. The engine gives that part of the turn the CPU constant (and up to a millisecond more,
  engine-model G-45). The harness compares it with the constant of an idle machine
  ([harness.md](harness.md#how-results-are-gathered)). Measured on the Mac (2026-10-09, lab opponents): median
  6–13 µs, 99th percentile ~80 µs, the longest turns 0.7–1.7 ms at a battle's start (the JIT) and in garbage
  collections.
