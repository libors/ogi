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
4. **Movement:** the surfer's commands while an enemy wave is in the air; otherwise the surfer's ways around a target
   that drives at us (`WaveSurfer.evade`), or orbiting the target (`OrbitMovement`). A disabled target in a duel
   (energy 0: it can neither fire nor move) is rammed instead, once none of its waves is in the air, and not shot at: a ram kill adds 30 % of all the damage we dealt it this round,
   a bullet kill 20 % ([engine-model.md](engine-model.md) G-46). From these commands `RobotMotion` predicts our position at the end of the next turn, exactly
   unless we ram someone.
5. **Fire:** last turn's plan is fired if the gun is cool and has reached the planned heading; the surfer follows
   the bullet for its shadows on the enemy waves (from the next turn's move on).
6. **Gun waves** advance; last turn's plan becomes this turn's wave.
7. **Aim:** the plan for the next turn's shot, from the predicted position, with the turret's random spread.

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
  0.125. The data lasts for the battle (a static map; statics are per robot, engine-model G-22).
- **Features:** bullet flight time, lateral and advancing velocity, acceleration, turns since the last reversal and
  the last deceleration, room to the wall ahead and behind (in escape angles), distance moved over 10 turns, and how
  the path curves. Flight time weighs most; turns since the last reversal barely count, and the wall behind and the
  curve have weight 0 (tuned on the gun bench, [harness.md](harness.md#gun-bench)).
- **Aim:** the nearest √n stored shots (at most 100), weighted by distance, vote with their whole hit ranges; the gun
  aims at the guess factor most weight covers. The search runs only once the gun heat is at most 4 cooling steps:
  the 4 turns before the gun is cool and the turns it stays cool.
- **Virtual guns:** every plan also holds the circular aim (`CircularAim`). A passed wave scores both guns only if
  the guess-factor gun searched for its plan (at power 1.9, 4 waves in 14: in the turn it fires, the gun heat already
  includes the new shot): would each aim have been inside the hit
  range? Per enemy, the gun with the better weighted record fires (a real bullet counts 1, a virtual wave 0.125);
  circular targeting until the guess-factor gun has a record.
- **Anti-surfer gun:** a third gun with the same features and weights over the waves of our bullets only, in three
  views: the latest 30 and 150 bullets and all of them. A search takes the 4 nearest shots of each view and aims at
  the guess factor most of their summed kernel weight covers. A surfer dodges our bullets, not our waves, and keeps
  going where they missed it until one hits it there; the short views follow that, the long ones keep the rest. It
  replaces the chosen gun when the enemy is hard to hit (the guess-factor gun's record over our bullets is not
  clearly above 20 %: one standard error below the record, the Wilson lower bound, is below 20 %), it has been scored
  on 30 bullets and its record over them is the better one. Our bullets an enemy bullet shot down count as virtual
  waves: they never reached the target. Its weights are a fixed copy of the main gun's; the gun bench does not tune
  them.
- **Shadow shots:** in a duel, in the turn before a shot of the anti-surfer gun, its peaks with at least 70 % of the
  best one's weight compete with its aim (at most three, 0.15 guess factors apart, and only those the turret can
  reach in one turn, 20°): the turret takes the heading
  whose bullet leaves the surfer the least danger, that bullet's shadows on the enemy waves counted
  (`WaveSurfer.dangerWithShot`), and the gun's own aim on a tie. A turret-only change, like the aim spread; the guns
  learn from their own aims.
- **Aim spread:** the turret aims at the chosen gun's aim plus a random offset of up to ±4 px at the target's
  distance, drawn every turn from a seeded generator of its own (`Plan.offset`). The guns learn and are scored on
  their own aims. Against a robot that stands still the gun would otherwise aim exactly at its centre, which a bullet
  shielder predicts to a fraction of a pixel and shoots down; a few pixels off the centre hit as often.
- **One model for the bot and the bench:** what the gun knows about an enemy and how it decides (the data, the
  virtual scores, the choice of gun, learning a passed wave) is `GunModel`. The tunable numbers (feature weights,
  the neighbour count, the virtual weight, the tries before switching) are `GunParameters`; the bot plays with
  `GunParameters.DEFAULT`. `GuessFactorGun` computes the features and the hit ranges and hands them to the model, so
  the offline gun bench can replay a battle through the same code ([harness.md](harness.md#gun-bench)).

## Movement (`cz.libors.ogi.move`)

- **Enemy waves** come from `FireDetector` (per enemy and round). A wave's frame is the bearing from its origin to
  where we were at the end of the turn before the shot, and our lateral direction then.
- **Danger** (`DangerModel`, per enemy, for the battle): where the enemy's bullets go, learned from the bullets that
  hit us and those one of ours hit. Each is stored with the guess factor it passed us at and with how we moved when
  the enemy aimed: the gun's `TargetingFeatures` of our own `RobotHistory` as it was at the end of the turn before the
  shot. A new wave gets a profile over 47 guess-factor bins:
  - a smoothed histogram of the bullets fired in the most similar situations (KNN, about 1.4·√n of them, at most 18),
    whose share
    grows with the data (`knnRamp`), so that guns whose aim follows our velocity become predictable;
  - the rest a histogram of all bullets plus a prior of a gun aiming straight at us (guess factor 0).
  - **An ensemble:** that data profile is one estimator. The others are seven simple guns (`SimpleAims`: head-on,
    linear with and without the walls, circular, linear with our lateral velocity averaged over the last 20 and 40
    turns, and a pattern match on our lateral velocity; each aimed from the enemy's scanned position and our state of
    the turn it aimed), each a narrow kernel at its aim, and a flattener: a KNN over where we ourselves were when this
    enemy's waves passed us (`DangerModel.visit`, the middle of the wave's hit range on us; every wave, also those
    whose bullet hit us or one of ours, which the surfer no longer surfs but follows until they pass), which is where
    a gun that learns our movement aims. Every observed bullet scores each estimator by the share it gave the
    bullet's bin; the weights go with the prior times `exp(temper · record)`, the record forgetting a part per bullet (`decay`). A simple
    gun the enemy does not use loses its weight within a few bullets, so it costs no room against learning guns.
  - **Lateral history** (`LateralHistory`, per enemy, for the battle): our lateral velocity as that enemy saw it, one
    value per turn in which we scanned it. The averaged guns take the mean of this round's last 20 or 40 values. The
    pattern gun finds the latest earlier occurrence of the longest run (3 to 40) of our newest lateral velocities
    rounded to whole pixels per turn, over the whole battle, in one backward pass, and leads by the sum of the rounded
    values that followed it over the bullet's flight time, divided by the distance; without a match it aims head-on.
  - The numbers (`DangerParameters.DEFAULT`) are tuned on the danger bench
    ([harness.md](harness.md#danger-bench)) with the first four simple guns; the averaged and pattern guns came later
    with the same prior, checked on the bench and in battles. `DangerParameters.HISTOGRAM` is the single histogram the
    surfer used until 2026-10-09.
- **A dead enemy's waves stay** until they pass us: its bullets keep flying (engine-model G-44).
- **Surfing** (`WaveSurfer`): for the wave that reaches us first, three ways to move are predicted with
  `RobotMotion` until the wave has passed: orbiting its origin either way, or stopping. The orbit steers towards
  325 px, and drives backwards rather than turning around. Where a wall is ahead it turns away from the wave's origin
  until there is room, so near walls it drifts outwards and stays further than 325 px. `HitRange` gives the guess
  factors that would hit us on each path.
  - **Paths:** besides the three ways held until the wave passes, paths that change once from one of them to another
    after k turns: they drive past a point and come back, stop short, or wait and go, so they reach the points between
    the held ways' ends and the same points at other times and speeds. k is one of 3, 6, 10 and 15 turns, a different
    one each turn in rotation; the path chosen goes on a turn later (k − 1) as a candidate of its own and wins ties, so
    a plan found on one turn is followed while it stays the best. Paths that share their first turns are predicted
    together, and the next wave is surfed from the paths in the order of the nearest wave's danger until that alone
    reaches the best total, which leaves the choice as it would be. Against a robot that drives at us the ways are
    held, as below. CPU: a predicted turn costs about 0.25 µs for the orbit and the engine physics plus 0.075 µs per
    wave's hit range (Mac, C1 only as on the queue's worker); one switch turn a turn costs about twice the held ways,
    all four every turn about four times, which made the robot skip about 3 turns a battle on the worker (2026-10-11).
  - **Two waves:** the next wave's hit range observes the same path, and from where each path ends the next wave is
    surfed the three ways (held) too. A path's danger is its expected damage from the nearest wave (the share of the
    wave's profile inside the hit range, times the bullet's damage) plus the least expected damage from the next wave.
  - **Close fights:** nearer than 250 px to the wave's shooter, each wave's danger is multiplied by d_now / d_end, d
    being the distance to the enemy now and where the way ends (the angle we fill in the shooter's view grows with
    nearness).
  - **A floor:** at any distance, every way a wave would hit carries a floor of 0.02 of the bullet's damage, so that
    ways that close in on a rammer cost more even where the profile is empty. The floor is shadowed like the rest, so
    a way whose hit range is all shadowed costs nothing.
  - **Bullet shadows:** every bullet we fire is followed from the `Bullet` that `setFireBullet` returns until it
    leaves the field or hits a robot or a bullet. For each enemy wave and each of our bullets, in every turn both are
    in the air, the part of our bullet's move in that turn that lies between the wave's radii at the start and the
    end of the turn gives the bearings whose enemy bullets cross it; segments of the same turn that cross always
    collide (engine-model G-04). Crossings of one bullet's move with the other's move of the turn before collide only
    if the engine moves that bullet first, which is a fresh coin each turn, so they shadow at weight ½. A way's
    expected damage from a wave counts only the unshadowed share of its hit range, each guess factor weighted by the
    wave's profile and by the largest shadow weight on it.
  - The path with the least danger wins, and its first turn's commands are used. Ties keep the last path.
  - **Against an enemy that drives at us** (`RamDetector`: per enemy for the battle, an exponential average, weight
    0.01 per scan, of its speed towards us while it is closer than 200 px and 0 further away; above 2 px/turn it
    drives at us):
    - every way also predicts the enemy from its last scan: each turn it turns the shorter way towards where we were
      (backwards if that turns it less) and drives at full speed, with the engine's physics (`RobotMotion`);
    - the ways orbit the enemy instead of the wave's origin, five of them: the two usual orbits, two that tilt twice
      as steeply against the preferred distance (up to 1.2 rad): away from the enemy when it is nearer than 325 px,
      towards it when it is farther, and the stop; the next wave of another shooter is surfed around its own origin;
    - every way is predicted at least 20 turns, after its waves have passed if need be;
    - a way on which the boxes of the two robots overlap costs the damage of a full-power bullet, once per wave's part
      of the way (the predicted enemy is pushed back, our robot is not stopped); at the way's end
      the enemy's next full-power bullet counts with the chance min(1, (50 / d)²), d the distance to the predicted
      enemy then; the close-fight factor uses the predicted enemy at any distance;
    - while no wave is in the air (`WaveSurfer.evade`), the same five ways around the target, if it drives at us,
      each predicted 20 turns and weighed by the contact and the enemy's next shot alone. At a round's start a
      rammer's gun is still hot for about 30 turns, and the orbit below closed in on it from beyond 325 px meanwhile.
- **Without an enemy wave** (and no target that drives at us) the bot orbits the target and changes direction at
  random intervals, from its own seeded generator. It also steers towards 325 px, but turns towards the target at
  walls, so it stays closer than the surfer.
- **The distance moves the gun's hit rate:** a longer flight gives the target more room, so a movement change shows
  up in `-Pmetric=hits` too ([harness.md](harness.md#known-quirks)).

## Debug overlay (`cz.libors.ogi.trace`)

- **`Trace`** holds what the bot decided this turn, as consumers see it; the gun and the surfer describe themselves
  into it. **`Overlay`** is the one place that paints it.
- **Robocode mirrors robot graphics** to battlefield coordinates (y up), so points in the mathematical convention are
  drawn as they are; arcs are polylines, never `Arc2D`.
- **What is drawn:** our gun waves (grey, a fired one orange) with their hit ranges on the target (green), enemy waves
  (red) with their hit range on us (yellow), the gun's reference and escape angles, each gun's aim (guess factor
  green, circular blue, anti-surfer orange; the chosen one thick), and per target the guns' virtual hit rates and the data size.
- **To see it:** start the GUI (`cd .robocode/1.11.1 && ./robocode.sh`), run a battle with `cz.libors.Ogi`, open its
  console from the robot buttons on the right and press "Paint". Robocode paints before the robot acts, so the plan on
  screen is the shot of the current turn.
- **For the harness:** every turn with a target the bot sets the debug property `gun` (`Trace.gunLine`): the turn
  of the plan, whether last turn's plan was fired in this turn, the chosen gun, each gun's aim, the planned origin,
  the reference, the direction, the power, the data size, each gun's virtual hit rate and the target. A recording
  keeps it ([harness.md](harness.md#recording-comparison-battles)).
- **For the gun bench:** `cz.libors.OgiGunLog` is Ogi that also logs its gun's inputs and decisions (`GunLog`): one
  `gunpass` property per gun wave that passed its target in this turn (the plan's turn, whether it carried a bullet,
  the wave's reference and escape angle, the lowest and highest hitting offset from the reference), then `gunshot` with the turn's plan (the features,
  the direction, the reference, the escape angle, whether the guess-factor gun searched, the circular aim, the guess
  factor found and the chosen gun). That is everything `GunModel` needs to replay the battle.
  - `OgiGunLog` also logs `gunshield` when an enemy bullet destroys one of its bullets (the plan's turn and the
    shooter): Ogi hands every bullet it fires to the hook `fired(Bullet, Plan)`, which only `OgiGunLog` uses. Robots
    that shoot bullets down would otherwise look easy to hit on the bench. The log keeps the guns' aims, not the
    turret's offset (aim spread, shadow shots), so the bench scores the aims, as the guns learn them.
  - Only `OgiGunLog` logs, and its jar (`bot/build/robots/cz.libors.OgiGunLog_<v>.jar`) never goes to a rumble; the
    rumble jars leave the class out. Formatting the log costs 0.1–0.25 ms per turn while the JVM is cold, and with it
    Ogi skipped a turn in the first battle of every `skeletonCheck` run (2026-10-09).
- **Without the GUI:** `OverlayRenderTest` paints a simulated fight into `bot/build/overlay/gun.png`. The image is
  mirrored by hand, so its text is upside down.
- **Own turn time (`TurnClock`):** the bot measures the wall time from its first event of a turn (`onStatus`,
  which the engine delivers before the events Ogi handles; only a skipped turn's, a win's and a round's or battle's
  end come earlier) to its `execute()`, and reports the previous turn's time in µs
  as the debug property `cpu`. The engine gives that part of the turn the CPU constant (and up to a millisecond more,
  engine-model G-45). The harness compares it with the constant of an idle machine
  ([harness.md](harness.md#how-results-are-gathered)). Measured on the Mac (2026-10-09, lab opponents): median
  6–13 µs, 99th percentile ~80 µs, the longest turns 0.7–1.7 ms at a battle's start (the JIT) and in garbage
  collections. Those numbers predate the shadow shots and the anti-ram ways; the queue's health has the current
  ones per job (e.g. the 1v1 rumble on 2026-10-11: a few hundred turns over the idle constant in 4572 battles).
- **The first call of a record's `equals` (also `hashCode`, `toString`) or of a lambda links an `invokedynamic`
  call site**, which in a fresh JVM takes long enough to skip a turn: the path search with a record `equals` and a
  sort lambda skipped 1–2 turns in the first battle of every `skeletonCheck` run (2026-10-11), and none with plain
  methods (`Path.sameAs`, `Predicted.compareTo`). Code that runs in a turn for the first time mid-battle avoids them.
