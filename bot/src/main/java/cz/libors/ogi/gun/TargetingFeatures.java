package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.RobotMotion;

/**
 * The situation of a shot as numbers in about [0, 1], for finding similar past shots, and the frame its guess
 * factors are measured in.
 *
 * <p>The frame: the reference is the bearing from the bullet's origin to the target, and the direction is the sign of
 * the target's lateral velocity (counter-clockwise positive), so that guess factor 1 means "as far as it can get in
 * the way it was moving".
 */
public final class TargetingFeatures {

    /** The number of features; their weights are in {@link GunParameters}. */
    public static final int COUNT = 10;

    private static final double WALL_LIMIT = 1.5;
    private static final int WALL_STEPS = 60;
    private static final int DISPLACEMENT_TURNS = 10;

    private TargetingFeatures() {
    }

    /**
     * Features of a shot from ({@code ox}, {@code oy}) at the target's latest scan. Writes them into {@code out}.
     *
     * @param lastDirection the direction to use while the target has no lateral velocity
     * @return the direction, +1 or −1
     */
    public static int compute(double ox, double oy, EnemyHistory target, double bulletSpeed, Field field,
                              int lastDirection, double[] out) {
        double distance = Math.hypot(target.x() - ox, target.y() - oy);
        double bearing = Angles.bearing(ox, oy, target.x(), target.y());
        double relative = target.heading() - bearing;
        double lateral = target.velocity() * Math.sin(relative);
        double advancing = -target.velocity() * Math.cos(relative);
        int direction = lateral > 0 ? 1 : lateral < 0 ? -1 : lastDirection;
        double flightTurns = distance / bulletSpeed;
        double escape = Math.asin(RobotMotion.MAX_VELOCITY / bulletSpeed);

        out[0] = flightTurns / 100;
        out[1] = Math.abs(lateral) / RobotMotion.MAX_VELOCITY;
        out[2] = (advancing / RobotMotion.MAX_VELOCITY + 1) / 2;
        out[3] = clamp((target.acceleration() + RobotMotion.DECELERATION)
                / (RobotMotion.DECELERATION + RobotMotion.ACCELERATION));
        out[4] = clamp(target.turnsSinceReversal() / flightTurns);
        out[5] = clamp(target.turnsSinceDeceleration() / flightTurns);
        out[6] = wallRoom(ox, oy, distance, bearing, direction, escape, field) / WALL_LIMIT;
        out[7] = wallRoom(ox, oy, distance, bearing, -direction, escape, field) / WALL_LIMIT;
        out[8] = clamp(target.displacement(DISPLACEMENT_TURNS) / (DISPLACEMENT_TURNS * RobotMotion.MAX_VELOCITY));
        // Whether the path curves towards higher or lower guess factors; a reversing robot curves the other way.
        double curve = target.turnRate() * direction * Math.signum(target.velocity());
        out[9] = clamp((curve / RobotMotion.MAX_TURN_RATE + 1) / 2);
        return direction;
    }

    /**
     * How far, in maximum escape angles, the target can orbit the origin in {@code direction} at its distance before
     * it reaches a wall; at most {@value #WALL_LIMIT}.
     */
    static double wallRoom(double ox, double oy, double distance, double bearing, int direction, double escape,
                           Field field) {
        double step = WALL_LIMIT * escape / WALL_STEPS;
        for (int i = 1; i <= WALL_STEPS; i++) {
            double a = bearing + direction * i * step;
            if (!field.contains(ox + distance * Math.cos(a), oy + distance * Math.sin(a), Field.ROBOT_HALF_SIZE)) {
                return (i - 1) * step / escape;
            }
        }
        return WALL_LIMIT;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
