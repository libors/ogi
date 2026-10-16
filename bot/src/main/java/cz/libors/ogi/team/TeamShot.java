package cz.libors.ogi.team;

import java.io.Serializable;

/**
 * A bullet a teammate fired: in turn {@code fireTime} from ({@code x}, {@code y}), its position at the end of that
 * turn, along {@code heading}, as {@code Robot.setFireBullet} reports it. Teammates receive it one tick later, while
 * the bullet makes its first move, and keep out of its way.
 *
 * @param heading mathematical heading in radians
 * @param round   round number; shots from another round are ignored
 */
public record TeamShot(
        String shooter,
        double x,
        double y,
        double heading,
        double power,
        long fireTime,
        int round) implements Serializable {

    private static final long serialVersionUID = 1L;
}
