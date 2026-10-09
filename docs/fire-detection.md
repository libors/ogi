# Enemy fire detection

A bot never sees enemy bullets in flight. It infers each shot from the shooter's energy in its scans.
`cz.libors.ogi.enemy.FireDetector` does this for one enemy and round; `DetectedShot` is its result.

## Inputs

Everything comes from what the bot itself receives, in the turn it happens (engine-model G-40):

| Input | Source | Effect on the enemy's energy |
|---|---|---|
| `scanned(time, x, y, velocity, energy)` | `ScannedRobotEvent` | the measurement |
| `ourBulletHit(power)` | `BulletHitEvent` | −(4p + 2·max(p − 1, 0)) |
| `hitUs(power)` | `HitByBulletEvent` | +3p |
| `rammed()` | `HitRobotEvent` | −0.6 |
| `zapped()` | our own unexplained loss of 0.1 (engine-model G-38) | −0.1 |
| `nearOtherRobot()` | another robot, enemy or teammate, seen within 150 px of the enemy in the last 16 turns (`Neighbours`) | rams among others: −0.6 each |

The bot feeds all of them except `zapped()`: the inactivity penalty needs 450 turns in which the robots lose less than
10 energy, and Ogi fires whenever it can, so it can only happen once one side is already finished.

## Rules

1. **Drop:** previous scanned energy − current − the observed changes above.
2. **Fire time:** the enemy issued the fire command between the previous scan and the turn before this one. The
   estimate is the first turn of that range in which its gun was cool. The gun is modelled like the engine's: 3.0 at
   round start, −0.1 per turn, +1 + p/5 at a shot (engine-model G-05). The gun counts as cool below half a cooling
   step, which absorbs rounding in the estimated power. A drop is a shot only if the gun can have cooled in the
   range; the model does not narrow the reported range, because it is off after a missed or a false shot.
3. **Origin:** the enemy's position at the end of the fire turn. It is exact when that turn was scanned; otherwise
   it is interpolated between the two scans.
4. **Walls:** an enemy that stopped at a wall may have lost up to 3.
   - Between consecutive scans the impact speed lies between braking and accelerating from the last scanned speed.
     A drop within that damage range is the wall alone; above it, a shot whose power assumes the scanned speed.
   - After a gap, a drop of up to 3 is taken as the wall alone.
5. **Rams among other robots:** a drop of exactly 0.6, 1.2, 1.8 or 2.4 while another robot was near is taken as
   rams. 3.0 is never taken as rams, because it is a common power.
6. **Power:** a shot is a drop in [0.1, 3], clamped. A drop that empties the enemy may be smaller than 0.1: the
   engine fires min(energy, power).

## Quality

Measured by `FireDetectionTest` (`./gradlew engineTest`) on the probe recordings, 2026-10-08 (unchanged on
2026-10-09, since the test finds ram partners with the bot's own `Neighbours`). Every probe is an observer with only
its own information; detections are matched with the true bullets.

| Scenario | Radar | Precision | Recall | Exact fire time | Origin error |
|---|---|---|---|---|---|
| `watch-duel` | locked | 100 % | 99.9 % | 100 % of matches | 1e-13 px |
| `watch-melee` (8 robots) | spinning | 98.3 % | 92.1 % | 16 % of matches | 4.9 px mean |

The probes fire at random moments, so in melee, where scans of an enemy are about 8 turns apart, the "fire as soon
as the gun is cool" estimate is seldom exact; bots that fire as soon as they can are timed better.

## Known limits

- **Melee false shots** come from what the observer cannot see: other robots' bullets hitting the enemy (about two
  thirds of the remaining false shots in `watch-melee`), and walls hit and left between two scans. Attributing hits
  to the waves of other enemies would remove most of the first.
- **Missed shots** are almost all in scan gaps, where a shot can merge with a second shot, a hit or a wall hit
  into one drop.
- **Real shots of 0.6, 1.2, 1.8 or 2.4** are lost when another robot is near the shooter (rule 5).
- **A shot and a wall hit in the same turn** can hide the shot when the drop stays within the wall damage range.
