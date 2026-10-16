package cz.libors.ogi.team;

import java.io.Serializable;

/**
 * A robot's own state as it tells its teammates. Teammates receive it one tick later, when the sender has already
 * moved once more, so it also carries where the sender's commands take it by the end of that next turn: exact unless
 * the sender collides with a robot.
 *
 * @param heading mathematical heading in radians, at the end of turn {@code time}
 * @param nextX   the sender's predicted position at the end of turn {@code time + 1}
 * @param round   round number; states from another round are ignored
 */
public record TeammateState(
        String name,
        double x,
        double y,
        double heading,
        double velocity,
        double energy,
        double nextX,
        double nextY,
        long time,
        int round) implements Serializable {

    private static final long serialVersionUID = 1L;
}
