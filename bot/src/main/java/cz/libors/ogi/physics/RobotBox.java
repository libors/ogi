package cz.libors.ogi.physics;

/**
 * A robot's 36×36 bounding box as the engine stores it: corner and size rounded to {@code float}
 * ({@code net.sf.robocode.battle.BoundingRectangle} extends {@code Rectangle2D.Float}; engine-model G-37). The
 * tests compute in {@code double} from the rounded values, as {@code Rectangle2D} does, so that edge cases match the
 * engine bit for bit.
 */
public final class RobotBox {

    private static final int OUT_LEFT = 1;
    private static final int OUT_TOP = 2;
    private static final int OUT_RIGHT = 4;
    private static final int OUT_BOTTOM = 8;

    private final float x;
    private final float y;
    private final float width;
    private final float height;

    /** The box around a robot's center, as {@code RobotPeer.updateBoundingBox} sets it. */
    public RobotBox(double centerX, double centerY) {
        int size = 2 * RobotMotion.HALF_SIZE;
        x = (float) (centerX - RobotMotion.HALF_SIZE);
        y = (float) (centerY - RobotMotion.HALF_SIZE);
        width = size;
        height = size;
    }

    /** Whether the point is inside; the low edges belong to the box, the high edges do not. */
    public boolean contains(double px, double py) {
        double x0 = x;
        double y0 = y;
        return px >= x0 && py >= y0 && px < x0 + (double) width && py < y0 + (double) height;
    }

    /** Whether two boxes overlap (robot collisions). */
    public boolean intersects(RobotBox o) {
        double x0 = x;
        double y0 = y;
        double ox = o.x;
        double oy = o.y;
        return ox + (double) o.width > x0 && oy + (double) o.height > y0
                && ox < x0 + (double) width && oy < y0 + (double) height;
    }

    /**
     * Whether the segment touches the box (bullet hits). Clips the segment's start against the box edges until it
     * lies inside or both ends are beyond the same edge.
     */
    public boolean intersectsLine(double x1, double y1, double x2, double y2) {
        int out2 = outcode(x2, y2);
        if (out2 == 0) {
            return true;
        }
        int out1;
        while ((out1 = outcode(x1, y1)) != 0) {
            if ((out1 & out2) != 0) {
                return false;
            }
            if ((out1 & (OUT_LEFT | OUT_RIGHT)) != 0) {
                double edgeX = x;
                if ((out1 & OUT_RIGHT) != 0) {
                    edgeX += width;
                }
                y1 = y1 + (edgeX - x1) * (y2 - y1) / (x2 - x1);
                x1 = edgeX;
            } else {
                double edgeY = y;
                if ((out1 & OUT_BOTTOM) != 0) {
                    edgeY += height;
                }
                x1 = x1 + (edgeY - y1) * (x2 - x1) / (y2 - y1);
                y1 = edgeY;
            }
        }
        return true;
    }

    /** Which edges the point lies beyond, computed in double from the float box. */
    private int outcode(double px, double py) {
        int out = 0;
        if (px < x) {
            out |= OUT_LEFT;
        } else if (px > x + (double) width) {
            out |= OUT_RIGHT;
        }
        if (py < y) {
            out |= OUT_TOP;
        } else if (py > y + (double) height) {
            out |= OUT_BOTTOM;
        }
        return out;
    }
}
