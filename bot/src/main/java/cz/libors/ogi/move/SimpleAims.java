package cz.libors.ogi.move;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.RobotHistory;

/**
 * Where simple guns aim at us: head-on, linear (iterated with the walls, and in closed form without them) and
 * circular targeting; linear with our lateral velocity averaged over the last 20 and 40 turns; and a pattern match on
 * our lateral velocity ({@link LateralHistory}). Such a gun aims in the turn before it fires, from its position and
 * its scan of us then. Measured on foreign robots (2026-10-09, full recordings): head-on bots match exactly, linear bots
 * one of the two linear aims within a few milliradians.
 *
 * <p>The windows and the match length are ours (2026-10-11, full recordings against foreign robots): of the windows
 * 5 … 60, 20 and 40 explained most of the bullets of the robots that average our lateral velocity; matches of at
 * most 20, 40 and 60 symbols explained about as many bullets of the robots that match its pattern, more than 10.
 */
public final class SimpleAims {

    public static final int HEAD_ON = 0;
    public static final int LINEAR = 1;
    public static final int LINEAR_NO_WALLS = 2;
    public static final int CIRCULAR = 3;
    public static final int AVERAGED_SHORT = 4;
    public static final int AVERAGED_LONG = 5;
    public static final int PATTERN = 6;
    public static final int COUNT = 7;

    private static final int MAX_TURNS = 200;
    private static final int SHORT_WINDOW = 20;
    private static final int LONG_WINDOW = 40;
    private static final int MAX_MATCH = 40;

    private SimpleAims() {
    }

    /**
     * The mathematical headings each gun fires along when it aims from ({@code ex}, {@code ey}) at us as {@code us}
     * ends: our position, heading and velocity then, and our turn rate since the state before; {@code lateral} is our
     * lateral velocity as this enemy saw it up to then (null: the averaged and pattern guns aim head-on).
     */
    public static double[] aims(double ex, double ey, RobotHistory us, double bulletSpeed, Field field,
                                LateralHistory lateral) {
        double[] aims = new double[COUNT];
        double bearing = Angles.bearing(ex, ey, us.x(), us.y());
        aims[HEAD_ON] = bearing;
        aims[LINEAR] = iterated(ex, ey, us, 0, bulletSpeed, field);
        aims[LINEAR_NO_WALLS] = lead(bearing, us.velocity() * Math.sin(us.heading() - bearing), bulletSpeed);
        aims[CIRCULAR] = iterated(ex, ey, us, us.turnRate(), bulletSpeed, field);
        aims[AVERAGED_SHORT] = bearing;
        aims[AVERAGED_LONG] = bearing;
        aims[PATTERN] = bearing;
        if (lateral != null) {
            aims[AVERAGED_SHORT] = lead(bearing, lateral.average(us.time(), SHORT_WINDOW), bulletSpeed);
            aims[AVERAGED_LONG] = lead(bearing, lateral.average(us.time(), LONG_WINDOW), bulletSpeed);
            double distance = Math.hypot(us.x() - ex, us.y() - ey);
            int turns = (int) Math.max(1, distance / bulletSpeed);
            double replay = lateral.replay(us.time(), MAX_MATCH, turns);
            if (!Double.isNaN(replay)) {
                aims[PATTERN] = bearing + replay / Math.max(distance, 1);
            }
        }
        return aims;
    }

    private static double lead(double bearing, double lateral, double bulletSpeed) {
        return bearing + Math.asin(Math.max(-1, Math.min(1, lateral / bulletSpeed)));
    }

    /** Our velocity and turn rate kept until the bullet covers the distance, our centre kept off the walls. */
    private static double iterated(double ex, double ey, RobotHistory us, double turnRate, double bulletSpeed,
                                   Field field) {
        double x = us.x();
        double y = us.y();
        double heading = us.heading();
        for (int turn = 1; turn <= MAX_TURNS; turn++) {
            heading += turnRate;
            x = field.clampX(x + us.velocity() * Math.cos(heading));
            y = field.clampY(y + us.velocity() * Math.sin(heading));
            if (Math.hypot(x - ex, y - ey) <= turn * bulletSpeed) {
                break;
            }
        }
        return Angles.bearing(ex, ey, x, y);
    }
}
