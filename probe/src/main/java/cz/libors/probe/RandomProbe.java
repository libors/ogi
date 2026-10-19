package cz.libors.probe;

import robocode.AdvancedRobot;
import robocode.Bullet;
import robocode.BulletHitBulletEvent;
import robocode.BulletHitEvent;
import robocode.BulletMissedEvent;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.HitWallEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;
import robocode.SkippedTurnEvent;
import robocode.util.Utils;

import java.util.Objects;
import java.util.Random;
import java.util.StringJoiner;

/**
 * Probe robot for engine recordings. It drives the engine with random commands, including edge cases such as
 * reversals through zero, infinite turns, turns ending next to a multiple of 90° and out-of-range limits, and
 * reports them through debug properties, so a recording of a probe battle holds the engine's inputs as well as its
 * response.
 *
 * <p>Debug properties, values comma-separated, numbers as {@link Double#toString} prints them, angles in radians and
 * Robocode's convention:
 * <ul>
 *   <li>{@code rem} every turn: time, then distance, body, gun and radar turn remaining as the engine left them after
 *       this turn's move.</li>
 *   <li>{@code cmd} every turn: time, distance, body, gun and radar turn remaining, max velocity, max turn rate, the
 *       adjust flags gun-for-robot, radar-for-gun and radar-for-robot, the fire power requested this turn ({@code -}
 *       if none) and whether the robot API accepted it. The engine applies these in the next turn.</li>
 *   <li>{@code ev} once per event received this turn, in the order the handlers ran: event type, event time, then
 *       the event's fields; a death also with {@code getOthers()} in its handler.</li>
 *   <li>{@code fireGetters} in a turn it tries to fire: time, the power requested, the gun heat and energy before
 *       the call and after it, whether the robot API accepted the shot, and the call: {@code setFire} in odd turns,
 *       {@code setFireBullet} in even ones (engine-model G-49).</li>
 *   <li>{@code fireAgain} after an accepted shot, a second {@code setFireBullet} with the same power in the same
 *       turn: time, the power, whether the robot API accepted it, and the gun heat and energy after it.</li>
 * </ul>
 *
 * <p>The random sequence is seeded from the start position, which the engine draws from its own random generator,
 * so a battle with a fixed engine seed replays exactly. Subclasses swap the source in {@link #nextRandom()}.
 */
public class RandomProbe extends AdvancedRobot {

    private static final double ADJUST_CHANCE = 0.01;

    private Random random;
    // The engine's defaults; it clamps both to the rules' maximum.
    private double maxVelocity = Double.MAX_VALUE;
    private double maxTurnRate = Double.MAX_VALUE;

    // Chance per turn of a new command, per channel, drawn once per round.
    private double moveChance;
    private double turnChance;
    private double gunChance;
    private double radarChance;
    private double limitChance;
    private double fireChance;

    @Override
    public void run() {
        random = new Random(Objects.hash(getRoundNum(), getX(), getY(), getHeadingRadians()));
        moveChance = commandChance();
        turnChance = commandChance();
        gunChance = commandChance();
        radarChance = commandChance();
        limitChance = 0.01 + 0.04 * nextRandom();
        fireChance = 0.05 + 0.3 * nextRandom();
        while (true) {
            report("rem", getTime(), getDistanceRemaining(), getTurnRemainingRadians(), getGunTurnRemainingRadians(),
                    getRadarTurnRemainingRadians());
            issueCommands();
            execute();
        }
    }

    /** Whether the probe fires at all; robots that never fire make the engine apply its inactivity penalty. */
    protected boolean fires() {
        return true;
    }

    /** Whether the radar gets random commands; subclasses that steer it return false. */
    protected boolean randomRadar() {
        return true;
    }

    /** Last commands of the turn, after the random ones; they are reported in {@code cmd} too. */
    protected void finishCommands() {
    }

    /** Uniform random number in [0, 1), the probe's only source of randomness. */
    protected double nextRandom() {
        return random.nextDouble();
    }

    private void issueCommands() {
        if (chance(moveChance)) {
            setAhead(distance());
        }
        if (chance(turnChance)) {
            setTurnRightRadians(chance(0.15) ? turnToQuarter() : angle());
        }
        if (chance(gunChance)) {
            setTurnGunRightRadians(angle());
        }
        if (randomRadar() && chance(radarChance)) {
            setTurnRadarRightRadians(chance(0.5) ? infinite() : angle());
        }
        if (chance(limitChance)) {
            maxVelocity = velocityLimit();
            setMaxVelocity(maxVelocity);
        }
        if (chance(limitChance)) {
            double degrees = turnRateLimit();
            setMaxTurnRate(degrees);
            maxTurnRate = Math.toRadians(degrees);
        }
        if (chance(ADJUST_CHANCE)) {
            setAdjustGunForRobotTurn(chance(0.5));
        }
        if (chance(ADJUST_CHANCE)) {
            setAdjustRadarForGunTurn(chance(0.5));
        }
        if (chance(ADJUST_CHANCE)) {
            setAdjustRadarForRobotTurn(chance(0.5));
        }
        Object power = "-";
        boolean fired = false;
        if (fires() && chance(fireChance)) {
            double p = firePower();
            power = p;
            double heat = getGunHeat();
            double energy = getEnergy();
            // Both calls end in the same proxy method; setFire returns nothing, so its shot shows in the gun heat.
            boolean bySetFire = getTime() % 2 == 1;
            if (bySetFire) {
                setFire(p);
                fired = getGunHeat() != heat;
            } else {
                fired = setFireBullet(p) != null;
            }
            // engine-model G-49: the getters include the shot set in this turn, and a second shot is refused.
            report("fireGetters", getTime(), p, heat, energy, getGunHeat(), getEnergy(), fired,
                    bySetFire ? "setFire" : "setFireBullet");
            if (fired) {
                Bullet again = setFireBullet(p);
                report("fireAgain", getTime(), p, again != null, getGunHeat(), getEnergy());
            }
        }
        finishCommands();
        report("cmd", getTime(), getDistanceRemaining(), getTurnRemainingRadians(), getGunTurnRemainingRadians(),
                getRadarTurnRemainingRadians(), maxVelocity, maxTurnRate, isAdjustGunForRobotTurn(),
                isAdjustRadarForGunTurn(), isAdjustRadarForRobotTurn(), power, fired);
    }

    /** Mostly long holds, sometimes a new command almost every turn. */
    private double commandChance() {
        double r = nextRandom();
        return 0.01 + 0.5 * r * r;
    }

    private double distance() {
        double r = nextRandom();
        if (r < 0.2) {
            // Against the current direction: the robot decelerates through zero.
            return -direction() * (1 + 199 * nextRandom());
        } else if (r < 0.35) {
            // Ends before the robot reaches full speed or stops.
            return 10 * nextRandom() - 5;
        } else if (r < 0.4) {
            return 0;
        } else if (r < 0.45) {
            return infinite();
        }
        return 800 * nextRandom() - 400;
    }

    /** Sign of the current velocity, or a random sign when standing still. */
    private double direction() {
        double v = getVelocity();
        if (v != 0) {
            return Math.signum(v);
        }
        return chance(0.5) ? -1 : 1;
    }

    private double angle() {
        double r = nextRandom();
        if (r < 0.25) {
            return Math.toRadians(30 * nextRandom() - 15);
        } else if (r < 0.3) {
            return 0;
        } else if (r < 0.35) {
            return infinite();
        }
        return 4 * Math.PI * nextRandom() - 2 * Math.PI;
    }

    /** A body turn that ends within 2e-5 rad of a multiple of 90°, on either side. */
    private double turnToQuarter() {
        double target = Math.floor(4 * nextRandom()) * Math.PI / 2 + (4 * nextRandom() - 2) * 1e-5;
        return Utils.normalRelativeAngle(target - getHeadingRadians());
    }

    private double infinite() {
        return chance(0.5) ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
    }

    /** Pixels per turn, including values outside 0–8. */
    private double velocityLimit() {
        double r = nextRandom();
        if (r < 0.3) {
            return 8;
        } else if (r < 0.4) {
            return 0;
        } else if (r < 0.5) {
            return 8 + 4 * nextRandom();
        } else if (r < 0.6) {
            return -8 * nextRandom();
        }
        return 8 * nextRandom();
    }

    /** Degrees per turn, including values outside 0–10. */
    private double turnRateLimit() {
        double r = nextRandom();
        if (r < 0.3) {
            return 10;
        } else if (r < 0.4) {
            return 0;
        } else if (r < 0.5) {
            return 10 + 10 * nextRandom();
        } else if (r < 0.6) {
            return -10 * nextRandom();
        }
        return 10 * nextRandom();
    }

    /** Includes powers outside 0.1–3. */
    private double firePower() {
        double r = nextRandom();
        if (r < 0.1) {
            return 0.1;
        } else if (r < 0.2) {
            return 3;
        } else if (r < 0.25) {
            return 0.1 * nextRandom();
        } else if (r < 0.3) {
            return 3 + 2 * nextRandom();
        }
        return 0.1 + 2.9 * nextRandom();
    }

    private boolean chance(double p) {
        return nextRandom() < p;
    }

    private void report(String key, Object... fields) {
        StringJoiner value = new StringJoiner(",");
        for (Object field : fields) {
            value.add(String.valueOf(field));
        }
        setDebugProperty(key, value.toString());
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        report("ev", "ScannedRobot", e.getTime(), e.getName(), e.getBearingRadians(), e.getDistance(),
                e.getHeadingRadians(), e.getVelocity(), e.getEnergy());
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        report("ev", "HitWall", e.getTime(), e.getBearingRadians());
    }

    @Override
    public void onHitRobot(HitRobotEvent e) {
        report("ev", "HitRobot", e.getTime(), e.getName(), e.getBearingRadians(), e.getEnergy(), e.isMyFault());
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        Bullet b = e.getBullet();
        report("ev", "HitByBullet", e.getTime(), b.getName(), b.getPower(), b.getHeadingRadians(), b.getX(), b.getY());
    }

    @Override
    public void onBulletHit(BulletHitEvent e) {
        Bullet b = e.getBullet();
        report("ev", "BulletHit", e.getTime(), e.getName(), e.getEnergy(), b.getPower(), b.getHeadingRadians(),
                b.getX(), b.getY());
    }

    @Override
    public void onBulletHitBullet(BulletHitBulletEvent e) {
        Bullet b = e.getBullet();
        Bullet other = e.getHitBullet();
        report("ev", "BulletHitBullet", e.getTime(), b.getPower(), b.getHeadingRadians(), b.getX(), b.getY(),
                other.getName(), other.getPower(), other.getHeadingRadians(), other.getX(), other.getY());
    }

    @Override
    public void onBulletMissed(BulletMissedEvent e) {
        Bullet b = e.getBullet();
        report("ev", "BulletMissed", e.getTime(), b.getPower(), b.getHeadingRadians(), b.getX(), b.getY());
    }

    @Override
    public void onRobotDeath(RobotDeathEvent e) {
        // engine-model G-52: the robot that died this turn is no longer counted.
        report("ev", "RobotDeath", e.getTime(), e.getName(), getOthers());
    }

    @Override
    public void onSkippedTurn(SkippedTurnEvent e) {
        report("ev", "SkippedTurn", e.getTime(), e.getSkippedTurn());
    }
}
