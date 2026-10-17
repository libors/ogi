package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.RobotMotion;

/**
 * Where a target can get before a wave reaches it ("precise escape angles"): from its last scan it orbits the wave's
 * origin at full speed each way, as the engine moves it, turning towards the origin to slide along a wall, until the
 * wave's radius reaches its distance. The range is the lowest and the highest bearing it reaches on the way, widened
 * by half a robot's width, in guess factors.
 *
 * <p>Almost no movement leaves it (bot.md "Gun", Random gun). So a gun that aims uniformly inside it hits any movement
 * about equally often, about as often as a robot's width over the range's, and a surfer cannot learn where it aims.
 */
final class EscapeRange {

    /** Wall smoothing: the stick that must end inside the field, and the step the heading turns by. */
    private static final double WALL_STICK = 120;
    private static final double SMOOTH_STEP = 0.05;
    private static final double DRIVE_DISTANCE = 100;
    /** A wave slower than any bullet does not exist; this only stops a run that never meets its wave. */
    private static final int MAX_TURNS = 200;
    /** Half a robot's mean width over the bearings it is seen from: 2 · 36 / π. */
    static final double HALF_WIDTH = 72 / Math.PI;

    private EscapeRange() {
    }

    /**
     * The range as {lowest, highest} guess factor, signed like the gun's ({@code direction}: +1 ahead of the
     * target's lateral motion).
     *
     * @param ox        the wave's origin: our position when the bullet leaves
     * @param fireTime  the turn of the fire command; the wave's radius at the end of turn t is
     *                  {@code (t − fireTime) · speed}
     * @param scanTime  the turn of the target's state ({@code x}, {@code y}, {@code heading}, {@code velocity})
     * @param heading   the target's heading, mathematical
     * @param reference the bearing the offsets are measured from
     * @param escape    the angle of guess factor 1
     */
    static double[] guessFactors(double ox, double oy, long fireTime, long scanTime, double x, double y,
                                 double heading, double velocity, double speed, double reference, int direction,
                                 double escape, Field field) {
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        for (int orbit = -1; orbit <= 1; orbit += 2) {
            EngineRobot r = new EngineRobot();
            r.x = x;
            r.y = y;
            r.bodyHeading = Angles.toRobocode(heading);
            r.velocity = velocity;
            for (int k = 0; k < MAX_TURNS; k++) {
                double offset = direction * Angles.normalizeRelative(Angles.bearing(ox, oy, r.x, r.y) - reference);
                low = Math.min(low, offset);
                high = Math.max(high, offset);
                if ((scanTime + k - fireTime) * speed >= Math.hypot(r.x - ox, r.y - oy)) {
                    break;
                }
                step(r, ox, oy, orbit, field);
            }
        }
        double half = Math.atan(HALF_WIDTH / Math.max(Math.hypot(x - ox, y - oy), HALF_WIDTH));
        return new double[] {(low - half) / escape, (high + half) / escape};
    }

    /** One turn of full speed around ({@code ox}, {@code oy}), counter-clockwise for orbit +1, along the walls. */
    private static void step(EngineRobot r, double ox, double oy, int orbit, Field field) {
        double want = Angles.bearing(ox, oy, r.x, r.y) + orbit * Math.PI / 2;
        for (int i = 0; i < Angles.TWO_PI / SMOOTH_STEP; i++) {
            if (field.contains(r.x + WALL_STICK * Math.cos(want), r.y + WALL_STICK * Math.sin(want),
                    Field.ROBOT_HALF_SIZE)) {
                break;
            }
            // Towards the origin, which keeps the bearing moving.
            want += orbit * SMOOTH_STEP;
        }
        double turn = Angles.normalizeRelative(Angles.toRobocode(want) - r.bodyHeading);
        double distance = DRIVE_DISTANCE;
        if (Math.abs(turn) > Math.PI / 2) {
            turn = Angles.normalizeRelative(turn + Math.PI);
            distance = -distance;
        }
        r.bodyTurnRemaining = turn;
        r.distanceRemaining = distance;
        RobotMotion.move(r, (int) field.width(), (int) field.height(), 0);
    }
}
