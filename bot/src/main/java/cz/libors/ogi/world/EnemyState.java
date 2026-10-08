package cz.libors.ogi.world;

/**
 * Last known state of an enemy robot.
 *
 * @param heading  mathematical heading in radians
 * @param turnRate mathematical heading change per tick, estimated from the two latest observations
 * @param time     tick of the observation
 */
public record EnemyState(
        String name,
        double x,
        double y,
        double heading,
        double velocity,
        double turnRate,
        double energy,
        long time) {

    public double distanceTo(double px, double py) {
        return Math.hypot(x - px, y - py);
    }
}
