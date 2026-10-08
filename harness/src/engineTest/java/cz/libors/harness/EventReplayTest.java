package cz.libors.harness;

import cz.libors.harness.recording.Recording;
import cz.libors.harness.recording.Recording.BulletTick;
import cz.libors.harness.recording.Recording.RobotTick;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.EngineAngles;
import cz.libors.ogi.physics.EngineBullet;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.RadarScan;
import cz.libors.ogi.physics.RobotBox;
import cz.libors.ogi.physics.RobotMotion;
import org.junit.jupiter.api.Test;

import java.awt.geom.Arc2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the events every probe received against what happened in the recording: each event arrives in the turn
 * of what it reports, with the engine's values, and nothing that happened to a live probe goes unreported.
 *
 * <p>Expected events per robot and turn: scans of every live robot the radar's pie touches (from {@link RadarScan},
 * fields bit for bit), bullet hits on the robot and by it, its bullets lost on walls or other bullets, wall hits,
 * rams in both roles, and other robots' deaths. A robot's events are checked only in turns in which it acted.
 */
class EventReplayTest {

    /** An event the recording says a robot must have received in a turn. */
    private record Expected(String type, String description, Predicate<String[]> matches) {
    }

    @Test
    void probesReceiveEveryEventInTheTurnItHappens() throws IOException {
        Mismatches mismatches = new Mismatches();
        Map<String, Integer> received = new TreeMap<>();
        for (ProbeTrace trace : ProbeTrace.loadAll()) {
            Map<Long, List<Expected>> expected = expected(trace);
            for (Recording.RoundEnd round : trace.recording.roundEnds()) {
                for (int turn = 1; turn <= round.turns(); turn++) {
                    for (Recording.RobotInfo robot : trace.recording.robots()) {
                        compare(trace, round.round(), turn, robot.index(),
                                expected.getOrDefault(ProbeTrace.key(round.round(), turn, robot.index()), List.of()),
                                mismatches, received);
                    }
                }
            }
        }
        System.out.printf("Events matched %s, mismatches %s%n", received, mismatches.byField);
        mismatches.examples.forEach(e -> System.out.println("  " + e));
        for (String type : List.of("ScannedRobot", "HitByBullet", "BulletHit", "BulletMissed", "BulletHitBullet",
                "HitWall", "HitRobot", "RobotDeath")) {
            assertTrue(received.getOrDefault(type, 0) > 0, "No " + type + " events checked");
        }
        assertEquals(Map.of(), mismatches.byField, "Events and recording disagree, examples:\n"
                + String.join("\n", mismatches.examples));
    }

    private static void compare(ProbeTrace trace, int round, int turn, int robot, List<Expected> expected,
                                Mismatches mismatches, Map<String, Integer> received) {
        RobotTick now = trace.tick(round, turn, robot);
        if (now == null || now.state().equals("DEAD") || trace.command(round, turn, robot) == null) {
            return;
        }
        String where = trace.name + " round " + round + " turn " + turn + " robot " + robot;
        List<String[]> actual = new ArrayList<>(trace.events(round, turn, robot));
        for (String[] e : actual) {
            if (Long.parseLong(e[1]) != turn) {
                mismatches.add(where, "time", e[0] + " stamped " + e[1]);
            }
        }
        for (Expected x : expected) {
            Iterator<String[]> it = actual.iterator();
            boolean found = false;
            while (it.hasNext()) {
                String[] e = it.next();
                if (e[0].equals(x.type()) && x.matches().test(e)) {
                    it.remove();
                    found = true;
                    received.merge(x.type(), 1, Integer::sum);
                    break;
                }
            }
            if (!found) {
                mismatches.add(where, "missing " + x.type(), x.description() + "; received "
                        + actual.stream().filter(e -> e[0].equals(x.type())).map(e -> String.join(",", e)).toList());
            }
        }
        for (String[] e : actual) {
            // A robot that rammed and hit a wall in the same turn shows only HIT_ROBOT, so its wall hit is unseen.
            boolean unseenWallHit = e[0].equals("HitWall") && now.state().equals("HIT_ROBOT");
            if (unseenWallHit) {
                received.merge("HitWall", 1, Integer::sum);
            } else {
                mismatches.add(where, "unexpected " + e[0], String.join(",", e));
            }
        }
    }

    /** Every event the recording implies, keyed by round, turn and receiving robot. */
    private static Map<Long, List<Expected>> expected(ProbeTrace trace) {
        Map<Long, List<Expected>> out = new HashMap<>();
        List<Recording.RobotInfo> robots = trace.recording.robots();
        bulletEvents(trace, out);
        for (Recording.RoundEnd round : trace.recording.roundEnds()) {
            int r = round.round();
            for (int turn = 1; turn <= round.turns(); turn++) {
                for (Recording.RobotInfo robot : robots) {
                    RobotTick before = trace.tick(r, turn - 1, robot.index());
                    RobotTick now = trace.tick(r, turn, robot.index());
                    if (before == null || now == null) {
                        continue;
                    }
                    if (!before.state().equals("DEAD") && now.state().equals("DEAD")) {
                        for (Recording.RobotInfo other : robots) {
                            RobotTick o = trace.tick(r, turn, other.index());
                            if (other.index() != robot.index() && !o.state().equals("DEAD")) {
                                add(out, r, turn, other.index(), new Expected("RobotDeath", robot.name(),
                                        e -> e[2].equals(robot.name())));
                            }
                        }
                    }
                    if (now.state().equals("DEAD") || before.state().equals("DEAD")) {
                        continue;
                    }
                    if (now.state().equals("HIT_WALL")) {
                        List<Double> bearings = wallBearings(now, trace.width, trace.height);
                        add(out, r, turn, robot.index(), new Expected("HitWall", "bearing one of " + bearings,
                                e -> bearings.stream().anyMatch(b -> same(e[2], b))));
                    }
                    scanEvents(trace, r, turn, robot.index(), before, now, out);
                }
                ramEvents(trace, r, turn, out);
            }
        }
        return out;
    }

    /** Hits on robots, walls and bullets, reported to the owner and, for robot hits, to the victim. */
    private static void bulletEvents(ProbeTrace trace, Map<Long, List<Expected>> out) {
        Map<Long, List<BulletTick>> bulletHitsByTurn = new HashMap<>();
        Map<Long, TreeMap<Integer, BulletTick>> tracks = trace.bulletTracks();
        for (TreeMap<Integer, BulletTick> track : tracks.values()) {
            BulletTick end = track.values().stream().filter(b -> !b.state().equals("MOVING")).findFirst()
                    .orElse(null);
            if (end != null && end.state().equals("HIT_BULLET")) {
                bulletHitsByTurn.computeIfAbsent(ProbeTrace.key(end.round(), end.turn(), 0), k -> new ArrayList<>())
                        .add(end);
            }
        }
        for (TreeMap<Integer, BulletTick> track : tracks.values()) {
            BulletTick end = track.values().stream().filter(b -> !b.state().equals("MOVING")).findFirst()
                    .orElse(null);
            if (end == null) {
                continue;
            }
            int r = end.round();
            int turn = end.turn();
            // Events carry the bullet where its move ended, before the engine moves it back into the victim's box.
            EngineBullet moved = movedInto(trace, track, end);
            String ownerName = trace.recording.robots().get(end.owner()).name();
            switch (end.state()) {
                case "HIT_VICTIM" -> {
                    String victimName = trace.recording.robots().get(end.victim()).name();
                    add(out, r, turn, end.victim(), new Expected("HitByBullet", "from " + ownerName,
                            e -> e[2].equals(ownerName) && same(e[3], end.power()) && same(e[4], end.heading())
                                    && same(e[5], moved.x) && same(e[6], moved.y)));
                    add(out, r, turn, end.owner(), new Expected("BulletHit", "on " + victimName,
                            e -> e[2].equals(victimName) && same(e[4], end.power()) && same(e[5], end.heading())
                                    && same(e[6], moved.x) && same(e[7], moved.y)));
                }
                case "HIT_WALL" -> add(out, r, turn, end.owner(), new Expected("BulletMissed", "at " + end.x(),
                        e -> same(e[2], end.power()) && same(e[3], end.heading()) && same(e[4], end.x())
                                && same(e[5], end.y())));
                case "HIT_BULLET" -> {
                    List<BulletTick> others = bulletHitsByTurn.get(ProbeTrace.key(r, turn, 0)).stream()
                            .filter(o -> o != end).toList();
                    add(out, r, turn, end.owner(), new Expected("BulletHitBullet", "bullet " + end.bulletId(),
                            e -> same(e[2], end.power()) && same(e[3], end.heading()) && same(e[4], end.x())
                                    && same(e[5], end.y()) && others.stream().anyMatch(o ->
                                    e[6].equals(trace.recording.robots().get(o.owner()).name())
                                            && same(e[7], o.power()) && same(e[8], o.heading())
                                            && same(e[9], o.x()) && same(e[10], o.y()))));
                }
                default -> {
                }
            }
        }
    }

    /** The bullet after the move in which it hit something, from its previous position or the shooter. */
    private static EngineBullet movedInto(ProbeTrace trace, TreeMap<Integer, BulletTick> track, BulletTick end) {
        Map.Entry<Integer, BulletTick> previous = track.lowerEntry(end.turn());
        EngineBullet b;
        if (previous != null) {
            b = new EngineBullet();
            b.x = previous.getValue().x();
            b.y = previous.getValue().y();
            b.heading = previous.getValue().heading();
            b.power = previous.getValue().power();
        } else {
            EngineRobot shooter = new EngineRobot();
            RobotMotionReplayTest.load(shooter, trace.tick(end.round(), end.turn() - 1, end.owner()));
            b = BulletMotion.fired(shooter, end.power());
        }
        BulletMotion.move(b);
        return b;
    }

    /** Every ram: the robot at fault and its victim report each other. */
    private static void ramEvents(ProbeTrace trace, int round, int turn, Map<Long, List<Expected>> out) {
        for (Recording.RobotInfo robot : trace.recording.robots()) {
            for (String[] e : trace.events(round, turn, robot.index(), "HitRobot")) {
                if (!Boolean.parseBoolean(e[5])) {
                    continue;
                }
                int victim = trace.robotIndex(e[2]);
                RobotTick v = trace.tick(round, turn, victim);
                if (v != null && !v.state().equals("DEAD")) {
                    add(out, round, turn, victim, new Expected("HitRobot", "rammed by " + robot.name(),
                            x -> x[2].equals(robot.name()) && x[5].equals("false")));
                }
                add(out, round, turn, robot.index(), new Expected("HitRobot", "rams " + e[2],
                        x -> x[2].equals(e[2]) && x[5].equals("true")));
            }
        }
    }

    /** Scans of the turn, with every field as the engine computes it from the positions after all moves. */
    private static void scanEvents(ProbeTrace trace, int round, int turn, int robot, RobotTick before, RobotTick now,
                                   Map<Long, List<Expected>> out) {
        EngineRobot b = new EngineRobot();
        RobotMotionReplayTest.load(b, before);
        EngineRobot a = new EngineRobot();
        RobotMotionReplayTest.load(a, now);
        if (!RadarScan.scans(b, a)) {
            return;
        }
        Arc2D sweep = RadarScan.sweep(now.x(), now.y(), before.radarHeading(), now.radarHeading());
        for (Recording.RobotInfo other : trace.recording.robots()) {
            RobotTick t = trace.tick(round, turn, other.index());
            if (other.index() == robot || t.state().equals("DEAD")
                    || !RadarScan.sees(sweep, new RobotBox(t.x(), t.y()))) {
                continue;
            }
            double bearing = RadarScan.bearing(now.x(), now.y(), now.bodyHeading(), t.x(), t.y());
            double distance = RadarScan.distance(now.x(), now.y(), t.x(), t.y());
            add(out, round, turn, robot, new Expected("ScannedRobot", other.name(),
                    e -> e[2].equals(other.name()) && same(e[3], bearing) && same(e[4], distance)
                            && same(e[5], t.bodyHeading()) && same(e[6], t.velocity()) && same(e[7], t.energy())));
        }
    }

    /**
     * {@code RobotPeer.checkWallCollision}'s bearing for each wall the robot stands at after the hit. When it stands
     * in a corner, the recording does not tell which walls it crossed, so both are candidates.
     */
    private static List<Double> wallBearings(RobotTick t, int width, int height) {
        int min = RobotMotion.HALF_SIZE;
        List<Double> bearings = new ArrayList<>();
        if (t.x() == min) {
            bearings.add(EngineAngles.normalRelative(3 * Math.PI / 2 - t.bodyHeading()));
        } else if (t.x() == width - min) {
            bearings.add(EngineAngles.normalRelative(Math.PI / 2 - t.bodyHeading()));
        }
        if (t.y() == min) {
            bearings.add(EngineAngles.normalRelative(Math.PI - t.bodyHeading()));
        } else if (t.y() == height - min) {
            bearings.add(EngineAngles.normalRelative(-t.bodyHeading()));
        }
        return bearings;
    }

    private static void add(Map<Long, List<Expected>> out, int round, int turn, int robot, Expected e) {
        out.computeIfAbsent(ProbeTrace.key(round, turn, robot), k -> new ArrayList<>()).add(e);
    }

    private static boolean same(String logged, double value) {
        return Double.doubleToLongBits(Double.parseDouble(logged)) == Double.doubleToLongBits(value);
    }
}
