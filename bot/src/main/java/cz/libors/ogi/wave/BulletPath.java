package cz.libors.ogi.wave;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.BulletMotion;

import java.util.ArrayList;
import java.util.List;

/**
 * One of our bullets in the air: fired in turn {@code fireTime} from ({@code x}, {@code y}), our position at the end
 * of that turn, it is {@code (t − fireTime) × speed} further along its heading at the end of turn t (as a
 * {@link Wave}), until it leaves the field in turn {@code endTime} at the latest.
 *
 * <p>Its shadows on an enemy wave are the bearings whose bullets it would hit (engine-model G-04).
 */
public record BulletPath(Object id, long fireTime, double x, double y, double dx, double dy, double speed,
                         long endTime) {

    /** The chance of a collision that needs the engine to move one of the two bullets first. */
    public static final double MIXED_ORDER = 0.5;

    /**
     * A bullet fired in turn {@code time} from ({@code x}, {@code y}); heading mathematical. It is followed until it
     * leaves the field, for {@code maxTurns} at most.
     */
    public static BulletPath fired(Object id, long time, double x, double y, double heading, double power,
                                   Field field, int maxTurns) {
        double dx = Math.cos(heading);
        double dy = Math.sin(heading);
        double speed = BulletMotion.speed(power);
        double toWall = Double.POSITIVE_INFINITY;
        if (dx > 1e-9) {
            toWall = Math.min(toWall, (field.width() - x) / dx);
        } else if (dx < -1e-9) {
            toWall = Math.min(toWall, -x / dx);
        }
        if (dy > 1e-9) {
            toWall = Math.min(toWall, (field.height() - y) / dy);
        } else if (dy < -1e-9) {
            toWall = Math.min(toWall, -y / dy);
        }
        long end = time + Math.min(maxTurns, (long) Math.ceil(toWall / speed));
        return new BulletPath(id, time, x, y, dx, dy, speed, end);
    }

    public double x(long t) {
        return x + (t - fireTime) * speed * dx;
    }

    public double y(long t) {
        return y + (t - fireTime) * speed * dy;
    }

    /**
     * The bearings from the wave's origin, as {from, to, chance} along the bullet's move, of the wave's bullets that
     * this bullet would hit (engine-model G-04). In every turn both are in the air: the part of the bullet's move in
     * that turn that lies between the wave's radii at the start and the end of the turn crosses those bullets' moves
     * of the same turn, a collision whatever order the engine moves them in (chance 1). The crossings of one bullet's
     * move with the other's move of the turn before happen only if the engine moves that bullet first, which is
     * random (chance ½).
     */
    public List<double[]> shadows(Wave wave) {
        List<double[]> found = new ArrayList<>();
        for (long t = Math.max(fireTime, wave.fireTime()) + 1; t <= endTime; t++) {
            crossings(found, wave, t, wave.radius(t - 1), wave.radius(t), 1);
            if (t - 1 > wave.fireTime()) {
                // We move first and hit the enemy's bullet through its move of the turn before.
                crossings(found, wave, t, wave.radius(t - 2), wave.radius(t - 1), MIXED_ORDER);
            }
            if (t - 1 > fireTime) {
                // The enemy's bullet moves first and hits ours through our move of the turn before.
                crossings(found, wave, t - 1, wave.radius(t - 1), wave.radius(t), MIXED_ORDER);
            }
        }
        return found;
    }

    /** Adds the bearings of the part of our move in turn {@code t} between radii {@code r1} and {@code r2}. */
    private void crossings(List<double[]> found, Wave wave, long t, double r1, double r2, double chance) {
        double ax = x(t - 1);
        double ay = y(t - 1);
        double fx = ax - wave.x();
        double fy = ay - wave.y();
        double ddx = x(t) - ax;
        double ddy = y(t) - ay;
        double c2 = ddx * ddx + ddy * ddy;
        double c1 = 2 * (fx * ddx + fy * ddy);
        double c0 = fx * fx + fy * fy;
        double[] outer = within(c2, c1, c0, r2);
        if (outer == null) {
            return;
        }
        double lo = Math.max(0, outer[0]);
        double hi = Math.min(1, outer[1]);
        if (hi < lo) {
            return;
        }
        double[] inner = within(c2, c1, c0, r1);
        if (inner == null || inner[1] <= lo || inner[0] >= hi) {
            found.add(bearings(wave, ax, ay, ddx, ddy, lo, hi, chance));
        } else {
            if (inner[0] > lo) {
                found.add(bearings(wave, ax, ay, ddx, ddy, lo, inner[0], chance));
            }
            if (inner[1] < hi) {
                found.add(bearings(wave, ax, ay, ddx, ddy, inner[1], hi, chance));
            }
        }
    }

    /** The interval of λ where |f + λ·d| ≤ r for the quadratic c2·λ² + c1·λ + c0 = |f + λ·d|², or null. */
    private static double[] within(double c2, double c1, double c0, double r) {
        double disc = c1 * c1 - 4 * c2 * (c0 - r * r);
        if (disc < 0 || c2 == 0) {
            return null;
        }
        double root = Math.sqrt(disc);
        return new double[] {(-c1 - root) / (2 * c2), (-c1 + root) / (2 * c2)};
    }

    /** {bearing from the wave's origin of a + λ·d at λ = {@code from}, the same at {@code to}, {@code chance}}. */
    private static double[] bearings(Wave wave, double ax, double ay, double dx, double dy, double from, double to,
                                     double chance) {
        return new double[] {Angles.bearing(wave.x(), wave.y(), ax + from * dx, ay + from * dy),
                Angles.bearing(wave.x(), wave.y(), ax + to * dx, ay + to * dy), chance};
    }
}
