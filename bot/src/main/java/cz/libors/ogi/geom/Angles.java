package cz.libors.ogi.geom;

/**
 * Angle conventions.
 *
 * <p>All internal geometry uses the mathematical convention: radians, 0 = east (+x), counter-clockwise positive.
 * Robocode uses 0 = north (+y) and clockwise positive. Conversion happens only at the Robocode API boundary.
 */
public final class Angles {

    public static final double TWO_PI = 2 * Math.PI;

    private Angles() {
    }

    /** Converts a Robocode heading or absolute bearing to a mathematical angle in [0, 2π). */
    public static double fromRobocode(double robocodeAngle) {
        return normalizeAbsolute(Math.PI / 2 - robocodeAngle);
    }

    /** Converts a mathematical angle to a Robocode heading in [0, 2π). */
    public static double toRobocode(double mathAngle) {
        return normalizeAbsolute(Math.PI / 2 - mathAngle);
    }

    /** Normalizes an angle to [0, 2π). */
    public static double normalizeAbsolute(double angle) {
        double a = angle % TWO_PI;
        return a < 0 ? a + TWO_PI : a;
    }

    /** Normalizes an angle to [-π, π). */
    public static double normalizeRelative(double angle) {
        return normalizeAbsolute(angle + Math.PI) - Math.PI;
    }

    /** Mathematical bearing from (x1, y1) to (x2, y2). */
    public static double bearing(double x1, double y1, double x2, double y2) {
        return Math.atan2(y2 - y1, x2 - x1);
    }

    /**
     * Robocode turn (clockwise positive, as expected by {@code setTurnRightRadians} and friends) that rotates
     * from mathematical angle {@code from} to {@code to} the short way.
     */
    public static double robocodeTurn(double from, double to) {
        return -normalizeRelative(to - from);
    }
}
