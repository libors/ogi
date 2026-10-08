package cz.libors.ogi.team;

import java.io.Serializable;

/**
 * A scan of an enemy shared between teammates. Teammates receive it one tick later.
 *
 * @param heading mathematical heading in radians
 * @param round   round number; sightings from another round are ignored
 */
public record EnemySighting(
        String name,
        double x,
        double y,
        double heading,
        double velocity,
        double energy,
        long time,
        int round) implements Serializable {

    private static final long serialVersionUID = 1L;
}
