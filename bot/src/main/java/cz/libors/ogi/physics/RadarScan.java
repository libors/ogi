package cz.libors.ogi.physics;

import java.awt.geom.Arc2D;

/**
 * The engine's radar scan ({@code RobotPeer.scan}), built with the same {@link Arc2D} as the engine so that the
 * results match it bit for bit.
 *
 * <p>A robot scans in a turn only if it moved or turned its body, gun or radar in it (or called {@code scan()}). The
 * scan covers the pie between the radar heading before and after the turn, up to {@link #RADIUS}, and sees every
 * live robot whose box it touches, at the positions after all robots moved (engine-model G-13).
 */
public final class RadarScan {

    public static final double RADIUS = 1200;

    private RadarScan() {
    }

    /** Whether the robot scans this turn, from its state before and after the turn. */
    public static boolean scans(EngineRobot before, EngineRobot after) {
        return before.bodyHeading != after.bodyHeading || before.gunHeading != after.gunHeading
                || before.radarHeading != after.radarHeading || before.x != after.x || before.y != after.y;
    }

    /** The swept pie, in Java2D's arc convention exactly as the engine builds it. */
    public static Arc2D sweep(double x, double y, double lastRadarHeading, double radarHeading) {
        double startAngle = lastRadarHeading;
        double scanRadians = radarHeading - startAngle;
        if (scanRadians < -Math.PI) {
            scanRadians = 2 * Math.PI + scanRadians;
        } else if (scanRadians > Math.PI) {
            scanRadians = scanRadians - 2 * Math.PI;
        }
        // The engine turns its clockwise, y-up angle into Java2D's counter-clockwise, y-down one.
        startAngle -= Math.PI / 2;
        startAngle = EngineAngles.normalAbsolute(startAngle);
        return new Arc2D.Double(x - RADIUS, y - RADIUS, 2 * RADIUS, 2 * RADIUS, 180.0 * startAngle / Math.PI,
                180.0 * scanRadians / Math.PI, Arc2D.PIE);
    }

    /** {@code RobotPeer.intersects}: the pie's start edge or the pie itself touches the box. */
    public static boolean sees(Arc2D sweep, RobotBox target) {
        return target.intersectsLine(sweep.getCenterX(), sweep.getCenterY(), sweep.getStartPoint().getX(),
                sweep.getStartPoint().getY())
                || sweep.intersects(target.x(), target.y(), target.width(), target.height());
    }

    /** The bearing a scan reports: from the scanner's body heading to the target, in [−π, π). */
    public static double bearing(double x, double y, double bodyHeading, double targetX, double targetY) {
        return EngineAngles.normalRelative(Math.atan2(targetX - x, targetY - y) - bodyHeading);
    }

    /** The distance a scan reports, between the robots' centers. */
    public static double distance(double x, double y, double targetX, double targetY) {
        return Math.hypot(targetX - x, targetY - y);
    }
}
