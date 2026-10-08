package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.world.EnemyState;

/**
 * Circular targeting: assumes the target keeps its velocity and turn rate.
 *
 * <p>Approximate by design (walking skeleton). It does not model acceleration limits and is not meant to match the
 * engine exactly.
 */
public final class CircularAim {

    private static final int MAX_FLIGHT_TICKS = 150;

    private CircularAim() {
    }

    /**
     * Returns the mathematical firing angle.
     *
     * @param fireTime the tick when the bullet leaves the gun
     */
    public static double aim(double shooterX, double shooterY, EnemyState target, double bulletSpeed,
                             long fireTime, Field field) {
        double x = target.x();
        double y = target.y();
        double heading = target.heading();
        double velocity = target.velocity();
        long ticksToFire = Math.max(0, fireTime - target.time());
        for (int tick = 1; tick <= ticksToFire + MAX_FLIGHT_TICKS; tick++) {
            heading += target.turnRate();
            x = field.clampX(x + velocity * Math.cos(heading));
            y = field.clampY(y + velocity * Math.sin(heading));
            long flightTicks = tick - ticksToFire;
            if (flightTicks > 0 && Math.hypot(x - shooterX, y - shooterY) <= flightTicks * bulletSpeed) {
                break;
            }
        }
        return Angles.bearing(shooterX, shooterY, x, y);
    }
}
