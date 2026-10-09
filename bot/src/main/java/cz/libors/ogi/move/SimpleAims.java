package cz.libors.ogi.move;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.RobotHistory;

/**
 * Where simple guns aim at us: head-on, linear (iterated with the walls, and in closed form without them) and
 * circular targeting. Such a gun aims in the turn before it fires, from its position and its scan of us then.
 * Measured on rumble bots (2026-10-09, full recordings): head-on bots match exactly, linear bots one of the two linear
 * aims within a few milliradians.
 */
public final class SimpleAims {

    public static final int HEAD_ON = 0;
    public static final int LINEAR = 1;
    public static final int LINEAR_NO_WALLS = 2;
    public static final int CIRCULAR = 3;
    public static final int COUNT = 4;

    private static final int MAX_TURNS = 200;

    private SimpleAims() {
    }

    /**
     * The mathematical headings each gun fires along when it aims from ({@code ex}, {@code ey}) at us as {@code us}
     * ends: our position, heading and velocity then, and our turn rate since the state before.
     */
    public static double[] aims(double ex, double ey, RobotHistory us, double bulletSpeed, Field field) {
        double[] aims = new double[COUNT];
        double bearing = Angles.bearing(ex, ey, us.x(), us.y());
        aims[HEAD_ON] = bearing;
        aims[LINEAR] = iterated(ex, ey, us, 0, bulletSpeed, field);
        double lateral = us.velocity() * Math.sin(us.heading() - bearing);
        aims[LINEAR_NO_WALLS] = bearing + Math.asin(Math.max(-1, Math.min(1, lateral / bulletSpeed)));
        aims[CIRCULAR] = iterated(ex, ey, us, us.turnRate(), bulletSpeed, field);
        return aims;
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
