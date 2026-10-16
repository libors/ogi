package cz.libors.ogi.team;

import java.io.Serializable;
import java.util.List;

/**
 * Everything a robot tells its teammates in one turn, sent once at the end of the turn: one message is serialized
 * once, however much it carries.
 *
 * @param state      the sender's own state
 * @param sightings  the sender's scans of enemies in this turn
 * @param shots      the bullets the sender fired in this turn
 * @param events     what changed an enemy's energy in this turn, as the sender saw it
 */
public record TeamPacket(TeammateState state, List<EnemySighting> sightings, List<TeamShot> shots,
                         List<TeamEvent> events) implements Serializable {

    private static final long serialVersionUID = 1L;
}
