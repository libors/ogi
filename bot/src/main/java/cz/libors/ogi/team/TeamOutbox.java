package cz.libors.ogi.team;

import java.util.ArrayList;
import java.util.List;

/**
 * What this robot tells its teammates about a turn, gathered while it acts: its scans of enemies, the bullets it fired
 * and the events that changed an enemy's energy. They go out once at the turn's end, in one {@link TeamPacket}.
 */
public final class TeamOutbox {

    private final List<EnemySighting> sightings = new ArrayList<>();
    private final List<TeamShot> shots = new ArrayList<>();
    private final List<TeamEvent> events = new ArrayList<>();

    public void sighting(EnemySighting s) {
        sightings.add(s);
    }

    public void shot(TeamShot s) {
        shots.add(s);
    }

    public void event(TeamEvent e) {
        events.add(e);
    }

    /** The turn's packet with our own state; the outbox is empty afterwards. */
    public TeamPacket take(TeammateState me) {
        TeamPacket packet = new TeamPacket(me, List.copyOf(sightings), List.copyOf(shots), List.copyOf(events));
        clear();
        return packet;
    }

    /** Forgets the turn: nobody is told. */
    public void clear() {
        sightings.clear();
        shots.clear();
        events.clear();
    }
}
