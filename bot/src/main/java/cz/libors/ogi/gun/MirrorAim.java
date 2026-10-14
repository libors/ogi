package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Aims at a robot that goes where a fixed map sends our own position some turns earlier: a mirror mover, which drives
 * to the point mirror of where we were through the field's centre, a few turns late or a few dozen turns late. Such a
 * robot moves as well as we do against a gun that reads its own movement, but where it will be is where we were, or
 * will be, a lag earlier.
 *
 * <p>For every lag k from 0 to {@link #MAX_LAG} turns, the enemy's position at the end of turn t is fitted by least
 * squares on ours at the end of turn t − k, as an affine map (any mirror, any centre, a shrink at the walls). The sums
 * last for the battle (static, per robot, engine-model G-22) and grow by one pair per lag in every turn with a scan;
 * the lag with the least residual wins, once it has {@link #MIN_PAIRS} pairs and the map explains at least
 * {@link #MIN_FIT} of the variance of the enemy's position. Robots that drive at us fit too (they go where we were),
 * robots that orbit us do not.
 *
 * <p>The aim follows the bullet: the target at the end of a turn is the map of our position a lag before, and the
 * bullet aims at the first such point it can reach. Our position before the end of this turn is known; after it, the
 * caller's prediction (the surfer's planned path) stands for it, its last point beyond its end. A short lag needs our
 * position well after the shot, so the prediction matters most against the closest mirrors.
 */
public final class MirrorAim {

    static final int MAX_LAG = 60;
    /** Pairs of positions a lag needs before its map counts. */
    static final int MIN_PAIRS = 200;
    /** The share of the variance of the enemy's position the map must explain. */
    static final double MIN_FIT = 0.5;
    private static final int MAX_FLIGHT_TURNS = 150;
    private static final int SUMS = 13;
    /** Per enemy and lag, the sums of the least squares over the battle. */
    private static final Map<String, double[][]> SUMS_BY_ENEMY = new HashMap<>();

    /** A fitted map: the target at turn t is (a11 x + a12 y + bx, a21 x + a22 y + by) of our position at t − lag. */
    public record Fit(double a11, double a12, double a21, double a22, double bx, double by, int lag, double explained) {

        double x(double ourX, double ourY) {
            return a11 * ourX + a12 * ourY + bx;
        }

        double y(double ourX, double ourY) {
            return a21 * ourX + a22 * ourY + by;
        }
    }

    private final Field field;
    /** Our positions at the end of the last turns of this round, by turn modulo the buffer. */
    private final double[] ourX = new double[MAX_LAG + 1];
    private final double[] ourY = new double[MAX_LAG + 1];
    private final long[] ourTime = new long[MAX_LAG + 1];
    private long latest = -1;

    public MirrorAim(Field field) {
        this.field = field;
        Arrays.fill(ourTime, -1);
    }

    /** Our position at the end of turn {@code time}; call it every turn before {@link #scanned}. */
    public void ours(long time, double x, double y) {
        int i = index(time);
        ourX[i] = x;
        ourY[i] = y;
        ourTime[i] = time;
        latest = time;
    }

    /**
     * The enemy's position at the end of turn {@code time}: one more pair for every lag whose position of ours is
     * known.
     */
    public void scanned(String name, long time, double x, double y) {
        double[][] sums = SUMS_BY_ENEMY.computeIfAbsent(name, n -> new double[MAX_LAG + 1][SUMS]);
        for (int k = 0; k <= MAX_LAG; k++) {
            int i = index(time - k);
            if (ourTime[i] == time - k) {
                add(sums[k], ourX[i], ourY[i], x, y);
            }
        }
    }

    /** The map of the lag with the least residual, or null while none explains enough of the enemy's moves. */
    public Fit fit(String name) {
        double[][] sums = SUMS_BY_ENEMY.get(name);
        if (sums == null) {
            return null;
        }
        Fit best = null;
        double bestResidual = Double.POSITIVE_INFINITY;
        for (int k = 0; k <= MAX_LAG; k++) {
            double[] s = sums[k];
            if (s[0] < MIN_PAIRS) {
                continue;
            }
            double[] f = solve(s);
            if (f != null && f[6] < bestResidual) {
                bestResidual = f[6];
                best = new Fit(f[0], f[1], f[2], f[3], f[4], f[5], k, f[7]);
            }
        }
        return best != null && best.explained() >= MIN_FIT ? best : null;
    }

    /**
     * The heading (mathematical) of a bullet fired in turn {@code fireTime} at {@code speed} towards where the map puts
     * the target when the bullet reaches it.
     *
     * @param future our predicted positions {x, y} at the end of turns {@code fireTime}, {@code fireTime + 1}, …; the
     *               first is the bullet's origin, and the last stands for every later turn
     */
    public double aim(Fit fit, long fireTime, double speed, double[][] future) {
        double originX = future[0][0];
        double originY = future[0][1];
        double x = originX;
        double y = originY;
        for (int n = 1; n <= MAX_FLIGHT_TURNS; n++) {
            long then = fireTime + n - fit.lag();
            double ux;
            double uy;
            if (then >= fireTime) {
                double[] p = future[(int) Math.min(then - fireTime, future.length - 1)];
                ux = p[0];
                uy = p[1];
            } else {
                int i = index(known(then));
                ux = ourX[i];
                uy = ourY[i];
            }
            x = field.clampX(fit.x(ux, uy));
            y = field.clampY(fit.y(ux, uy));
            if (Math.hypot(x - originX, y - originY) <= n * speed) {
                break;
            }
        }
        return Angles.bearing(originX, originY, x, y);
    }

    /** The turn nearest {@code time} whose position of ours is known, at most the latest one. */
    private long known(long time) {
        long t = Math.min(time, latest);
        while (t < latest && ourTime[index(t)] != t) {
            t++;
        }
        return t;
    }

    private static int index(long time) {
        return (int) Math.floorMod(time, (long) (MAX_LAG + 1));
    }

    private static void add(double[] s, double ux, double uy, double ex, double ey) {
        s[0]++;
        s[1] += ux;
        s[2] += uy;
        s[3] += ux * ux;
        s[4] += ux * uy;
        s[5] += uy * uy;
        s[6] += ex;
        s[7] += ey;
        s[8] += ux * ex;
        s[9] += uy * ex;
        s[10] += ux * ey;
        s[11] += uy * ey;
        s[12] += ex * ex + ey * ey;
    }

    /**
     * The least squares of the enemy's x and y on (our x, our y, 1): {a11, a12, a21, a22, bx, by, residual variance,
     * share of the variance explained}; null if our positions do not span the plane.
     */
    private static double[] solve(double[] s) {
        double n = s[0];
        // Centred moments: the normal equations of the slopes alone, then the offsets from the means.
        double mx = s[1] / n;
        double my = s[2] / n;
        double ex = s[6] / n;
        double ey = s[7] / n;
        double sxx = s[3] / n - mx * mx;
        double sxy = s[4] / n - mx * my;
        double syy = s[5] / n - my * my;
        double det = sxx * syy - sxy * sxy;
        if (det <= 1e-9 * (sxx * syy + 1e-12)) {
            return null;
        }
        double cxX = s[8] / n - mx * ex;
        double cyX = s[9] / n - my * ex;
        double cxY = s[10] / n - mx * ey;
        double cyY = s[11] / n - my * ey;
        double a11 = (syy * cxX - sxy * cyX) / det;
        double a12 = (sxx * cyX - sxy * cxX) / det;
        double a21 = (syy * cxY - sxy * cyY) / det;
        double a22 = (sxx * cyY - sxy * cxY) / det;
        double total = s[12] / n - ex * ex - ey * ey;
        double explained = a11 * cxX + a12 * cyX + a21 * cxY + a22 * cyY;
        double residual = Math.max(0, total - explained);
        return new double[] {a11, a12, a21, a22, ex - a11 * mx - a12 * my, ey - a21 * mx - a22 * my, residual,
                total > 0 ? explained / total : 0};
    }
}
