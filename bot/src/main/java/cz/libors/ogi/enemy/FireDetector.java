package cz.libors.ogi.enemy;

import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.Inactivity;
import cz.libors.ogi.physics.RobotMotion;

import java.util.Optional;

/**
 * Infers one enemy's shots from its energy, as seen in our scans (engine-model G-14).
 *
 * <p>Between two scans the enemy's energy changes by its shot and by everything else that happened to it. The
 * detector subtracts what we can observe: our bullets hitting it, its bullets hitting us (it regains 3p), rams with
 * us, the inactivity penalty, and an estimate of wall damage when it stopped at a wall. A drop of whole rams while
 * another robot was near counts as rams. What remains counts as a shot if it is a possible bullet power and the
 * enemy's gun, modelled like the engine's, was cool (G-05). In melee, hits among other robots are invisible and cause
 * false shots.
 *
 * <p>Feed it in turn order: the corrections of a turn ({@link #ourBulletHit}, {@link #hitUs}, {@link #rammed},
 * {@link #zapped}, {@link #nearOtherRobot}) before the turn's scan ({@link #scanned}); the engine delivers all of them
 * in the same turn (G-40). One detector per enemy and round.
 */
public final class FireDetector {

    private static final double EPSILON = 1e-6;
    private static final double MAX_VELOCITY = RobotMotion.MAX_VELOCITY;
    private static final double MAX_WALL_DAMAGE = RobotMotion.wallHitDamage(MAX_VELOCITY);
    /** Rams between two scans that the detector recognises in the energy; 5 would be 3.0, a common power. */
    private static final int MAX_RAMS = 4;
    /** The gun counts as cool below half a cooling step, which absorbs rounding in the estimated powers. */
    private static final double COOL_FRACTION = 0.5;

    private final String enemy;
    private final double coolingRate;
    private final int fieldWidth;
    private final int fieldHeight;

    private boolean seen;
    private long lastTime;
    private double lastX;
    private double lastY;
    private double lastVelocity;
    private double lastEnergy;
    /** Known energy changes since the last scan, losses positive. */
    private double knownLoss;
    private boolean nearOthers;

    /** The enemy's gun heat at the end of turn {@code heatTime}; all guns start at 3 (G-05). */
    private long heatTime;
    private double heat = 3;

    public FireDetector(String enemy, double coolingRate, int fieldWidth, int fieldHeight) {
        this.enemy = enemy;
        this.coolingRate = coolingRate;
        this.fieldWidth = fieldWidth;
        this.fieldHeight = fieldHeight;
    }

    /** Our bullet of this power hit the enemy. */
    public void ourBulletHit(double power) {
        knownLoss += BulletMotion.damage(power);
    }

    /** The enemy's bullet of this power hit us; it regains 3p. */
    public void hitUs(double power) {
        knownLoss -= BulletMotion.hitBonus(power);
    }

    /** We and the enemy collided; both lose the ram damage. */
    public void rammed() {
        knownLoss += RobotMotion.ROBOT_HIT_DAMAGE;
    }

    /**
     * Another robot came close enough to ram the enemy since the last scan. A ram among other robots is invisible
     * to us; it costs the enemy 0.6 per ram, a drop a shot rarely matches exactly.
     */
    public void nearOtherRobot() {
        nearOthers = true;
    }

    /** A turn with the inactivity penalty (G-38). */
    public void zapped() {
        knownLoss += Inactivity.ZAP;
    }

    /** A scan of the enemy at the end of turn {@code time}; returns the shot it reveals, if any. */
    public Optional<DetectedShot> scanned(long time, double x, double y, double velocity, double energy) {
        Optional<DetectedShot> shot = seen ? classify(time, x, y, velocity, energy) : Optional.empty();
        seen = true;
        lastTime = time;
        lastX = x;
        lastY = y;
        lastVelocity = velocity;
        lastEnergy = energy;
        knownLoss = 0;
        nearOthers = false;
        return shot;
    }

    private Optional<DetectedShot> classify(long time, double x, double y, double velocity, double energy) {
        double drop = lastEnergy - energy - knownLoss;
        if (lastEnergy == 0 || drop <= EPSILON) {
            return Optional.empty();
        }
        // The fire command was issued in some turn between the last scan and the turn before this one, most likely
        // in the first in which the modelled gun was cool. The model may be off after a missed or a false shot, so
        // it only rules out a shot when the gun cannot have cooled in time.
        long latest = time - 1;
        long fireTime = Math.max(lastTime, firstCoolTurn(lastTime));
        if (fireTime > latest) {
            return Optional.empty();
        }
        if (nearOthers && isRamDamage(drop)) {
            return Optional.empty();
        }
        double power = drop;
        boolean wallStop = velocity == 0 && atWall(x, y) && (lastVelocity != 0 || !atWall(lastX, lastY));
        if (wallStop && time > lastTime + 1 && drop <= MAX_WALL_DAMAGE + EPSILON) {
            // It hit a wall at an unknown speed between the scans; the drop may be wall damage alone.
            return Optional.empty();
        }
        if (wallStop) {
            // The impact speed lies between braking and accelerating from the last scanned speed.
            double speed = Math.abs(lastVelocity);
            double minDamage = RobotMotion.wallHitDamage(Math.max(0, speed - RobotMotion.DECELERATION));
            double maxDamage = RobotMotion.wallHitDamage(Math.min(MAX_VELOCITY, speed + RobotMotion.ACCELERATION));
            if (drop <= maxDamage + EPSILON) {
                return Optional.empty();
            }
            power = drop - Math.max(minDamage, RobotMotion.wallHitDamage(speed));
        }
        // A shot that spends the last energy can be weaker than the minimum power.
        boolean spentAll = energy == 0 && drop <= RobotMotion.MAX_BULLET_POWER + EPSILON;
        if (!spentAll && (power < RobotMotion.MIN_BULLET_POWER - EPSILON
                || power > RobotMotion.MAX_BULLET_POWER + EPSILON)) {
            return Optional.empty();
        }
        power = spentAll ? Math.min(power, RobotMotion.MAX_BULLET_POWER)
                : Math.min(Math.max(power, RobotMotion.MIN_BULLET_POWER), RobotMotion.MAX_BULLET_POWER);
        double fraction = (double) (fireTime - lastTime) / (time - lastTime);
        double originX = lastX + fraction * (x - lastX);
        double originY = lastY + fraction * (y - lastY);
        shotFired(fireTime, power);
        return Optional.of(new DetectedShot(enemy, fireTime, lastTime, latest, power, originX, originY, time));
    }

    /** The engine fires at the start of the next turn, then cools the gun in it. */
    private void shotFired(long fireTime, double power) {
        double h = 0;
        h += 1 + (power / 5);
        h -= coolingRate;
        if (h < 0) {
            h = 0;
        }
        heatTime = fireTime + 1;
        heat = h;
    }

    /** The turns from {@code now} until the enemy's gun is cool by the model of its gun heat, 0 if it is. */
    public long coolIn(long now) {
        return firstCoolTurn(now) - now;
    }

    /** Whether, by the model of its gun heat, the enemy's gun is cool at the end of turn {@code time}. */
    public boolean coolAt(long time) {
        return firstCoolTurn(time) <= time;
    }

    /** The first turn, not before {@code from}, at whose end the gun is cool. */
    private long firstCoolTurn(long from) {
        long t = heatTime;
        double h = heat;
        while (t < from || h >= COOL_FRACTION * coolingRate) {
            h -= coolingRate;
            if (h < 0) {
                h = 0;
            }
            t++;
        }
        return t;
    }

    private static boolean isRamDamage(double drop) {
        for (int rams = 1; rams <= MAX_RAMS; rams++) {
            if (Math.abs(drop - rams * RobotMotion.ROBOT_HIT_DAMAGE) < EPSILON) {
                return true;
            }
        }
        return false;
    }

    private boolean atWall(double x, double y) {
        double min = RobotMotion.HALF_SIZE;
        return Math.abs(x - min) < EPSILON || Math.abs(x - (fieldWidth - min)) < EPSILON
                || Math.abs(y - min) < EPSILON || Math.abs(y - (fieldHeight - min)) < EPSILON;
    }
}
