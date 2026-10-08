# Engine model

How the Robocode engine behaves, as far as the bot depends on it. It is the specification that the bot's simulator
and its tests are written against.

**Rules for this document:**
- Every statement cites its source: the engine source (Robocode 1.11.1, the newest version LiteRumble accepts), the
  RoboWiki, or an observation.
- Every statement ends up covered by a differential test against the real engine. A test runs probe robots in the
  real engine, records each tick and compares it with our simulator.
- Wiki pages and bot descriptions are a starting point, not a source of truth. They often lag behind the code.

**Status of each gotcha:**
- `wiki`: stated on the RoboWiki only.
- `verified`: checked in the engine source.
- `to-test`: needs a differential test.
- `tested`: covered, with the test name.

## Tick order (to be completed from `Battle.runTurn`)

1. Robots execute their code until they take an action, then pause.
2. Time is incremented.
3. Bullets move and collide.
4. Robots move: gun, radar, heading, acceleration, velocity, distance, in that order. Gun heat decreases.
5. Robots scan and collect team messages.
6. Robots resume and process their event queues.

Source: RoboWiki Game Physics; to be verified step by step against the engine.

# Gotchas

## Firing and bullets

- **G-01 The gun fires before it turns.**
  - **What:** the bullet leaves at the gun heading from the start of the tick.
  - **Bites when:** you aim and fire in the same tick.
  - **Source:** wiki Game Physics, "Firing pitfall". · **Status:** wiki, to-test.
- **G-02 Bullets move and collide before robots move.**
  - **What:** a hit at tick t is evaluated against robot positions from the end of t−1.
  - **Source:** wiki processing loop. · **Status:** wiki, to-test.
- **G-03 Off-by-one in wave origin, fire time and travelled distance.**
  - **What:** an enemy shot is only visible as an energy drop in a later scan. The exact origin, fire time and initial
    distance offsets must be derived from the engine, not from tutorials.
  - **Bites when:** waves are built from tutorial offsets.
  - **Source:** classic surfing bug class; Diamond projects bullets over flight time − 1 because bullets advance once
    before collisions are checked. · **Status:** to-test.
- **G-04 Bullet–bullet collision geometry.**
  - **What:** bullets are line segments of the last tick. Bullets are updated in random order, so a bullet may collide
    with another bullet's previous-tick segment.
  - **Bites when:** predicting shield intercepts or bullet shadows.
  - **Source:** BeepBoop's author (RoboWiki). · **Status:** to-test in `BulletPeer`.
- **G-05 Gun heat at round start.**
  - **What:** all guns start hot. Heat after firing is 1 + p/5; it cools at 0.1 per tick by default (a battle
    setting).
  - **Source:** wiki; the initial value (3.0?) is [unverified]. · **Status:** to-test.
- **G-06 Bullet power is clamped and limited by energy.**
  - **What:** you cannot fire more than your energy, and there is a minimum of 0.1.
  - **Bites when:** the enemy-power estimate from energy drops ignores this.
  - **Source:** general. · **Status:** to-test.

## Movement

- **G-07 Acceleration 1, deceleration 2, and the rule through zero.**
  - **What:** when reversing, the engine decelerates and then accelerates within one tick by a specific rule. The
    maximum velocity also depends on the remaining distance.
  - **Bites when:** a precise predictor that is "almost right" drifts by a few pixels and breaks surfing.
  - **Source:** wiki (incomplete). · **Status:** to-test, derive from `RobotPeer`.
- **G-08 The turn rate depends on velocity:** 10 − 0.75·|v| degrees per tick.
  - **What:** which velocity counts (before or after this tick's change) matters.
  - **Source:** wiki. · **Status:** to-test.
- **G-09 Gun and radar rotation stack on the body rotation**, unless the `setAdjust*` flags are set.
  - **Source:** wiki. · **Status:** to-test.
- **G-10 Robot bounding box.**
  - **What:** 36×36 px [unverified]; XanderCat uses 40.
  - **Bites when:** wrong precise intersection or wall distance.
  - **Source:** XanderCat source and version history. · **Status:** to-test.
- **G-11 Wall hits.**
  - **What:** an AdvancedRobot takes max(|v|/2 − 1, 0) damage and stops.
  - **Bites when:** an **enemy energy drop from a wall hit is taken for a shot** (false wave).
  - **Source:** wiki; classic bug class. · **Status:** to-test.
- **G-12 Robot collisions.**
  - **What:** 0.6 damage to each robot. Ram score goes only to the robot moving towards the other, and a robot moving
    away is not stopped.
  - **Bites when:** an energy drop from a ram is taken for a shot.
  - **Source:** wiki. · **Status:** to-test.

## Sensing and events

- **G-13 Radar scans the arc swept this tick.**
  - **What:** the arc between the previous and the current radar heading; range 1200 [unverified]. Scan data reflects
    the state at scan time.
  - **Source:** wiki and engine. · **Status:** to-test.
- **G-14 Enemy energy can change without a shot:** our hits (energy loss), its hits on us (energy gain of 3p), wall
  hits, rams, hits by third parties in melee, inactivity zapping.
  - **Bites when:** naive energy-drop wave detection.
  - **Source:** general; melee wave detection in Diamond. · **Status:** to-test.
- **G-15 Event handlers that take turns are interrupted** by `EventInterruptedException` when events nest.
  - **Source:** wiki Game Physics. · **Status:** wiki.
- **G-16 Time resets every round** [unverified]. Store (round, tick) pairs.
  - **Status:** to-test.

## Timing and CPU

- **G-17 Painting relaxes the turn time limit** to `DEBUG_TURN_WAIT_MILLIS` = 10 min. No skipped turns show up while
  watching, so the bot must measure and display its own decision time.
  - **Source:** `Battle.java`. · **Status:** verified.
- **G-18 First turn and skipped turns.**
  - **What:** the first turn waits `millisWait × 10`; `MAX_SKIPPED_TURNS = 30`.
  - **Source:** `Battle.java`, `RobotPeer.java`. · **Status:** verified (semantics to-test).
- **G-19 Waves fired while we skipped turns are missed** unless handled explicitly. XanderCat 12.8 fixed exactly this.
  - **Source:** XanderCat source and version history. · **Status:** to-test with a skipped-turn simulator.
- **G-20 The CPU constant differs between rumble clients.**
  - **What:** turbo boost makes the constant loose; long-running clients degrade (rumble anomalies reported on Talk:BeepBoop in 2021).
  - **Source:** Talk:BeepBoop. · **Status:** wiki.
- **G-21 Robots run serially within a tick by default** (`wakeupSerial`), so one battle ≈ one core.
  - **Source:** `Battle.java`. · **Status:** verified.

## Teams and isolation

- **G-22 No shared memory between teammates:** each robot has its own classloader and its own statics.
  - **Source:** engine; XanderCat. · **Status:** verified.
- **G-23 Messages arrive one tick later.** They are `Serializable` and at most 32,768 bytes each. Whether
  serialisation counts against turn time is [unverified].
  - **Source:** Robocode source. · **Status:** verified (size), to-test (timing).
- **G-24 Team energy rules:**
  - leader +100; droid +20 and no radar
  - leader death costs every alive teammate 30
  - friendly fire still gives the shooter its 3p energy, but no score
  - **Source:** `RobotPeer`, `BulletPeer`. · **Status:** verified.
- **G-25 Start positions are random per robot**, not grouped by team.
  - **Source:** Robocode source. · **Status:** verified.

## Determinism, files, portability

- **G-26 Reproducibility.**
  - **What:** `RandomFactory.resetDeterministic(seed)` controls start positions and processing order. Its `Math.random`
    patch may fail on current JVMs [unverified].
  - **Source:** Robocode source (`RandomFactory`). · **Status:** to-test.
- **G-27 Quotas:** robot file system 200,000 bytes; console 8192 characters.
  - **Source:** `SettingsManager`. · **Status:** verified.
- **G-28 Field size differs per rumble:** 800×600, 1000×1000, 1200×1200, 800×800. DrussGT hard-codes 800×600.
  - **Source:** rumble configs; DrussGT source. · **Status:** verified.
- **G-29 Angle conventions.**
  - **What:** Robocode heading = π/2 − math angle. Robocode-style `atan2(dx, dy)` equals math `atan2(dy, dx)`.
  - **Rule:** convert only at the API boundary and in the painting adapter.
  - **Source:** project convention. · **Status:** to-test (geometry unit tests).
- **G-31 `BattleResults.getRank()` is not simply the order by score in melee.** In the first skeleton melee run, a
  bot with a higher score had a worse rank. The harness must define its own ranking.
  - **Source:** harness observation. · **Status:** to-test, check in `BattleResults` and the engine.
- **G-32 The team result is named after the team plus an index**, e.g. `cz.libors.OgiTeam 0.1 (1)`, not after the
  leader robot.
  - **Source:** harness observation. · **Status:** observed.
- **G-30 Approximate trigonometry breaks prediction.** Lookup-table sin/cos must not be used where we have to match the
  engine.
  - **Source:** project convention. · **Status:** to-test (the precise predictor's differential test).
