package cz.libors.ogi.wave;

import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.RobotMotion;

/**
 * Every bullet a robot could have fired in one turn: a circle that grows by the bullet speed each turn.
 *
 * <p>The shooter issued the fire command in turn {@code fireTime}. The bullet leaves from ({@code x}, {@code y}), the
 * shooter's position at the end of that turn, moves for the first time in the next turn and has travelled
 * {@code (t − fireTime) × speed} by the end of turn t (engine-model G-01, G-03).
 */
public record Wave(String shooter, double x, double y, long fireTime, double power) {

    public double speed() {
        return BulletMotion.speed(power);
    }

    /** Distance the bullets have travelled by the end of turn {@code time}. */
    public double radius(long time) {
        return (time - fireTime) * speed();
    }

    /** Maximum escape angle: the widest angle a robot at full speed can cover while the bullet flies. */
    public double maxEscapeAngle() {
        return Math.asin(RobotMotion.MAX_VELOCITY / speed());
    }
}
