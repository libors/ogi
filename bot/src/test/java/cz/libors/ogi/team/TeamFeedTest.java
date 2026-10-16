package cz.libors.ogi.team;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TeamFeedTest {

    /** Records what a flush hands on, in order. */
    private static final class Log implements TeamFeed.Sink {
        final List<String> lines = new ArrayList<>();

        @Override
        public void zapped(long time) {
            lines.add("zap " + time);
        }

        @Override
        public void event(TeamEvent e) {
            lines.add(e.kind() + " " + e.enemy() + " " + e.time());
        }

        @Override
        public void scanned(EnemySighting s) {
            lines.add("scan " + s.name() + " " + s.time() + " " + s.energy());
        }
    }

    private static EnemySighting scan(String name, long time, double energy) {
        return new EnemySighting(name, 100, 100, 0, 0, energy, time, 0);
    }

    /**
     * Our own data of turn 11 come in turn 11, a teammate's of turn 10 in turn 11 too, after ours of turn 10: in turn
     * 11 turn 10 is handed on whole, the penalty first, then the events, then one scan per enemy.
     */
    @Test
    void aTurnIsHandedOnWholeWhenItsReportsAreIn() {
        TeamFeed feed = new TeamFeed();
        Log log = new Log();
        feed.sighting(scan("b", 10, 90));
        feed.sighting(scan("a", 10, 80));
        feed.sighting(scan("a", 11, 79));
        feed.event(new TeamEvent("a", TeamEvent.Kind.HIT_BY_US, 2, 10, 0));
        feed.sighting(scan("a", 10, 80));
        feed.zapped(10);
        feed.flush(10, log);
        assertEquals(List.of("zap 10", "HIT_BY_US a 10", "scan a 10 80.0", "scan b 10 90.0"), log.lines);
        feed.sighting(scan("c", 10, 50));
        feed.event(new TeamEvent("c", TeamEvent.Kind.RAM, 0, 9, 0));
        assertEquals(2, feed.late(), "data of a turn already handed on are dropped");
        log.lines.clear();
        feed.flush(11, log);
        assertEquals(List.of("scan a 11 79.0"), log.lines);
    }
}
