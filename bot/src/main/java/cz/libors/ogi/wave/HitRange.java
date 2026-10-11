package cz.libors.ogi.wave;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.RobotBox;

import java.util.Arrays;

/**
 * The headings at which a wave's bullet hits one robot, accumulated turn by turn while the wave passes it ("precise
 * intersection").
 *
 * <p>It repeats the engine's test (engine-model G-02, G-03): in turn t a bullet moves along its heading from radius
 * {@code (t − f − 1)·v} to {@code (t − f)·v}; it is gone if it ends within {@value BulletMotion#RADIUS} px of a wall;
 * otherwise it hits a robot whose box at the end of turn t − 1 its move touches. The headings that hit in one turn are
 * those under which the box's part between the two radii is seen from the origin, minus those that end at a wall.
 *
 * <p>Angles are mathematical. They are kept relative to a reference angle, normally the bearing to the robot when the
 * wave was fired, so that a range never wraps around ±π. It ignores other bullets and other robots that might stop the
 * bullet first.
 */
public final class HitRange {

    private final Wave wave;
    private final double reference;
    private final int fieldWidth;
    private final int fieldHeight;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;
    private boolean passed;

    public HitRange(Wave wave, double reference, int fieldWidth, int fieldHeight) {
        this.wave = wave;
        this.reference = reference;
        this.fieldWidth = fieldWidth;
        this.fieldHeight = fieldHeight;
    }

    /**
     * The robot's position at the end of turn {@code time}; the wave's bullets moving in turn {@code time + 1} are
     * tested against it. Feed every turn from the fire turn on, in order.
     */
    public void observe(long time, double x, double y) {
        if (passed || time < wave.fireTime()) {
            return;
        }
        RobotBox box = new RobotBox(x, y);
        double inner = wave.radius(time);
        double outer = wave.radius(time + 1);
        // Most turns the bullets are still short of the box, and nothing can hit; the full test is far slower.
        if (nearestPoint(wave.x(), wave.y(), box) <= outer + 1e-6) {
            double[] turn = turn(wave.x(), wave.y(), inner, outer, box, reference, fieldWidth, fieldHeight);
            if (turn != null) {
                min = Math.min(min, turn[0]);
                max = Math.max(max, turn[1]);
            }
        }
        passed = inner > farthestCorner(wave.x(), wave.y(), box);
    }

    /** Whether the wave has passed the robot's last observed box, so later turns cannot hit it. */
    public boolean passed() {
        return passed;
    }

    /** Whether some heading hits. */
    public boolean hits() {
        return min <= max;
    }

    /** An independent copy, e.g. to continue it along a predicted path. */
    public HitRange copy() {
        HitRange c = new HitRange(wave, reference, fieldWidth, fieldHeight);
        c.min = min;
        c.max = max;
        c.passed = passed;
        return c;
    }

    public Wave wave() {
        return wave;
    }

    public double reference() {
        return reference;
    }

    /** Lowest hitting heading relative to the reference, or +∞ if none hits. */
    public double minOffset() {
        return min;
    }

    /** Highest hitting heading relative to the reference, or −∞ if none hits. */
    public double maxOffset() {
        return max;
    }

    /**
     * The headings, relative to {@code reference}, at which a bullet moving between the two radii hits the box and
     * does not end at a wall: {lowest, highest}, or null if none. Assumes the origin lies outside the box. The range is
     * continuous around the bearing to the box, so it can extend beyond ±π from the reference.
     */
    public static double[] turn(double ox, double oy, double inner, double outer, RobotBox box, double reference,
                                int fieldWidth, int fieldHeight) {
        double x0 = box.x();
        double y0 = box.y();
        double x1 = x0 + box.width();
        double y1 = y0 + box.height();
        // Offsets are taken from the bearing to the box's center, which the box never spans half a turn away from.
        double local = Angles.bearing(ox, oy, (x0 + x1) / 2, (y0 + y1) / 2);
        double[] range = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        // The extremes of the angle over the box's part of the ring lie at its vertices: the corners inside the
        // ring and the points where the circles cross the edges.
        corner(range, ox, oy, x0, y0, inner, outer, local);
        corner(range, ox, oy, x1, y0, inner, outer, local);
        corner(range, ox, oy, x1, y1, inner, outer, local);
        corner(range, ox, oy, x0, y1, inner, outer, local);
        edges(range, ox, oy, inner, x0, y0, x1, y1, local);
        edges(range, ox, oy, outer, x0, y0, x1, y1, local);
        if (range[0] > range[1] || !clipWalls(range, ox, oy, outer, local, fieldWidth, fieldHeight)) {
            return null;
        }
        double shift = Angles.normalizeRelative(local - reference);
        range[0] += shift;
        range[1] += shift;
        return range;
    }

    /**
     * Removes the headings whose move ends within the wall radius ({@code BulletPeer.checkWallCollision}): the
     * engine tests walls before robots. The circle of the end points crosses the wall lines at a few headings; the
     * pieces between them are either all in or all out. Narrows {@code range} in place; false if nothing is left.
     */
    private static boolean clipWalls(double[] range, double ox, double oy, double outer, double reference,
                                     int fieldWidth, int fieldHeight) {
        double low = BulletMotion.RADIUS;
        double[] cuts = new double[8];
        int n = 0;
        n = cutVertical(cuts, n, (low - ox) / outer, range, reference);
        n = cutVertical(cuts, n, (fieldWidth - low - ox) / outer, range, reference);
        n = cutHorizontal(cuts, n, (low - oy) / outer, range, reference);
        n = cutHorizontal(cuts, n, (fieldHeight - low - oy) / outer, range, reference);
        if (n == 0) {
            return endsInside(ox, oy, outer, reference + (range[0] + range[1]) / 2, fieldWidth, fieldHeight);
        }
        Arrays.sort(cuts, 0, n);
        double lowest = Double.POSITIVE_INFINITY;
        double highest = Double.NEGATIVE_INFINITY;
        double from = range[0];
        for (int i = 0; i <= n; i++) {
            double to = i < n ? cuts[i] : range[1];
            if (to > from && endsInside(ox, oy, outer, reference + (from + to) / 2, fieldWidth, fieldHeight)) {
                lowest = Math.min(lowest, from);
                highest = Math.max(highest, to);
            }
            from = to;
        }
        range[0] = lowest;
        range[1] = highest;
        return lowest <= highest;
    }

    /** Headings at which the end-point circle crosses a vertical wall line; {@code c} is the line's cosine. */
    private static int cutVertical(double[] cuts, int n, double c, double[] range, double reference) {
        if (Math.abs(c) > 1) {
            return n;
        }
        double a = Math.acos(c);
        return addCut(cuts, addCut(cuts, n, a, range, reference), -a, range, reference);
    }

    /** Headings at which the end-point circle crosses a horizontal wall line; {@code s} is the line's sine. */
    private static int cutHorizontal(double[] cuts, int n, double s, double[] range, double reference) {
        if (Math.abs(s) > 1) {
            return n;
        }
        double a = Math.asin(s);
        return addCut(cuts, addCut(cuts, n, a, range, reference), Math.PI - a, range, reference);
    }

    private static int addCut(double[] cuts, int n, double angle, double[] range, double reference) {
        double offset = Angles.normalizeRelative(angle - reference);
        if (offset > range[0] && offset < range[1]) {
            cuts[n++] = offset;
        }
        return n;
    }

    private static boolean endsInside(double ox, double oy, double radius, double heading, int fieldWidth,
                                      int fieldHeight) {
        double x = ox + radius * Math.cos(heading);
        double y = oy + radius * Math.sin(heading);
        double r = BulletMotion.RADIUS;
        return x - r > 0 && y - r > 0 && x + r < fieldWidth && y + r < fieldHeight;
    }

    private static void corner(double[] range, double ox, double oy, double cx, double cy, double inner,
                               double outer, double reference) {
        double d = Math.hypot(cx - ox, cy - oy);
        if (d >= inner && d <= outer) {
            include(range, ox, oy, cx, cy, reference);
        }
    }

    /** Where the circle of radius {@code r} crosses the four edges of the box. */
    private static void edges(double[] range, double ox, double oy, double r, double x0, double y0, double x1,
                              double y1, double reference) {
        crossVertical(range, ox, oy, r, x0, y0, y1, reference);
        crossVertical(range, ox, oy, r, x1, y0, y1, reference);
        crossHorizontal(range, ox, oy, r, y0, x0, x1, reference);
        crossHorizontal(range, ox, oy, r, y1, x0, x1, reference);
    }

    /** Where the circle crosses the vertical edge x = {@code ex} between {@code y0} and {@code y1}. */
    private static void crossVertical(double[] range, double ox, double oy, double r, double ex, double y0, double y1,
                                      double reference) {
        double dx = ex - ox;
        double h = r * r - dx * dx;
        if (h < 0) {
            return;
        }
        double root = Math.sqrt(h);
        if (oy - root >= y0 && oy - root <= y1) {
            include(range, ox, oy, ex, oy - root, reference);
        }
        if (oy + root >= y0 && oy + root <= y1) {
            include(range, ox, oy, ex, oy + root, reference);
        }
    }

    /** Where the circle crosses the horizontal edge y = {@code ey} between {@code x0} and {@code x1}. */
    private static void crossHorizontal(double[] range, double ox, double oy, double r, double ey, double x0,
                                        double x1, double reference) {
        double dy = ey - oy;
        double h = r * r - dy * dy;
        if (h < 0) {
            return;
        }
        double root = Math.sqrt(h);
        if (ox - root >= x0 && ox - root <= x1) {
            include(range, ox, oy, ox - root, ey, reference);
        }
        if (ox + root >= x0 && ox + root <= x1) {
            include(range, ox, oy, ox + root, ey, reference);
        }
    }

    private static void include(double[] range, double ox, double oy, double px, double py, double reference) {
        double offset = Angles.normalizeRelative(Angles.bearing(ox, oy, px, py) - reference);
        range[0] = Math.min(range[0], offset);
        range[1] = Math.max(range[1], offset);
    }

    /** The distance from the origin to the box's nearest point. */
    private static double nearestPoint(double ox, double oy, RobotBox box) {
        double dx = Math.max(0, Math.max(box.x() - ox, ox - (box.x() + box.width())));
        double dy = Math.max(0, Math.max(box.y() - oy, oy - (box.y() + box.height())));
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double farthestCorner(double ox, double oy, RobotBox box) {
        double fx = Math.max(Math.abs(box.x() - ox), Math.abs(box.x() + box.width() - ox));
        double fy = Math.max(Math.abs(box.y() - oy), Math.abs(box.y() + box.height() - oy));
        return Math.hypot(fx, fy);
    }
}
