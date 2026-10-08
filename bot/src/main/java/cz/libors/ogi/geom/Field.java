package cz.libors.ogi.geom;

/** Battlefield bounds as seen by a robot's center (the robot is 36×36, so the center stays 18 px from walls). */
public record Field(double width, double height) {

    public static final double ROBOT_HALF_SIZE = 18;

    public double clampX(double x) {
        return Math.max(ROBOT_HALF_SIZE, Math.min(width - ROBOT_HALF_SIZE, x));
    }

    public double clampY(double y) {
        return Math.max(ROBOT_HALF_SIZE, Math.min(height - ROBOT_HALF_SIZE, y));
    }

    /** Whether the point keeps at least {@code margin} px from every wall. */
    public boolean contains(double x, double y, double margin) {
        return x >= margin && y >= margin && x <= width - margin && y <= height - margin;
    }
}
