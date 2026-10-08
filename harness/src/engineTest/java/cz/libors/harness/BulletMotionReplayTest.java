package cz.libors.harness;

import cz.libors.harness.ProbeTrace.Command;
import cz.libors.harness.recording.Recording;
import cz.libors.harness.recording.Recording.BulletTick;
import cz.libors.harness.recording.Recording.RobotTick;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.EngineBullet;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.RobotBox;
import cz.libors.ogi.physics.RobotMotion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays every bullet of the probe recordings through {@link BulletMotion}, bit for bit:
 * <ul>
 *   <li><b>spawn:</b> a bullet first seen in turn t was fired by its owner's command of turn t − 1, with the power
 *       the engine allows, from the owner's position along its gun heading of turn t − 1, and moved once;</li>
 *   <li><b>each move:</b> from a moving bullet in turn t, predict turn t + 1: still moving, wall hit, or hit on a
 *       robot whose box (from turn t) the move crosses.</li>
 * </ul>
 * Bullet–bullet hits depend on the engine's random processing order, so they are taken from the recording. The test
 * checks where such a bullet stops: at the start of its last move if it moved before the hit, or one move further
 * back if the other bullet hit its previous segment before it moved (engine-model G-04).
 */
class BulletMotionReplayTest {

    @Test
    void bulletsMatchTheEngineInEveryRecordedTurn() throws IOException {
        Mismatches mismatches = new Mismatches();
        Map<String, Integer> outcomes = new TreeMap<>();
        for (ProbeTrace trace : ProbeTrace.loadAll()) {
            for (TreeMap<Integer, BulletTick> track : trace.bulletTracks().values()) {
                replay(trace, track, mismatches, outcomes);
            }
        }
        System.out.printf("Bullet outcomes %s, mismatches %s%n", outcomes, mismatches.byField);
        mismatches.examples.forEach(e -> System.out.println("  " + e));
        assertTrue(outcomes.getOrDefault("spawned", 0) > 5_000, "Too few bullets: " + outcomes);
        assertTrue(outcomes.getOrDefault("hit robot", 0) > 500, "Too few robot hits: " + outcomes);
        assertEquals(Map.of(), mismatches.byField, "Model and engine disagree, examples:\n"
                + String.join("\n", mismatches.examples));
    }

    private static void replay(ProbeTrace trace, TreeMap<Integer, BulletTick> track, Mismatches mismatches,
                               Map<String, Integer> outcomes) {
        BulletTick first = track.firstEntry().getValue();
        int round = first.round();
        int fireTurn = first.turn() - 1;
        String id = trace.name + " round " + round + " bullet " + first.bulletId();
        RobotTick shooter = trace.tick(round, fireTurn, first.owner());
        Command cmd = trace.command(round, fireTurn, first.owner());
        if (shooter == null || cmd == null || !cmd.fired()) {
            mismatches.add(id, "spawn", "no accepted fire command in turn " + fireTurn);
            return;
        }
        EngineRobot robot = new EngineRobot();
        RobotMotionReplayTest.load(robot, shooter);
        double power = RobotMotion.fire(robot, cmd.firePower());
        mismatches.compare(id, "spawn power", first.power(), power);
        EngineBullet start = BulletMotion.fired(robot, power);
        mismatches.compare(id, "spawn heading", first.heading(), start.heading);
        count(outcomes, "spawned");

        // The first move starts at the shooter; later ones at the bullet's previous position.
        EngineBullet previous = start;
        int turn = fireTurn;
        for (BulletTick next : track.values()) {
            check(trace, id, turn, previous, next, mismatches, outcomes);
            if (!next.state().equals("MOVING")) {
                return;
            }
            EngineBullet b = new EngineBullet();
            b.lastX = previous.x;
            b.lastY = previous.y;
            b.x = next.x();
            b.y = next.y();
            b.heading = next.heading();
            b.power = next.power();
            previous = b;
            turn = next.turn();
        }
    }

    /** Predicts one move of {@code from} (state of turn {@code turn}) and compares it with the recorded result. */
    private static void check(ProbeTrace trace, String id, int turn, EngineBullet from, BulletTick actual,
                              Mismatches mismatches, Map<String, Integer> outcomes) {
        String where = id + " turn " + actual.turn();
        if (actual.turn() != turn + 1) {
            mismatches.add(where, "gap", "no record of turn " + (turn + 1));
            return;
        }
        double positionBefore = from.lastX;
        double positionBeforeY = from.lastY;
        EngineBullet b = new EngineBullet();
        b.x = from.x;
        b.y = from.y;
        b.heading = from.heading;
        b.power = from.power;
        BulletMotion.move(b);
        boolean wall = BulletMotion.hitsWall(b, trace.width, trace.height);
        List<Integer> victims = wall ? List.of() : victims(trace, actual.round(), turn, actual.owner(), b);
        mismatches.compare(where, "heading", actual.heading(), b.heading);
        mismatches.compare(where, "power", actual.power(), b.power);

        switch (actual.state()) {
            case "HIT_BULLET" -> {
                if (same(actual, b.lastX, b.lastY)) {
                    count(outcomes, "hit bullet after moving");
                    if (wall || !victims.isEmpty()) {
                        String model = wall ? "HIT_WALL" : "HIT_VICTIM";
                        mismatches.add(where, "state", "engine HIT_BULLET, model " + model);
                    }
                } else if (same(actual, positionBefore, positionBeforeY)) {
                    count(outcomes, "hit bullet before moving");
                } else {
                    mismatches.add(where, "hit bullet position", "engine " + actual.x() + "," + actual.y());
                }
            }
            case "HIT_VICTIM" -> {
                if (!victims.contains(actual.victim())) {
                    mismatches.add(where, "victim", "engine " + actual.victim() + ", model " + victims
                            + (wall ? " (wall)" : ""));
                    return;
                }
                count(outcomes, victims.size() > 1 ? "hit robot, several crossed" : "hit robot");
                RobotTick victim = trace.tick(actual.round(), turn, actual.victim());
                double[] impact = BulletMotion.impact(b, new RobotBox(victim.x(), victim.y()));
                mismatches.compare(where, "x", actual.x(), impact[0]);
                mismatches.compare(where, "y", actual.y(), impact[1]);
            }
            case "HIT_WALL" -> {
                if (!wall) {
                    String model = victims.isEmpty() ? "MOVING" : "HIT_VICTIM";
                    mismatches.add(where, "state", "engine HIT_WALL, model " + model);
                    return;
                }
                count(outcomes, "hit wall");
                mismatches.compare(where, "x", actual.x(), b.x);
                mismatches.compare(where, "y", actual.y(), b.y);
            }
            case "MOVING" -> {
                if (wall) {
                    mismatches.add(where, "state", "engine MOVING, model HIT_WALL");
                    return;
                }
                if (!victims.isEmpty()) {
                    // Another bullet may have killed the robot earlier in the same turn.
                    boolean allKilled = victims.stream().allMatch(v ->
                            trace.tick(actual.round(), turn + 1, v).state().equals("DEAD"));
                    if (!allKilled) {
                        mismatches.add(where, "state", "engine MOVING, model HIT_VICTIM " + victims);
                        return;
                    }
                    count(outcomes, "crossed a robot killed earlier in the turn");
                }
                count(outcomes, "moved");
                mismatches.compare(where, "x", actual.x(), b.x);
                mismatches.compare(where, "y", actual.y(), b.y);
            }
            default -> mismatches.add(where, "state", "unexpected engine state " + actual.state());
        }
    }

    /** Robots, other than the owner and alive at the end of {@code turn}, whose box the move crosses. */
    private static List<Integer> victims(ProbeTrace trace, int round, int turn, int owner, EngineBullet b) {
        List<Integer> victims = new ArrayList<>();
        for (Recording.RobotInfo robot : trace.recording.robots()) {
            RobotTick r = trace.tick(round, turn, robot.index());
            if (robot.index() == owner || r == null || r.state().equals("DEAD")) {
                continue;
            }
            if (BulletMotion.hits(b, new RobotBox(r.x(), r.y()))) {
                victims.add(robot.index());
            }
        }
        return victims;
    }

    private static boolean same(BulletTick t, double x, double y) {
        return Double.doubleToLongBits(t.x()) == Double.doubleToLongBits(x)
                && Double.doubleToLongBits(t.y()) == Double.doubleToLongBits(y);
    }

    private static void count(Map<String, Integer> outcomes, String outcome) {
        outcomes.merge(outcome, 1, Integer::sum);
    }
}
