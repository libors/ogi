package cz.libors.harness;

import cz.libors.harness.recording.Recording;
import cz.libors.harness.recording.Recording.RobotTick;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.RadarScan;
import cz.libors.ogi.physics.RobotBox;
import org.junit.jupiter.api.Test;

import java.awt.geom.Arc2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine-model G-52, on the probes' events in the golden recordings ({@code ev}, logged in the order the handlers
 * ran): a turn's events come by priority, highest first; in the handlers {@code getOthers()} already leaves out the
 * robots that died in the turn; and no robot scans a robot in the turn it died, even with the robot in its radar's
 * sweep. {@code recordProbes} checks team messages against deaths ({@code MessageTeam}).
 */
class EventOrderTest {

    /** The default priorities of the events the probes log ({@code robocode.*Event.DEFAULT_PRIORITY}). */
    private static final Map<String, Integer> PRIORITY = Map.of("SkippedTurn", 100, "RobotDeath", 70,
            "BulletMissed", 60, "BulletHitBullet", 55, "BulletHit", 50, "HitRobot", 40, "HitWall", 30,
            "HitByBullet", 20, "ScannedRobot", 10);

    @Test
    void aTurnsEventsComeByPriorityHighestFirst() throws IOException {
        // "earlier > later": how often an event of one type came right before one of another type in a turn
        Map<String, Integer> pairs = new TreeMap<>();
        List<String> examples = new ArrayList<>();
        for (ProbeTrace trace : ProbeTrace.loadAll()) {
            for (Recording.RoundEnd round : trace.recording.roundEnds()) {
                for (int turn = 1; turn <= round.turns(); turn++) {
                    for (Recording.RobotInfo robot : trace.recording.robots()) {
                        if (trace.command(round.round(), turn, robot.index()) == null) {
                            continue;
                        }
                        List<String[]> events = trace.events(round.round(), turn, robot.index());
                        for (int i = 1; i < events.size(); i++) {
                            String earlier = events.get(i - 1)[0];
                            String later = events.get(i)[0];
                            if (earlier.equals(later)) {
                                continue;
                            }
                            pairs.merge(earlier + " > " + later, 1, Integer::sum);
                            if (PRIORITY.get(earlier) < PRIORITY.get(later) && examples.size() < 10) {
                                examples.add(trace.name + " round " + round.round() + " turn " + turn + " robot "
                                        + robot.index() + ": " + earlier + " before " + later);
                            }
                        }
                    }
                }
            }
        }
        int outOfOrder = 0;
        for (Map.Entry<String, Integer> pair : pairs.entrySet()) {
            String[] types = pair.getKey().split(" > ");
            outOfOrder += PRIORITY.get(types[0]) < PRIORITY.get(types[1]) ? pair.getValue() : 0;
        }
        System.out.printf("Event order, neighbouring events of different types: %s%n", pairs);
        assertEquals(0, outOfOrder, "events out of priority order:\n" + String.join("\n", examples));
        // Every kind the probes log met a scan in some turn, and a death met a hit on the probe.
        for (String type : List.of("RobotDeath", "BulletMissed", "BulletHitBullet", "BulletHit", "HitRobot",
                "HitWall", "HitByBullet")) {
            assertTrue(pairs.getOrDefault(type + " > ScannedRobot", 0) > 0, "no turn with " + type + " and a scan");
        }
        assertTrue(pairs.getOrDefault("RobotDeath > HitByBullet", 0) > 0, "no turn with a death and a hit");
    }

    @Test
    void theHandlersNoLongerCountTheRobotsThatDiedInTheTurn() throws IOException {
        int checked = 0;
        List<String> wrong = new ArrayList<>();
        for (ProbeTrace trace : ProbeTrace.loadAll()) {
            for (Recording.RoundEnd round : trace.recording.roundEnds()) {
                for (int turn = 1; turn <= round.turns(); turn++) {
                    int alive = 0;
                    for (Recording.RobotInfo r : trace.recording.robots()) {
                        alive += trace.tick(round.round(), turn, r.index()).state().equals("DEAD") ? 0 : 1;
                    }
                    for (Recording.RobotInfo r : trace.recording.robots()) {
                        if (trace.command(round.round(), turn, r.index()) == null) {
                            continue;
                        }
                        for (String[] e : trace.events(round.round(), turn, r.index(), "RobotDeath")) {
                            // Every other robot alive at the end of this turn's update; the dead one is not.
                            if (Integer.parseInt(e[3]) != alive - 1) {
                                wrong.add(trace.name + " round " + round.round() + " turn " + turn + " robot "
                                        + r.index() + ": getOthers() " + e[3] + ", others alive " + (alive - 1));
                            }
                            checked++;
                        }
                    }
                }
            }
        }
        System.out.printf("getOthers() in onRobotDeath: %d deaths, %d wrong%n", checked, wrong.size());
        assertEquals(List.of(), wrong.subList(0, Math.min(10, wrong.size())));
        assertTrue(checked > 100, "too few deaths: " + checked);
    }

    @Test
    void nobodyScansARobotInTheTurnItDied() throws IOException {
        int inSweep = 0;
        List<String> scanned = new ArrayList<>();
        for (ProbeTrace trace : ProbeTrace.loadAll()) {
            List<Recording.RobotInfo> robots = trace.recording.robots();
            for (Recording.RoundEnd round : trace.recording.roundEnds()) {
                int r = round.round();
                for (int turn = 1; turn <= round.turns(); turn++) {
                    for (Recording.RobotInfo dying : robots) {
                        RobotTick before = trace.tick(r, turn - 1, dying.index());
                        RobotTick now = trace.tick(r, turn, dying.index());
                        if (before.state().equals("DEAD") || !now.state().equals("DEAD")) {
                            continue;
                        }
                        for (Recording.RobotInfo scanner : robots) {
                            RobotTick from = trace.tick(r, turn - 1, scanner.index());
                            RobotTick to = trace.tick(r, turn, scanner.index());
                            if (scanner.index() == dying.index() || to.state().equals("DEAD")
                                    || trace.command(r, turn, scanner.index()) == null
                                    || !sweeps(from, to, now)) {
                                continue;
                            }
                            inSweep++;
                            for (String[] e : trace.events(r, turn, scanner.index(), "ScannedRobot")) {
                                if (e[2].equals(dying.name())) {
                                    scanned.add(trace.name + " round " + r + " turn " + turn + ": robot "
                                            + scanner.index() + " scanned " + dying.name());
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.printf("Robots in a radar's sweep in the turn they died: %d, scanned %d%n", inSweep,
                scanned.size());
        assertEquals(List.of(), scanned);
        assertTrue(inSweep > 20, "too few deaths inside a sweep: " + inSweep);
    }

    /** Whether the scanner's radar swept the robot's box in this turn, as it would see a live robot (G-13). */
    private static boolean sweeps(RobotTick from, RobotTick to, RobotTick target) {
        EngineRobot b = new EngineRobot();
        RobotMotionReplayTest.load(b, from);
        EngineRobot a = new EngineRobot();
        RobotMotionReplayTest.load(a, to);
        if (!RadarScan.scans(b, a)) {
            return false;
        }
        Arc2D sweep = RadarScan.sweep(to.x(), to.y(), from.radarHeading(), to.radarHeading());
        return RadarScan.sees(sweep, new RobotBox(target.x(), target.y()));
    }
}
