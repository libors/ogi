# Engine model

How the Robocode engine behaves, as far as the bot depends on it. It is the specification that the bot's simulator
and its tests are written against.

**Rules for this document:**
- Every statement cites its source: the engine source (Robocode 1.11.1, the newest version LiteRumble accepts), the
  RoboWiki, or an observation.
- Every statement ends up covered by a differential test against the real engine. A test runs probe robots in the
  real engine, records each tick and compares it with our simulator.
- Wiki pages and bot descriptions are a starting point, not a source of truth. They often lag behind the code.

**In code:** `cz.libors.ogi.physics` repeats the engine's arithmetic. It works in the engine's angle convention so
that it can match bit for bit.
- `RobotMotion`: one robot in one turn: firing, cooling, turning, movement, walls, rams in both roles, bullet
  damage and bonus, the inactivity penalty. `Inactivity` decides the penalty turns for the battle.
- `BulletMotion` and `RobotBox`: bullets: spawn, moves, wall and robot hits, bullet crossings.
- `RadarScan`: which robots a scan sees, and the values it reports.

Outside that package, `cz.libors.ogi.wave.HitRange` repeats the engine's bullet test for a whole wave: the headings
at which its bullet hits a robot, turn by turn (see [bot.md](bot.md)).

`./gradlew engineTest` replays every probe recording through it (numbers from 2026-10-08):
- `RobotMotionReplayTest`: from the state and commands of turn t, the robot's state of turn t + 1 (about 160,000
  robot turns);
- `BulletMotionReplayTest`: every bullet's spawn and every move (about 6,400 bullets and 130,000 moves);
- `EnergyReplayTest`: the whole single-robot turn including energy, bit for bit under some processing order (about
  160,000 turns, 5,000 of them with the inactivity penalty);
- `EventReplayTest`: every event a probe received against the recording, and every event it should have received
  (about 92,000, 82,000 of them scans);
- `HitRangeReplayTest`: in every turn of every bullet's flight, its heading lies in the hit range of the robot it hit
  and outside the range of every robot it flew past (about 1,100 hits and 1.2 million misses).

Not modelled yet: which robot pairs collide and which bullet pairs meet (both depend on the engine's random
processing order), team rules (leader bonus, droids, the leader's death), sentry robots.

**Status of each gotcha:**
- `wiki`: stated on the RoboWiki only.
- `verified`: checked in the engine source.
- `to-test`: needs a differential test.
- `tested`: covered, with the test name.

## Tick order

From `Battle.runTurn` and `RobotPeer` (Robocode 1.11.1). Status: verified in the source; steps 2–5, 7 and 8 are
tested robot by robot and bullet by bullet (`./gradlew engineTest`). The random order within steps 3 and 4 is
to-test.

1. Time is incremented (`BaseBattle.runTurn`).
2. **Commands load** (`RobotPeer.performLoadCommands`): each robot takes the commands from its last action. A queued
   bullet is fired from the robot's current position along its current gun heading, if the gun heat is 0 and the
   energy above 0.
3. **Bullets move and collide** (`BulletPeer.update`), in random order, including the bullets fired in step 2:
   walls, robots, other bullets.
4. **Robots move** (`RobotPeer.performMove`), in random order: the gun cools, then the body turns (not while in a
   collision), the gun turns, the radar turns, the robot moves, and wall and robot collisions are resolved.
5. Bounding boxes are corrected; then every robot scans, in random order, and its team messages are dispatched
   (`performScan`).
6. Dead robots are scored and `RobotDeathEvent`s are queued (`handleDeadRobots`); the round end is handled if one
   team remains.
7. **Statuses are published** (`publishStatuses`): this is the state a robot reads in its turn.
8. **Robots act** (`wakeupRobots`), one after another in random order. Each first receives the events of this
   turn's update (G-40), then runs until it calls `execute()`; its commands wait for step 2 of the next turn. The
   engine waits only for live robots.
9. The turn snapshot goes to battle listeners (`TurnEndedEvent`). Recordings are taken here, so the state of turn t
   and the commands a robot issued in turn t appear together.

# Gotchas

## Firing and bullets

- **G-01 The gun fires before it turns.**
  - **What:** the bullet leaves along the gun heading the robot read in the turn it called fire, i.e. before this
    turn's gun turn, from the robot's position before this turn's move.
  - **Bites when:** you aim and fire in the same tick.
  - **Source:** wiki Game Physics, "Firing pitfall"; `RobotPeer.performLoadCommands` fires before `performMove`.
    · **Status:** tested: `BulletMotionReplayTest` (spawn heading and position).
- **G-02 Bullets move and collide before robots move.**
  - **What:** a hit at tick t is evaluated against robot positions from the end of t−1.
  - **Source:** wiki processing loop; `Battle.runTurn` (tick order steps 3 and 4). · **Status:** tested:
    `BulletMotionReplayTest` (victims from the boxes of the previous turn).
- **G-03 Off-by-one in wave origin, fire time and travelled distance.**
  - **What:** an enemy shot is only visible as an energy drop in a later scan. The exact origin, fire time and initial
    distance offsets must be derived from the engine, not from tutorials.
    - A bullet moves in the turn it is fired: commands load, then all bullets move (tick order steps 2 and 3). In
      its first snapshot it is one bullet-speed step (20 − 3p) from the shooter's position of the previous turn.
    - A bullet that hits a robot stops at the start of its last move if that point lies inside the robot's box,
      otherwise at the end.
  - **Bites when:** waves are built from tutorial offsets.
  - **Source:** classic surfing bug class; Diamond projects bullets over flight time − 1 because bullets advance once
    before collisions are checked; `BulletPeer.update`, `checkRobotCollision`. · **Status:** tested:
    `BulletMotionReplayTest` (spawn position, every move, impact position); the energy-drop side is step 2c.
- **G-04 Bullet–bullet collision geometry.**
  - **What:** bullets collide when their last-move segments cross; bullets of the same robot never collide. Bullets
    are updated in random order, so a bullet may be hit through its previous-turn segment before it moves. A bullet
    that hit another stops at the start of the crossing segment: at its position of this turn's start if it had
    moved, or of the previous turn's start if it had not.
  - **Bites when:** predicting shield intercepts or bullet shadows: whether two bullets meet can depend on the
    processing order.
  - **Source:** BeepBoop's author (RoboWiki); `BulletPeer.checkBulletCollision`, `intersect`. · **Status:** tested:
    `BulletMotionReplayTest` (the stop position in both cases; which pair collides is taken from the recording).
- **G-05 Gun heat at round start.**
  - **What:** all guns start with heat 3.0. Heat after firing is 1 + p/5; it cools at 0.1 per tick by default (a
    battle setting), before the robot's turns and moves (tick order step 4). A robot whose gun reads 0 fires in the
    next turn, before that turn's cooling.
  - **Source:** `RobotPeer.initializeRound` sets 3; `fireBullets`, `updateGunHeat`. · **Status:** tested:
    `RobotMotionReplayTest` (heat), initial value seen at turn 0 of every recording.
- **G-06 Bullet power is clamped and limited by energy.**
  - **What:** the engine fires min(energy, clamp(p, 0.1, 3)); you cannot fire more than your energy.
  - **Bites when:** the enemy-power estimate from energy drops ignores this.
  - **Source:** `RobotPeer.fireBullets`. · **Status:** tested: `BulletMotionReplayTest` (the probes request powers
    below 0.1 and above 3).
- **G-37 Robot boxes are floats.**
  - **What:** the engine stores a robot's bounding box as `Rectangle2D.Float` (`BoundingRectangle`), so its corner
    is rounded to float, about 3e-5 px at 500 px. Bullet hits and robot collisions are tested against the rounded
    box, in double arithmetic.
  - **Bites when:** an exact hit predictor grazes a box edge. The effect is below 1e-4 px; none of the recorded hits
    depends on it.
  - **Source:** `net.sf.robocode.battle.BoundingRectangle`, `RobotPeer.updateBoundingBox`. · **Status:** verified;
    `RobotBox` repeats it, the recordings do not exercise it.

## Movement

- **G-07 Acceleration 1, deceleration 2, and the rule through zero.**
  - **What:** when reversing, the robot brakes at 2 for the part of the turn it needs to reach zero and accelerates
    at 1 for the rest (`maxDecel`): from 1 it reaches −0.5, from 1.5 −0.25, from 2 exactly 0. The goal velocity is
    also limited by the remaining distance, so that the robot stops exactly at its end (`getMaxVelocity`).
  - **Bites when:** a precise predictor that is "almost right" drifts by a few pixels and breaks surfing.
  - **Source:** `RobotPeer.getNewVelocity`, `getMaxVelocity`, `maxDecel`. · **Status:** tested:
    `RobotMotionReplayTest`.
- **G-08 The turn rate depends on the velocity before this turn's acceleration.**
  - **What:** the rate is `(0.4 + 0.6 · (1 − |v| / 8)) · toRadians(10)`, with v from the start of the turn, capped by
    the max turn rate. The wiki's 10 − 0.75·|v| degrees is the same number on paper but rounds differently.
  - **Bites when:** an exact predictor uses the new velocity (thousands of mismatches in the replay) or the wiki
    formula (hundreds of last-bit heading mismatches).
  - **Source:** `RobotPeer.updateHeading`. · **Status:** tested: `RobotMotionReplayTest`, with both variants
    checked as failing mutations.
- **G-09 Gun and radar rotation stack on the body rotation**, unless the `setAdjust*` flags are set.
  - **What:** the adjust flags do not stop the gun or radar from turning with the body; they subtract the body's
    turn from the gun's (radar's) remaining turn, which the gun (radar) then turns back within the same engine turn.
    The radar also turns with the gun unless adjusted for it.
  - **Source:** `RobotPeer.updateHeading`, `updateGunHeading`. · **Status:** tested: `RobotMotionReplayTest`.
- **G-10 Robot bounding box.**
  - **What:** 36×36 px; the robot's center stays 18 px from the walls. XanderCat uses 40.
  - **Bites when:** wrong precise intersection or wall distance.
  - **Source:** `RobotPeer.WIDTH`, `HEIGHT`, `checkWallCollision`. · **Status:** tested for walls:
    `RobotMotionReplayTest`; robot–robot intersection to-test.
- **G-11 Wall hits.**
  - **What:** an AdvancedRobot takes max(|v|/2 − 1, 0) damage and stops. It is pushed back along its heading, so
    both coordinates change unless it hits head-on, and its remaining distance is dropped. When the move crossed two
    walls, the event's bearing is that of the horizontal one, even if the robot ends up touching only the other.
  - **Bites when:** an **enemy energy drop from a wall hit is taken for a shot** (false wave).
  - **Source:** wiki; classic bug class; `RobotPeer.checkWallCollision`. · **Status:** tested:
    `RobotMotionReplayTest` (movement), `EnergyReplayTest` (damage), `EventReplayTest` (`HitWallEvent` bearing).
- **G-12 Robot collisions.**
  - **What:** 0.6 damage to each robot. Only the robot moving towards the other is at fault: it is pushed back by its
    move, stops and does not turn its body next turn. The victim is not moved. Both get a `HitRobotEvent` in the
    same turn. Ram score goes only to the robot at fault.
  - **Bites when:** an energy drop from a ram is taken for a shot.
  - **Source:** wiki; `RobotPeer.checkRobotCollision`. · **Status:** tested: `RobotMotionReplayTest`,
    `EnergyReplayTest`, `EventReplayTest` (who rammed whom comes from the events; which pairs collide is to-test).
- **G-38 The inactivity penalty needs 10 energy lost in total.**
  - **What:** the engine zaps every robot by 0.1 per turn once more than `inactivityTime` (450) turns passed since
    the robots last lost 10 energy in total. Losses add up across robots, turns and rounds; each full 10 restarts
    the count, the turn count also restarts each round. Firing, bullet damage, rams (both robots) and deaths (10 each)
    count; regained energy and wall damage do not. Below 0.1 the robot is disabled; the next zap kills it.
  - **Bites when:** an enemy's 0.1 drops are taken for shots, or a bot assumes that any damage resets the count.
  - **Source:** `Battle.resetInactiveTurnCount`, `updateRobots`, `RobotPeer.zap`, `kill`. · **Status:** tested:
    `EnergyReplayTest` with `Inactivity` (the `inactive` scenario of probes that never fire).
- **G-35 Hidden overdrive state.**
  - **What:** the engine remembers whether a robot is braking for the end of its distance (`isOverDriving`). When
    that braking brings the velocity within 1e-5 of zero, the rest of the distance is dropped. The flag is not part
    of any snapshot, and the engine never resets it, not even between rounds. In the first turn of a round it is
    recomputed unless the robot moves less than 1e-5, so the carry-over has no practical effect.
  - **Bites when:** a predictor without the flag keeps a distance the engine dropped (105 mismatches in the replay).
  - **Source:** `RobotPeer.updateMovement`. · **Status:** tested: `RobotMotionReplayTest` (the flag); carry-over
    across rounds verified, no effect in the recordings.
- **G-36 A finished body turn snaps to a multiple of 90°.**
  - **What:** when the body turn completes, a heading within 1e-5 rad of 0, π/2, π or 3π/2 becomes exactly that
    angle (`Utils.normalNearAbsoluteAngle`). A head-on wall hit then moves the robot along one axis only.
  - **Bites when:** an exact predictor turns to a cardinal heading.
  - **Source:** `RobotPeer.updateHeading`. · **Status:** tested: `RobotMotionReplayTest` (the probes turn to
    within 2e-5 rad of quarter headings on purpose).

## Sensing and events

- **G-13 Radar scans the arc swept this tick.**
  - **What:** the pie between the radar heading before and after the turn, radius 1200, also tested along its start
    edge. It sees every live robot whose box it touches, at the positions after all robots moved, and reports
    energy, heading and velocity from that moment. A robot that neither moved nor turned its body, gun or radar does
    not scan at all (unless it calls `scan()`).
  - **Source:** `RobotPeer.performMove`, `scan`, `intersects`. · **Status:** tested: `EventReplayTest` (every scan
    and every field, against `RadarScan`).
- **G-14 Enemy energy can change without a shot:** our hits (energy loss), its hits on us (energy gain of 3p), wall
  hits, rams, hits by third parties in melee, inactivity zapping.
  - A disabled robot (energy 0) still gains 3p when one of its bullets in flight hits.
  - Within a turn the engine applies these in a fixed sequence of phases (fire; bullets in random order; each robot's
    move with its wall and ram damage and the penalty, in random order), so the last bits of the energy depend on
    the order.
  - **Bites when:** naive energy-drop wave detection.
  - **Source:** general; melee wave detection in Diamond; `RobotPeer`, `BulletPeer`. · **Status:** tested:
    `EnergyReplayTest` (bit for bit under some processing order).
- **G-39 Events carry a bullet where its move ended.** `HitByBulletEvent` and `BulletHitEvent` are created before
  the engine moves the bullet back to the start of its move when that lies inside the victim's box (G-03), so the
  event's bullet position can differ from the snapshot's.
  - **Bites when:** a bot treats the event's bullet position as the impact point recorded by the engine. The point is
    on the bullet's line either way.
  - **Source:** `BulletPeer.checkRobotCollision`. · **Status:** tested: `EventReplayTest` (41 such hits).
- **G-40 A turn's events arrive in the same turn.** Everything that happens in the engine's update of turn t
  (bullet hits, walls, rams, scans, deaths) reaches the robot when it acts in turn t, stamped t.
  - **Source:** `RobotPeer.executeImpl`, `EventManager.addImpl` (the robot stamps events when it receives them).
    · **Status:** tested: `EventReplayTest`.
- **G-15 Event handlers that take turns are interrupted** by `EventInterruptedException` when events nest.
  - **Source:** wiki Game Physics. · **Status:** wiki.
- **G-16 Time resets every round.** Store (round, tick) pairs.
  - **Source:** `BaseBattle.initializeRound`. · **Status:** tested: `recordProbes` integrity checks require the
    probe's `getTime()` to equal the snapshot turn in every turn of every round.

## Timing and CPU

- **G-17 Painting relaxes the turn time limit** to `DEBUG_TURN_WAIT_MILLIS` = 10 min. No skipped turns show up while
  watching, so the bot must measure and display its own decision time.
  - The system property `debug=true` has the same effect for all robots, including the round-start wait. Its other
    effects (painting buffer limit, console quota, `System.err`) do not touch the rules. `recordProbes` uses it.
  - **Source:** `Battle.java`, `RobocodeProperties`, `HostManager`. · **Status:** verified; recordings are
    identical with and without debug mode.
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
  - **What:** `RandomFactory.resetDeterministic(seed)` makes a battle replay exactly: start positions and the
    processing order follow the seed. The `RANDOMSEED` system property seeds the same generator when a battle
    starts (`BattleManager`).
    - **Not controlled:** `Math.random()`. The patch for it needs reflection into `java.lang` (`Math`'s generator
      field, `Field.modifiers`) that current JVMs deny. The failure is caught and only logged when `RANDOMSEED` is
      set. Robots' own unseeded generators are not controlled either.
    - **Portability:** replays are bit-identical across processes and across Java 17, 21 and 25 (one arm64 Mac,
      2026-10-08).
  - **Bites when:** a battle "replays" with an opponent or bot that uses `Math.random()` or `new Random()`.
  - **Source:** `RandomFactory`, `BattleManager`. · **Status:** tested: `recordProbes` (replay checks; "Math.random()
    is not seeded"); cross-JVM observed.
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
- **G-33 One random generator for the harness, the engine and the robots.**
  - **What:** `RandomFactory` lives in `robocode.jar`, which the engine's own classloader leaves out, so the
    control-API client, the engine and robots calling `Utils.getRandom()` share one instance. Seeding it in the
    harness seeds the engine and the robots' `Utils.getRandom()`.
  - **Bites when:**
    - a robot draws from `Utils.getRandom()`: its draws shift the engine's later draws (processing order, the next
      round's start positions), so two bot versions run on one seed diverge once their draws differ;
    - you read `EngineClassLoader` alone: it isolates `robocode.control` classes, which suggests the engine has its
      own copy. It does not, because `Container.findJars` excludes `robocode.jar`.
  - **Source:** `Container.findJars`, `EngineClassLoader` (robocode.core); `RobotClassLoader` (robocode.host).
    · **Status:** tested: `recordProbes` ("Utils.getRandom() in robots is seeded", "shared generator"; all replays
    are seeded from the harness only). The bot avoids the generator; `RandomnessRulesTest` enforces it.
- **G-34 A robot that died this turn races with the turn snapshot.**
  - **What:** the engine wakes a robot killed this turn but waits only for live robots. The dead robot's last turn
    (event handlers, then `execute()`, which stores its commands before throwing `DeathException`) runs while the
    snapshot is taken, so its debug properties and output in that snapshot can differ between runs.
  - If the dead robot draws from `Utils.getRandom()` in that last turn, it races with the engine for the shared
    generator (G-33), and the rest of the battle can differ between runs. Observed once (2026-10-08): two robots died
    in the same turn of round 1, and round 2 started at different positions in two runs with one seed.
  - **Bites when:** recordings or tools read debug properties of dead robots; a bot draws from `Utils.getRandom()`.
  - **Source:** `Battle.wakeupSerial`, `RobotPeer.executeImpl`. · **Status:** verified; `TurnRecorder` leaves the
    debug properties out, and `UtilsRandomProbe` stops drawing once dead (10 reruns identical since).
- **G-41 Fixed starts hold in every round, unless a robot is in the way.**
  - **What:** a battle's initial positions (the string `(x,y,heading),…` of `RobocodeEngine.runBattle`, or the
    specification's `RobotSetup` array) apply to every round, not only the first. Headings are in degrees. Robot i
    starts at its position unless that box overlaps a robot already placed in this round (a lower index) or a robot
    not placed yet (a higher index), which still stands where it ended the previous round. Then the engine draws a
    random spot from the shared generator (G-33), as if there were no start.
    - The parser drops every character other than digits and dots from each number, so `-5` reads as 5 and `1.0E-4`
      as 1.04.
    - The `RobotSetup` array must have one entry per contestant, but the engine indexes it by robot, so in a team
      battle it can only place the first robots. The string has no such limit.
    - When the battle is created, the engine draws three numbers from the shared generator for every listed start,
      used or not (they fill in `?` values).
  - **Bites when:** paired battles assume equal starts; team battles use `RobotSetup[]`; generated values print with
    a sign or an exponent.
  - **Source:** `Battle.computeInitialPositions`, `RobotPeer.initializeRound`, `RobotPeer.validSpot`,
    `BattleProperties.setInitialPositions`, `BattleSpecification`. · **Status:** tested: `recordProbes` ("Fixed
    starts are used in every round unless a robot is in the way"); the `RobotSetup` limit verified in the source.
    `BattleRunner` passes starts as the string and counts robot rounds that missed them.
- **G-42 A team jar lists its robots in the repository too.**
  - **What:** `cz.libors.OgiTeam_0.1.jar` contains `cz/libors/Ogi.properties`, so the local repository lists
    `cz.libors.Ogi 0.1` twice, once per jar, and a lookup by name can return the one from the team jar. A team loads
    its members from its own jar first, so several versions of the team can be deployed side by side.
    `RobotSpecification.getJarFile()` is never null: it wraps the repository root's URL, e.g. `jar:file:/…/X.jar!`,
    in a `File`.
  - **Bites when:** identifying or hashing the jar a robot came from; deploying several versions.
  - **Source:** `RepositoryManager.getRobotItems`, `RobotSpecItem.createRobotSpecification`; harness observation.
    · **Status:** tested: `recordProbes` ("listed by the robot jar and the team jar"). `BattleRunner` prefers the jar
    named `<name>_<version>.jar`. Teams using their own members observed on 2026-10-08 with four versions deployed.
- **G-30 Approximate trigonometry breaks prediction.** Lookup-table sin/cos must not be used where we have to match the
  engine.
  - **Source:** project convention. · **Status:** to-test (the precise predictor's differential test).
