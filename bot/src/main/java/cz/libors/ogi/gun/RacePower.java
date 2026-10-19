package cz.libors.ogi.gun;

import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.RobotMotion;

/**
 * The bullet power for an energy race in a duel: once our energy is low, the round goes to the robot whose energy
 * lasts longer, and our power sets how fast both run out.
 *
 * <p>Each robot loses energy through its own shots (a shot costs its power p, a hit returns 3p) and through the other's
 * hits (4p + 2 max(p − 1, 0)). With each gun firing as soon as it is cool, a shot of power p every 10 + 2p turns, our
 * hit rate h(p) and theirs g, and their power q, the losses per turn are
 *
 * <pre>
 *   ours   = p (1 − 3 h(p)) / (10 + 2p) + g · damage(q) / (10 + 2q)
 *   theirs = q (1 − 3 g) / (10 + 2q) + h(p) · damage(p) / (10 + 2p)
 * </pre>
 *
 * and the power with the largest ratio theirs / ours makes the time we last longest against the time they last,
 * whatever the two energies are. A weak bullet costs us almost nothing, so against an enemy that hits us rarely and
 * keeps firing, its own shots run it down first; against one that hits us often, our damage has to end the round
 * sooner. A faster bullet hits more often: h(p) is our measured hit rate scaled by the maximum escape angle of the
 * normal power, which it is taken as measured at, over that of p (bot.md "Bullet power").
 */
public final class RacePower {

    /** The powers the race chooses from, besides the power the other rules give. */
    static final double[] CANDIDATES = {0.1, 0.2, 0.35, 0.5, 0.75, 1.0, 1.45};

    private RacePower() {
    }

    /**
     * The race's power, at most {@code normal}.
     *
     * @param ourHit     our battle-long hit rate on the enemy, at whatever powers our bullets had; taken as measured
     *                   at {@code normal}
     * @param theirHit   the enemy's hit rate on us
     * @param theirPower the power of the enemy's shots
     * @param normal     the power the other rules give
     */
    public static double choose(double ourHit, double theirHit, double theirPower, double normal) {
        double best = normal;
        double bestRatio = ratio(normal, ourHit, theirHit, theirPower, normal);
        for (double p : CANDIDATES) {
            if (p >= normal) {
                break;
            }
            double r = ratio(p, ourHit, theirHit, theirPower, normal);
            if (r > bestRatio) {
                best = p;
                bestRatio = r;
            }
        }
        return best;
    }

    /** Their energy lost per turn over ours if we fire {@code p}; +∞ while ours does not fall. */
    static double ratio(double p, double ourHit, double theirHit, double theirPower, double measuredAt) {
        double h = Math.min(1, ourHit * escape(measuredAt) / escape(p));
        double ours = p * (1 - 3 * h) / period(p) + theirHit * BulletMotion.damage(theirPower) / period(theirPower);
        double theirs = theirPower * (1 - 3 * theirHit) / period(theirPower) + h * BulletMotion.damage(p) / period(p);
        return ours <= 0 ? Double.POSITIVE_INFINITY : theirs / ours;
    }

    /** Turns between shots of a gun that fires as soon as it is cool. */
    private static double period(double power) {
        return 10 + 2 * power;
    }

    private static double escape(double power) {
        return Math.asin(RobotMotion.MAX_VELOCITY / BulletMotion.speed(power));
    }
}
