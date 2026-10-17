package cz.libors.ogi.move;

import cz.libors.ogi.geom.Angles;

/**
 * A turn's movement as the engine takes it: the distance to drive (negative: backwards) and the body's turn to the
 * right, in radians (Robocode's convention, clockwise).
 */
public record Move(double distance, double rightTurn) {

    /**
     * Straight towards the point ({@code px}, {@code py}) from ({@code x}, {@code y}), backwards if that turns us less.
     *
     * @param heading our body heading, mathematical
     */
    public static Move to(double x, double y, double heading, double px, double py) {
        double distance = Math.hypot(px - x, py - y);
        double turn = Angles.normalizeRelative(Angles.bearing(x, y, px, py) - heading);
        if (Math.abs(turn) > Math.PI / 2) {
            turn = Angles.normalizeRelative(turn + Math.PI);
            distance = -distance;
        }
        return new Move(distance, -turn);
    }
}
