package cz.libors.ogi.radar;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.physics.RadarScan;
import cz.libors.ogi.physics.RobotMotion;
import cz.libors.ogi.radar.MeleeRadar.Seen;
import cz.libors.ogi.radar.TeamRadar.Arc;
import cz.libors.ogi.radar.TeamRadar.Watcher;
import cz.libors.ogi.trace.Trace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamRadarTest {

    private static final long NOW = 100;

    @Test
    void theArcHoldsEveryEnemyAcrossZero() {
        double a = Math.toRadians(10);
        List<Seen> group = List.of(new Seen("up", 500 + 400 * Math.cos(a), 500 + 400 * Math.sin(a), NOW - 1),
                new Seen("down", 500 + 400 * Math.cos(a), 500 - 400 * Math.sin(a), NOW - 1));
        Arc arc = TeamRadar.arc(NOW, 500, 500, group);
        // Scanned last turn: each may have driven two turns at full speed by the end of the next, plus half a robot.
        double margin = Math.atan((8 * 2 + 18) / 400.0);
        assertEquals(0, Angles.normalizeRelative(arc.centre()), 1e-12);
        assertEquals(a + margin, arc.halfWidth(), 1e-12);
    }

    @Test
    void theSweepCrossesTheWholeArcToItsFartherEnd() {
        Arc arc = new Arc(0, 0.2);
        assertEquals(-0.4, TeamRadar.sweep(0.2, arc), 1e-12);
        assertEquals(0.4, TeamRadar.sweep(-0.2, arc), 1e-12);
        // From outside, past the near end to the far one.
        assertEquals(-1.2, TeamRadar.sweep(1.0, arc), 1e-12);
        assertEquals(1.2, TeamRadar.sweep(-1.0, arc), 1e-12);
    }

    /** a sees e1 and e2 within 16° (one sweep), and e3 too within 37°; b sees e3 alone, nearer. */
    private static final List<Seen> THREE = List.of(new Seen("e1", 600, 620, NOW - 1),
            new Seen("e2", 600, 580, NOW - 1), new Seen("e3", 1000, 1000, NOW - 1));

    @Test
    void oneRobotWatchesTwoEnemiesOnlyWhereOneEachWillNotDo() {
        List<Watcher> two = List.of(new Watcher("a", 200, 600, 0, NOW), new Watcher("b", 1000, 600, Math.PI / 2, NOW));
        Map<String, List<String>> plan = TeamRadar.assign(NOW, two, THREE);
        assertEquals(List.of("e1", "e2"), plan.get("a"));
        assertEquals(List.of("e3"), plan.get("b"));
        // Alone, a watches all three in one sweep.
        Map<String, List<String>> alone = TeamRadar.assign(NOW, List.of(two.get(0)), THREE);
        assertEquals(List.of("e1", "e2", "e3"), alone.get("a"));
    }

    @Test
    void enemiesOutOfEveryRadarsRangeAreLeftOut() {
        Map<String, List<String>> plan = TeamRadar.assign(NOW, List.of(new Watcher("a", 100, 100, 0, NOW)),
                List.of(new Seen("far", 1150, 1150, NOW - 1), new Seen("near", 400, 100, NOW - 1)));
        assertEquals(List.of("near"), plan.get("a"));
    }

    @Test
    void robotsKeepTheEnemiesTheirRadarsPointAt() {
        // a and b are alike for both enemies, which lie 90° apart from each of them: one each, and the one whose
        // radar points at an enemy keeps it.
        List<Seen> enemies = List.of(new Seen("e1", 200, 600, NOW - 1), new Seen("e2", 1000, 600, NOW - 1));
        Map<String, List<String>> plan = TeamRadar.assign(NOW,
                List.of(new Watcher("a", 600, 200, Math.toRadians(135), NOW),
                        new Watcher("b", 600, 1000, Math.toRadians(315), NOW)), enemies);
        assertEquals(List.of("e1"), plan.get("a"));
        assertEquals(List.of("e2"), plan.get("b"));
        Map<String, List<String>> turned = TeamRadar.assign(NOW,
                List.of(new Watcher("a", 600, 200, Math.toRadians(45), NOW),
                        new Watcher("b", 600, 1000, Math.toRadians(225), NOW)), enemies);
        assertEquals(List.of("e2"), turned.get("a"));
        assertEquals(List.of("e1"), turned.get("b"));
    }

    @Test
    void everyRobotGetsThePlanWhateverTheOrderOfItsInputs() {
        Random random = new Random(7);
        List<Watcher> team = new ArrayList<>();
        List<Seen> enemies = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            team.add(new Watcher("w" + i, 100 + 1000 * random.nextDouble(), 100 + 1000 * random.nextDouble(),
                    2 * Math.PI * random.nextDouble(), NOW));
            enemies.add(new Seen("e" + i, 100 + 1000 * random.nextDouble(), 100 + 1000 * random.nextDouble(),
                    NOW - 1 - random.nextInt(4)));
        }
        Map<String, List<String>> plan = TeamRadar.assign(NOW, team, enemies);
        for (int k = 0; k < 10; k++) {
            Collections.shuffle(team, random);
            Collections.shuffle(enemies, random);
            assertEquals(plan, TeamRadar.assign(NOW, team, enemies));
        }
        // Every enemy in some robot's range goes to exactly one robot: none twice, none left out.
        List<String> watched = new ArrayList<>();
        plan.values().forEach(watched::addAll);
        assertEquals(watched.stream().distinct().count(), watched.size());
        Set<String> inRange = new TreeSet<>();
        for (Seen e : enemies) {
            for (Watcher w : team) {
                if (Math.hypot(e.x() - w.x(), e.y() - w.y()) + RobotMotion.MAX_VELOCITY * (NOW + 1 - e.time())
                        <= RadarScan.RADIUS) {
                    inRange.add(e.name());
                }
            }
        }
        assertEquals(inRange, new TreeSet<>(watched));
        assertTrue(inRange.size() >= 4, "most enemies within some robot's range: " + inRange);
    }

    @Test
    void thePlanUsesOnlyScansTheWholeTeamHas() {
        TeamRadar radar = new TeamRadar();
        assertNull(radar.watch(NOW, "a"), "no plan before our own report");
        radar.reported(new Watcher("a", 200, 600, 0, NOW));
        // Our scan of this turn reaches the teammates in the next one: it is not planned with yet.
        radar.scanned("e1", 600, 620, NOW);
        assertEquals(List.of(), radar.watch(NOW, "a"));
        radar.reported(new Watcher("a", 200, 600, 0, NOW + 1));
        assertEquals(List.of("e1"), radar.watch(NOW + 1, "a"));
        radar.died("e1");
        radar.reported(new Watcher("a", 200, 600, 0, NOW + 2));
        assertEquals(List.of(), radar.watch(NOW + 2, "a"));
    }

    @Test
    void twoScansOfOneTurnAgreeWhicheverCameFirst() {
        // Two robots computed the same enemy's position from their own; the robots receive them in either order.
        TeamRadar first = new TeamRadar();
        TeamRadar second = new TeamRadar();
        for (TeamRadar r : List.of(first, second)) {
            r.reported(new Watcher("a", 200, 600, 0, NOW));
            r.reported(new Watcher("b", 1000, 600, Math.PI, NOW));
        }
        first.scanned("e1", 600.0000000001, 620, NOW - 1);
        first.scanned("e1", 600, 620, NOW - 1);
        second.scanned("e1", 600, 620, NOW - 1);
        second.scanned("e1", 600.0000000001, 620, NOW - 1);
        assertEquals(first.watch(NOW, "a"), second.watch(NOW, "a"));
        assertEquals(first.watch(NOW, "b"), second.watch(NOW, "b"));
        assertTrue(first.watch(NOW, "a").size() + first.watch(NOW, "b").size() == 1);
    }

    /** The overlay's view is the plan the robot follows, the same whichever robot paints it. */
    @Test
    void theOverlaySeesThePlanEveryRobotFollows() {
        TeamRadar radar = new TeamRadar();
        assertNull(radar.describe(NOW, "a"), "no plan yet");
        radar.reported(new Watcher("a", 200, 600, 0, NOW));
        radar.reported(new Watcher("b", 1000, 600, Math.PI / 2, NOW));
        for (Seen s : THREE) {
            radar.scanned(s.name(), s.x(), s.y(), s.time());
        }
        radar.scanned("e3", 1000, 1000, NOW);
        List<String> mine = radar.watch(NOW, "a");
        Trace.RadarView view = radar.describe(NOW, "a");
        assertEquals(List.of("a", "b"), view.watchers().stream().map(Trace.RadarWatcher::name).toList());
        assertTrue(view.watchers().get(0).me());
        assertEquals(List.of("e1", "e2", "e3"), view.enemies().stream().map(Trace.RadarEnemy::name).toList());
        for (Trace.RadarEnemy e : view.enemies()) {
            assertEquals(mine.contains(e.name()) ? 0 : 1, e.watcher(), e.name());
        }
        assertEquals(1, view.enemies().get(0).age());
        assertEquals(0, view.enemies().get(2).age(), "this turn's scan, not yet in the plan");
        Trace.RadarWatcher a = view.watchers().get(0);
        assertTrue(Math.abs(Angles.normalizeRelative(a.arcCentre() - Angles.bearing(200, 600, 600, 600))) < 0.01);
        assertNull(radar.describe(NOW + TeamRadar.MAX_REPORT_AGE + 1, "a"), "the plan is too old");
    }
}
