package cz.libors.ogi.physics;

/**
 * The engine's angle normalizations ({@code robocode.util.Utils}), in its convention (radians, 0 = north,
 * clockwise), for code that has to match the engine bit for bit.
 */
public final class EngineAngles {

    private static final double TWO_PI = 2 * Math.PI;
    private static final double PI_OVER_TWO = Math.PI / 2;
    private static final double THREE_PI_OVER_TWO = 3 * Math.PI / 2;
    private static final double NEAR_DELTA = .00001;

    private EngineAngles() {
    }

    /** {@code Utils.normalAbsoluteAngle}: to [0, 2π). */
    public static double normalAbsolute(double angle) {
        return (angle %= TWO_PI) >= 0 ? angle : (angle + TWO_PI);
    }

    /** {@code Utils.normalRelativeAngle}: to [−π, π). */
    public static double normalRelative(double angle) {
        return (angle %= TWO_PI) >= 0
                ? (angle < Math.PI) ? angle : angle - TWO_PI
                : (angle >= -Math.PI) ? angle : angle + TWO_PI;
    }

    /** {@code Utils.normalNearAbsoluteAngle}: like {@link #normalAbsolute}, snapping to a quarter within 1e-5. */
    public static double normalNearAbsolute(double angle) {
        angle = (angle %= TWO_PI) >= 0 ? angle : (angle + TWO_PI);
        if (isNear(angle, Math.PI)) {
            return Math.PI;
        } else if (angle < Math.PI) {
            if (isNear(angle, 0)) {
                return 0;
            } else if (isNear(angle, PI_OVER_TWO)) {
                return PI_OVER_TWO;
            }
        } else {
            if (isNear(angle, THREE_PI_OVER_TWO)) {
                return THREE_PI_OVER_TWO;
            } else if (isNear(angle, TWO_PI)) {
                return 0;
            }
        }
        return angle;
    }

    /** {@code Utils.isNear}. */
    public static boolean isNear(double a, double b) {
        return Math.abs(a - b) < NEAR_DELTA;
    }
}
