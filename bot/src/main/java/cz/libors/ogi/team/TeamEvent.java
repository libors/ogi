package cz.libors.ogi.team;

import java.io.Serializable;

/**
 * Something that changed an enemy's energy in turn {@code time} and that a teammate saw: a teammate's bullet hit it,
 * its bullet hit a teammate, or it and a teammate collided. Every robot of the team needs all of them to tell the
 * enemy's shots from its energy (engine-model G-14).
 *
 * @param round round number; events from another round are ignored
 */
public record TeamEvent(String enemy, Kind kind, double power, long time, int round) implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** A bullet of ours of {@code power} hit the enemy: it lost the bullet's damage. */
        HIT_BY_US,
        /** The enemy's bullet of {@code power} hit one of us: it regained 3 × power. */
        HIT_US,
        /** The enemy and one of us collided: both lost the ram damage. */
        RAM
    }
}
