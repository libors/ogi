package cz.libors.ogi.move;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.world.World;

import java.util.List;
import java.util.Random;

/**
 * Orbits the target at a preferred distance, switches direction at random intervals, smooths along walls and keeps
 * away from teammates. Walking-skeleton movement, not a strategy.
 */
public final class OrbitMovement {

    private static final double PREFERRED_DISTANCE = 325;
    private static final double MAX_DISTANCE_TILT = 0.5;
    private static final double WALL_MARGIN = 30;
    private static final double WALL_STICK = 120;
    private static final double WALL_SMOOTH_STEP = 0.1;
    private static final double TEAMMATE_CLEARANCE = 120;

    private final Field field;
    private final Random random;
    private int direction = 1;
    private long nextSwitch;

    public OrbitMovement(Field field, Random random) {
        this.field = field;
        this.random = random;
    }

    /** Returns the desired mathematical heading of travel. */
    public double desiredHeading(double x, double y, double targetX, double targetY, long now,
                                 List<World.Teammate> teammates) {
        if (now >= nextSwitch) {
            direction = random.nextBoolean() ? 1 : -1;
            nextSwitch = now + 15 + random.nextInt(40);
        }
        double heading = orbitHeading(x, y, targetX, targetY);
        World.Teammate crowding = closestWithin(x, y, teammates, TEAMMATE_CLEARANCE);
        if (crowding != null) {
            heading = Angles.bearing(crowding.x(), crowding.y(), x, y);
        }
        return smoothAlongWalls(x, y, heading);
    }

    private double orbitHeading(double x, double y, double targetX, double targetY) {
        double bearing = Angles.bearing(x, y, targetX, targetY);
        double distance = Math.hypot(targetX - x, targetY - y);
        double tilt = Math.max(-MAX_DISTANCE_TILT,
                Math.min(MAX_DISTANCE_TILT, (PREFERRED_DISTANCE - distance) / PREFERRED_DISTANCE));
        return bearing + direction * (Math.PI / 2 + tilt);
    }

    private double smoothAlongWalls(double x, double y, double heading) {
        for (int i = 0; i < Angles.TWO_PI / WALL_SMOOTH_STEP; i++) {
            double h = heading - direction * i * WALL_SMOOTH_STEP;
            if (field.contains(x + WALL_STICK * Math.cos(h), y + WALL_STICK * Math.sin(h), WALL_MARGIN)) {
                return h;
            }
        }
        direction = -direction;
        return heading + Math.PI;
    }

    private static World.Teammate closestWithin(double x, double y, List<World.Teammate> teammates, double radius) {
        World.Teammate best = null;
        double bestDistance = radius;
        for (World.Teammate t : teammates) {
            double d = Math.hypot(t.x() - x, t.y() - y);
            if (d < bestDistance) {
                best = t;
                bestDistance = d;
            }
        }
        return best;
    }
}
