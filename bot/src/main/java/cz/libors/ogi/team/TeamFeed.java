package cz.libors.ogi.team;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The team's scans of enemies and the events that changed their energy, gathered turn by turn and handed on once a
 * turn is complete.
 *
 * <p>An enemy's shot shows as a drop of its energy between two scans, less what else changed it (engine-model G-14).
 * In a team every robot scans and every robot hits or is hit, and a teammate's report of turn t arrives in turn t + 1
 * (G-23). So the detector of an enemy's shots can take turn t only in turn t + 1, when all reports of t are in: each
 * turn's events first, then its scans. Data that come after their turn was handed on are dropped.
 */
public final class TeamFeed {

    /** Where the turns go, in order. */
    public interface Sink {

        /** The engine's inactivity penalty took energy from every robot in this turn (G-38). */
        void zapped(long time);

        void event(TeamEvent e);

        void scanned(EnemySighting s);
    }

    private static final class Turn {
        boolean zapped;
        final List<TeamEvent> events = new ArrayList<>();
        /** One scan per enemy, the first that came. */
        final Map<String, EnemySighting> scans = new TreeMap<>();
    }

    private final TreeMap<Long, Turn> turns = new TreeMap<>();
    private long handedOn = Long.MIN_VALUE;
    private int late;

    public void sighting(EnemySighting s) {
        Turn t = turn(s.time());
        if (t != null) {
            t.scans.putIfAbsent(s.name(), s);
        }
    }

    public void event(TeamEvent e) {
        Turn t = turn(e.time());
        if (t != null) {
            t.events.add(e);
        }
    }

    public void zapped(long time) {
        Turn t = turn(time);
        if (t != null) {
            t.zapped = true;
        }
    }

    /** Hands on every turn up to {@code upTo}, oldest first: in each, the penalty, then the events, then the scans. */
    public void flush(long upTo, Sink sink) {
        for (Iterator<Map.Entry<Long, Turn>> it = turns.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Turn> entry = it.next();
            if (entry.getKey() > upTo) {
                break;
            }
            Turn t = entry.getValue();
            if (t.zapped) {
                sink.zapped(entry.getKey());
            }
            for (TeamEvent e : t.events) {
                sink.event(e);
            }
            for (EnemySighting s : t.scans.values()) {
                sink.scanned(s);
            }
            it.remove();
        }
        handedOn = Math.max(handedOn, upTo);
    }

    /** Scans and events dropped because they came after their turn was handed on. */
    public int late() {
        return late;
    }

    private Turn turn(long time) {
        if (time <= handedOn) {
            late++;
            return null;
        }
        return turns.computeIfAbsent(time, k -> new Turn());
    }
}
