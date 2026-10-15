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
4. **Movement:** in a duel, until the enemy's trial fails, the bullet shield instead of the rest of this list
   ([Bullet shield](#bullet-shield-czliborsogishield)). Otherwise the surfer's commands while an enemy wave is in the air; otherwise, in a duel, the wave the enemy can
   fire next once its gun is cool (`WaveSurfer.gunHeatCommand`), the surfer's ways around a target that drives at us
   (`WaveSurfer.evade`), or orbiting the target (`OrbitMovement`). A disabled target in a duel
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
- **Search (`KdTree`, `GunData`):** data that keeps every shot is searched with a bucket kd-tree (32 shots a leaf,
  split at the middle of the widest weighted spread); the views with a size limit are scanned, a shot dropped as soon
  as its partial sum of squares reaches the k-th distance. Both find exactly the shots that sorting all of them by
  distance and then by age finds: the tree's bound on a node is summed with the same arithmetic as a distance, so it
  is never above one inside, and `KdTreeTest` checks both against that sort, ties included. The neighbours' hit
  ranges are sorted by a stable merge sort. Against Komarious (35 rounds, Mac, C1 only as on the queue's worker,
  2026-10-12) a search took 32 µs on average instead of the scan's 146 µs, and the gun's aiming 0.29 s of a battle
  instead of 1.06 s; the scan grew to 55–100 µs a turn by the last rounds.
- **Bullet power** (`Ogi.bulletPower`): 3 within 200 px, else 23/12 and one unit in the last place (1.916666666666667),
  at most the enemy's energy / 4 + 0.1 and our energy less 0.1. Ten times that bullet's speed lies just past a half
  (142.5): a surfer built on the BasicSurfer pattern matches a bullet that hit it to its wave by the speeds rounded to
  tenths, the wave's speed taken from our energy drop, whose floating-point noise rounds it the other way about four
  times in five, and then it does not learn the hit (2026-10-12; 1.9, a whole 143, never failed the match). Powers of
  2.2 lost a point on the population. Against the enemies we hit 25–55 % of the time, one power for all of them from
  1.48 to 2.48 (2026-10-14): the score is flat from 2.0 to 2.5 and falls below 2.0. A lower power hits more often,
  but deals less damage a turn, so the rounds last longer and the enemy hits us more; a higher one, below a hit rate
  of a third, costs us energy with every shot (a shot costs its power and a hit returns three times it) and loses
  rounds. Our hit rate alone does not tell which power scores more.
  - **Against an enemy we read** (`HitRecord`, `READ_HIT_RATE`): once 20 of our bullets at it have ended in the
    battle (hit it or missed; shot down or still in the air at the round's end left out) and 55 % of them hit, the
    power is 3 at any distance. Such enemies win almost no rounds and score only through their hits on us, so a
    faster kill takes points from them; at 75 % a power-3 bullet still hits about 71 %. At 25 % or 35 % the rule lost
    or gained nothing: the slower bullet missed a tenth more often and the rounds did not end sooner (2026-10-13).
  - **Against an enemy we hit rarely** (`RARE_HIT_POWER`, `RARE_HIT_RATE`): in a duel, beyond close range, 1.45
    instead of 1.92 while our bullets at it hit less than 25 % of the time after 30 of them ended (the record that
    also sets the nearer distance, with its own threshold of 18 %). The faster bullet leaves the enemy a narrower escape
    angle: against the strong bots that gained about 1.5 points of score share and against the medium tier about 0.7,
    while 1.15 gained nothing (more hits do not make up for half the damage); 18 % instead of 25 % gave the medium tier
    0.55 less. Ten times the speed of 1.45 is 156.5, so the BasicSurfers' rounding misses its hits too (2026-10-13).
  - **In an energy race** (`RacePower`, `RACE_ENERGY`, `RACE_CLOSE_ENERGY`): in a duel, while our energy is below
    20, or the enemy's is below 20 and ours below 40, the round goes to whoever runs out last. Each robot loses
    energy through its own shots (a shot costs p, a hit returns 3p) and through the other's hits; with each gun firing as soon as it is cool (a shot every 10 + 2p turns), our hit rate h,
    the enemy's hit rate on us g and its power q, we lose p(1 − 3h)/(10 + 2p) + g · damage(q)/(10 + 2q) a turn and it
    loses q(1 − 3g)/(10 + 2q) + h · damage(p)/(10 + 2p). The power is the one of 0.1, 0.2, 0.35, 0.5, 0.75, 1, 1.45 and
    the normal one (never above it) with the largest ratio of its loss to ours, which makes the time we last longest
    against the time it lasts, whatever the two energies are. h is our bullets' record against it over the battle,
    scaled for p by the maximum escape angles (a faster bullet hits more often); g is its hits per detected shot over
    the battle (`WaveSurfer.hitRateOnUs`), q its last shot's power. Against an enemy that hits us rarely and keeps
    firing, a weak bullet costs us almost nothing while its misses run it down; against one that hits us often, our
    damage has to end the round, and the power stays. The model predicts how long we last below 20 energy (about 155
    turns at 1.65, measured 130) and how long Diamond lasts with its 0.1 below 20 (440, measured 450). Against the
    strong and medium population it gained 0.4 points of score share below 20 alone (half a round won more a battle,
    the damage kept) and 0.5 with the close races (against the strong tier 1.25, a round and a half won more a
    battle); 0.1 below 20 for every enemy won the same rounds but lost the damage, the model below 30 whatever the
    enemy's energy lost damage too, and keeping 450 px while firing weak bullets cost our hit rate against the strong
    bots; against the weak tier, where few rounds become races, nothing changed (2026-10-14).
- **Virtual guns:** every plan also holds the circular aim (`CircularAim`). A passed wave scores both guns only if
  the guess-factor gun searched for its plan (at power 1.92, 4 waves in 14: in the turn it fires, the gun heat already
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
- **Random gun:** a fourth gun aims uniformly inside the target's precise escape range (`EscapeRange`): from its last
  scan the target orbits our planned origin at full speed each way, moved by the engine's rules and sliding along the
  walls (a 120 px stick, turning towards us), until the wave reaches it; the range is the lowest and highest bearing
  on the way, widened by half a robot's mean width (2 · 36 / π px). No movement leaves it: in recordings of the whole
  rumble under 0.2 % of the bullets found their target outside it (2026-10-12). So a surfer cannot learn where it aims,
  and it hits any movement about as often as a robot's width over the range's. It is scored by the share of its range
  that would have hit, not by its one aim; the place in the range is drawn from a seeded generator of its own
  (`Ogi.randomAim`) once per bullet and held until the bullet fires, so that the turret reaches it in the turns
  before the gun is cool (a draw every turn made the turret chase it, and the bullets fired where a draw fell within
  its reach: later and not uniformly); it goes into the shot, so the bench replays it. Against an enemy that is hard to hit it replaces
  the gun that would fire (the anti-surfer gun's or the main gun's choice) once it has been scored on 30 bullets and
  its record over them is the better one (DrussGT's idea). The range costs two predicted runs of the target, only in
  the turns the guns search.
- **Mirror gun** (`MirrorAim`): some robots drive to where a fixed map sends our own position some turns earlier, most
  of them to its point mirror through the field's centre, 2 to 46 turns late (16 such bots in the rumble, found on
  its recordings of 2026-10-11). They move as well as we do against a gun that reads their own movement: our main gun
  hit them 14–22 %. In a duel, for every lag from 0 to 60 turns, the enemy's position at the end of a turn is fitted
  on ours that many turns earlier by least squares, as an affine map (any mirror, any centre, the shrink at the walls);
  the sums last for the battle, and the lag with the least residual counts once it has 200 pairs and its map explains
  half the variance of the enemy's position. The aim then follows the bullet: the target at the end of each turn is
  the map of our position a lag before, and the bullet aims at the first such point it can reach. A short lag needs
  our own position after the shot: the surfer's planned path stands for it (`WaveSurfer.plannedPositions`, its last
  way held after its turns), only as many turns as the flight less the lag, so robots with a long lag cost nothing
  more than the fit. The gun aims only in the turns the guns search and replaces the guess-factor or circular gun once
  it has 20 weighted tries and the better weighted record. Against the closest mirrors (2–12 turns late) its aims
  would hit 19–22 %, where a replay with our real later positions hits 37–59 %: our planned path is the limit, not
  the map; against the one 46 turns late they would hit 30 %, and our hit rate on it rose from 22 to 37 %. Score +2.8
  points of share on the 16 (2026-10-14); against the 63 robots that drive at us, which fit a map too, it changed
  nothing.
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
  - **An ensemble:** that data profile is one estimator. The others are thirteen simple guns (`SimpleAims`: head-on,
    linear with and without the walls, circular, linear with our lateral velocity averaged over the last 20 and 40
    turns, two pattern matches on our lateral velocity, rounded and truncated, and five that rumble bots use: the linear
    lead without the arc sine, the average over 5 turns, and 0.5, 0.75 and 1.25 times the linear lead; each aimed from
    the enemy's scanned position and our state of the turn it aimed), each a narrow kernel at its aim, and a flattener:
    a KNN over where we ourselves were when this enemy's waves passed us (`DangerModel.visit`, the middle of the wave's
    hit range on us; every wave, also those whose bullet hit us or one of ours, which the surfer no longer surfs but
    follows until they pass), which is where a gun that learns our movement aims. Every observed bullet scores each
    estimator by the share it gave the bullet's bin; the weights go with the prior times `exp(temper · record)`, the
    record forgetting a part per bullet (`decay`). A simple gun the enemy does not use loses its weight within a few
    bullets, so it costs no room against learning guns.
  - **Lateral history** (`LateralHistory`, per enemy, for the battle): our lateral velocity as that enemy saw it, one
    value per turn in which we scanned it. The averaged guns take the mean of this round's last 20 or 40 values. The
    pattern gun finds the latest earlier occurrence of the longest run (3 to 40) of our newest lateral velocities
    rounded to whole pixels per turn, over the whole battle, in one backward pass, and leads by the sum of the rounded
    values that followed it over the bullet's flight time, divided by the distance; without a match it aims head-on.
    The second pattern gun does the same on our lateral velocity truncated towards zero, as guns that cast it to a
    whole number see it: 7.6 px/turn, an orbit tilted towards the preferred distance, is 8 rounded and 7 truncated.
    Against the nano pattern matchers that truncate it explains about twice as many of their bullets (2026-10-12).
  - The numbers (`DangerParameters.DEFAULT`) are tuned on the danger bench
    ([harness.md](harness.md#danger-bench)) with the first four simple guns; the averaged and pattern guns came later
    with the same prior, checked on the bench and in battles (the truncated one and the last five on 2026-10-12, found
    by running the bench over recordings against the whole rumble and keeping guns that explain some bots' bullets
    much better).
    `DangerParameters.HISTOGRAM` is the single histogram the surfer used until 2026-10-09.
- **A dead enemy's waves stay** until they pass us: its bullets keep flying (engine-model G-44).
- **Surfing** (`WaveSurfer`): for the wave that reaches us first, three ways to move are predicted with
  `RobotMotion` until the wave has passed: orbiting its origin either way, or stopping. The orbit steers towards
  325 px, and drives backwards rather than turning around. Where a wall is ahead it turns until there is room, towards
  the preferred distance: away from the wave's origin when nearer than 325 px, towards it further out. Against an
  enemy that keeps further away and backs off when we come nearer, turning away at any distance let the distance
  drift out (at 400–500 px the orbit closed in about 1 px/turn in the open and backed off about as fast within 50 px
  of a wall). `HitRange` gives the
  guess factors that would hit us on each path.
  - **A nearer distance against enemies we hit rarely:** in a duel, once per round at its first turn with a target,
    `Ogi` sets the surfer's preferred distance to 275 px instead of 325 when our bullets at that enemy over the battle
    hit it less than 18 % of the time, after at least 30 of them ended (the same `HitRecord` that sets full power);
    within a round it does not change. A nearer fight raises our hit rate more than theirs against such enemies, while
    against the enemies we hit often it changes little or costs. The orbit without waves keeps 325 px.
  - **Steeper held ways:** two more ways held until the wave passes, the orbits that lean twice as steeply towards
    the preferred distance (at most 1.2 rad; the anti-ram mode's retreats, here around the wave's origin): they change
    where we can be in distance too, the hit range's width and the bullet's flight time.
  - **Paths:** besides the ways held until the wave passes, paths that change once from one of the first three to
    another after k turns: they drive past a point and come back, stop short, or wait and go, so they reach the points
    between the held ways' ends and the same points at other times and speeds. k is one of 3, 6, 10 and 15 turns, a
    different one each turn in rotation; the path chosen goes on a turn later (k − 1) as a candidate of its own and
    wins ties, so a plan found on one turn is followed while it stays the best.
  - **Targeted stops:** while each orbit is predicted, every turn's braking point (where we would stand if we braked
    then, 12 px on from full speed) is scored by the wave's profile over the bins our box covers there seen from the
    origin, each bin less its likeliest shadow; the turn with the least exposure gives one more path per orbit, orbit
    then stop, predicted and weighed like the others. So the stopping paths reach the safest point of the reachable
    span, not only the points a fixed k gives.
  - Paths that share their first turns are predicted together, and the next wave is surfed from the paths in the
    order of the nearest wave's danger until that alone reaches the best total, which leaves the choice as it would
    be. Against a robot that drives at us the ways are held, as below. CPU: a predicted turn costs about 0.25 µs for
    the orbit and the engine physics plus 0.075 µs per wave's hit range (Mac, C1 only as on the queue's worker). A
    surfing call costs about 8 µs with the three held ways, 15.5 µs with one switch turn a turn, 25 µs with the
    steeper ways and targeted stops too; all four switch turns every turn (31.7 µs) made the robot skip about 3 turns
    a battle on the worker, the 25 µs about 0.7 (2026-10-12, against 0.1). In battle a call predicts about 400 turns
    and takes about 111 µs (Komarious, 35 rounds); three exact shortcuts brought that to about 94 µs: the wall
    smoothing steps over headings whose stick end lies too far outside for the next steps to clear the walls
    (`smoothed`; the failing headings tested per predicted turn fell from 10.3 to 1.8), `HitRange.observe` makes
    its quick tests without allocating a box, and the full hit test skips the wall clipping when the box is more
    than 120 px from every wall (`HitRange.WALL_CLEARANCE`). They return the same numbers; recorded battles replay
    bit for bit.
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
    - while no wave is in the air and its gun is hot (`WaveSurfer.evade`), the same five ways around the target, if
      it drives at us, each predicted 20 turns and weighed by the contact and the enemy's next shot alone. At a round's start a
      rammer's gun is still hot for about 30 turns, and the orbit below closed in on it from beyond 325 px meanwhile.
- **The wave a cool gun can fire next** (`WaveSurfer.gunHeatCommand`, in a duel, rammers included): while no enemy
  wave is in the air and `FireDetector`'s heat model says the enemy's gun is cool by the end of this turn, the surfer
  surfs the wave it could fire next turn as if it were in the air: from the enemy's position now, with the power of
  its last wave (2 before its first), aimed at us as we are now, with the same danger model and our bullets' shadows.
  It is rebuilt every turn and replaced by the real wave once the energy drop shows the shot, so we start dodging a
  turn or two sooner. Close up this matters most: within 150 px about four of five enemy shots leave while no wave
  of theirs is in the air, beyond 350 px fewer than one in ten.
- **Without an enemy wave** (while the enemy's gun is hot, and no target that drives at us) the bot orbits the target and changes direction at
  random intervals, from its own seeded generator. It also steers towards 325 px, but turns towards the target at
  walls at any distance (the surfer only beyond 325 px).
- **The distance moves the gun's hit rate:** a longer flight gives the target more room, so a movement change shows
  up in `-Pmetric=hits` too ([harness.md](harness.md#known-quirks)).

## Bullet shield (`cz.libors.ogi.shield`)

In a duel the bot starts by standing still and shooting each enemy bullet down with one of its own. Against a target
that stands, head-on, linear and circular guns aim at the same point and guns that learn converge to it, so many guns
become predictable to the last bits; anything that makes us move spoils that.

- **The trial** (`Ogi`, per enemy, for the battle): every duel starts with the shield. Once the damage the enemy's
  bullets did to us while we shielded exceeds 35 plus 0.25 per shot it fired meanwhile, or we lost 2 rounds while
  shielding, we surf it for the rest of the battle. Never the other way round: guns that learn our movement learn a few
  rounds of surfing, and a shield tried after them fails. In shield mode we score about 60 a round (survival), so 0.25
  a shot at about 40 shots a round keeps a share of about 80 %. The start allows two full-power hits, since the aim
  models learn from the first bullets.
- **Targets** (`Shield`): every shot the fire detector finds with an exact fire time, while we saw both robots,
  becomes a target. Each turn the first target not yet shot at gets a plan. Once the gun will be cool and on the
  plan's heading in the next turn the plan is committed, and it is fired then only if the gun is within 1e-9 rad of the
  heading. While the gun cannot fire in the next turn there is no plan, only the gun turned towards the line to the
  bullet's origin: a plan costs a fraction of a millisecond warm and several cold. Each enemy bullet that hits us or
  one of ours is matched to its target, also one or two bullet moves back (engine-model G-04), and teaches the aim.
- **Where the enemy aims** (`ShieldAim`, per enemy, for the battle): 45 models, each a base heading and an offset.
  - The bases, for a bullet fired in turn f: head-on from the enemy's position at the end of f − 1 at ours then
    (engine-model G-01); from the bullet's origin; from the enemy's position advanced by one turn of its velocity;
    head-on plus its body's turn in f. Each also at our position advanced by one turn of our velocity, which matters
    only in the turns we move. And the enemy's body heading, for a gun that never turns on its own.
  - The offsets: constant; signed by our lateral direction as the enemy saw it (the last one we moved in, or the
    current one with standing counted as clockwise); the same two in units of the maximum escape angle.
  - A model's offset is the error that most of its last 60 errors match within 2e-5 rad. The model matching the most
    bullets predicts, the smaller offset on a tie; without bullets, head-on. Following runs of a gun's other aim (one
    guess-factor bin off) after two of their shots caught more against some bots and provoked more against others,
    so the aim does not follow runs.
  - The margin the enemy's aim needs: 1.25 × the error 90 % of the last 30 predictions stayed within, at least
    2e-5 rad; 1e-4 before 10 bullets.
- **The shot** (`ShieldPlanner`): two bullets collide when their moves of a turn cross (engine-model G-04).
  - For each turn of the enemy bullet's flight, until it would come within our box's extent along its path plus
    2 px (`18 / max(|cos|, |sin|) + 2`, 20 to 27.5 px: the box is square and axis-aligned, engine-model G-03), the
    planner aims at five points of the enemy bullet's move of that turn, the middle first. It takes the speed at which
    our move of the turn is centred on the point, or the nearest allowed speed that still reaches it. With the middle
    alone, two bullets of the same speed, closing in by two moves a turn, had no solution in half of the turns.
  - A shot's margin is how far the enemy's heading may be off and the bullets still collide for sure, from the
    shadows our bullet would cast on the wave (`BulletPath.shadows`, from the turns around the crossing).
  - The choice, among shots the gun is ready for with a sure margin (2e-5 rad): the smallest move whose shot reaches
    the needed margin; a power within the energy race's target; the widest margin up to 3e-3 rad (some guns aim with
    a little noise); the lowest power.
  - **The energy race:** if every shot meets its bullet, each enemy shot of power p costs it p and our answer q
    costs us q. Its energy has paid for its bullet, ours not yet for the answer, so we can answer all its shots while
    E_enemy / p ≤ (E_us − q) / q, that is q ≤ p · E_us / (E_enemy + p). The power never exceeds the enemy's bullet's.
- **Standing:** we stand where the round put us, the body across the line to the enemy, and return there after a
  step; pushed more than 2 px away (by a robot) we stand where we are. A robot on the enemy bullet's line can meet it
  only head-on, so a standing robot may step `ShieldPlanner.WIGGLE` (a tenth of a pixel) aside along the body for a
  shot and back, but only where standing gives less margin than the enemy's aim needs. Every move teaches guns that
  learn our movement to expect another: steps of a pixel at the start of each round cost more hits than the margin
  they gave.
- **Retreat:** closer to the enemy than 38 px plus 3.4 px per unit of its next bullet's speed (its last shot's power
  this round, before that the battle's mean, before its first shot 2), our fastest bullet fired up to two turns after
  the enemy's would not meet it in time: about 105 px against power 0.1, 80 px against 3. We back straight away, or
  along the nearest heading that stays 30 px inside the field 60 px ahead, still shielding.
- **A disabled enemy** gets a 0.1 bullet once none of its bullets is left to shoot down: any hit kills it, and a ram
  would move us.
- **Idle:** after 60 turns without an enemy shot the gun shoots at the enemy as usual.
- **The surfer does not learn** from the waves fired while we shield (`WaveSurfer.learning`): bullets aimed at a
  standing target say nothing about the aim at a surfer. The shield's bullets still cast their shadows.
- **Inactivity:** bullets that collide damage nobody, so a shield's rounds against weak bullets can reach the
  inactivity penalty ([fire-detection.md](fire-detection.md#inputs)).
- **Debug properties:** `shield` for each enemy bullet matched to its target: the fire turn, `down` or `hit`, whether
  we shot at it (or why not), the model, the margin, the error of the expected heading, the distance, our power, the
  model and offset for the next bullet, the share of bullets it matches and the states of the turns it was planned for.
  `shieldTrial` when the trial ends.

## Debug overlay (`cz.libors.ogi.trace`)

- **`Trace`** holds what the bot decided this turn, as consumers see it; the gun and the surfer describe themselves
  into it. **`Overlay`** is the one place that paints it.
- **Robocode mirrors robot graphics** to battlefield coordinates (y up), so points in the mathematical convention are
  drawn as they are; arcs are polylines, never `Arc2D`.
- **What is drawn:** our gun waves (grey, a fired one orange) with their hit ranges on the target (green), enemy waves
  (red) with their hit range on us (yellow), the gun's reference and escape angles, each gun's aim (guess factor
  green, circular blue, anti-surfer orange, random violet, mirror pink; the chosen one thick), and per target the
  guns' virtual hit rates and the data size.
- **To see it:** start the GUI (`cd .robocode/1.11.1 && ./robocode.sh`), run a battle with `cz.libors.Ogi`, open its
  console from the robot buttons on the right and press "Paint". Robocode paints before the robot acts, so the plan on
  screen is the shot of the current turn.
- **For the harness:** every turn with a target the bot sets the debug property `gun` (`Trace.gunLine`): the turn
  of the plan, whether last turn's plan was fired in this turn, the chosen gun, each gun's aim, the planned origin,
  the reference, the direction, the power, the data size, each gun's virtual hit rate and the target. A recording
  keeps it ([harness.md](harness.md#recording-comparison-battles)).
- **For the gun bench:** `cz.libors.OgiGunLog` is Ogi that also logs its gun's inputs and decisions (`GunLog`): one
  `gunpass` property per gun wave that passed its target in this turn (the plan's turn, whether it carried a bullet,
  the wave's reference and escape angle, the lowest and highest hitting offset from the reference), then `gunshot`
  with the turn's plan (the features, the direction, the reference, the escape angle, whether the guess-factor gun
  searched, the circular aim, the guess factor found, the chosen gun, the random gun's range and draw, and the mirror
  gun's aim; lines logged before 2026-10-12 lack the random gun's three fields and lines logged before 2026-10-14 the
  mirror gun's aim, and are read without those guns). That is everything `GunModel` needs to replay the battle.
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
  In 35-round duels with the worker's JVM options (2026-10-12): mean 106–140 µs a turn, 99th percentile 357–392 µs,
  about three quarters of it surfing; against light opponents that is most of the battle's CPU (Komarious: 3.2 of
  4.3 s), so the queue's throughput follows it. Profile with timers in the bot: JFR, sampling the robot thread, kept a fifth of the samples
  and gave the surfer under 1 % of the time against the timers' 65 %.
- **The first call of a record's `equals` (also `hashCode`, `toString`) or of a lambda links an `invokedynamic`
  call site**, which in a fresh JVM takes long enough to skip a turn: the path search with a record `equals` and a
  sort lambda skipped 1–2 turns in the first battle of every `skeletonCheck` run (2026-10-11), and none with plain
  methods (`Path.sameAs`, `Predicted.compareTo`). Code that runs in a turn for the first time mid-battle avoids them.
