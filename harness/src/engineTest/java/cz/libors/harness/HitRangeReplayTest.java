package cz.libors.harness;

import cz.libors.harness.recording.Recording;
import cz.libors.harness.recording.Recording.BulletTick;
import cz.libors.harness.recording.Recording.RobotTick;
import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.physics.RobotBox;
import cz.libors.ogi.wave.HitRange;
import cz.libors.ogi.wave.Wave;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every recorded bullet against {@link HitRange}: in each turn of its flight, the bullet's heading must lie in the
 * hit range of the robot it hit, and outside the range of every robot it flew past. Turns in which the bullet hit
 * another bullet, or in which a robot died, depend on the engine's processing order and are left out.
 */
class HitRangeReplayTest {

    /** Headings this close to a range's edge are not classified; the engine sums the moves, the range multiplies. */
    private static final double EDGE = 1e-9;

    @Test
    void bulletsHitExactlyTheRobotsWhoseRangeTheirHeadingIsIn() throws IOException {
        Mismatches mismatches = new Mismatches();
        Map<String, Integer> counts = new TreeMap<>();
        for (ProbeTrace trace : ProbeTrace.loadAll()) {
            for (TreeMap<Integer, BulletTick> track : trace.bulletTracks().values()) {
                replay(trace, track, mismatches, counts);
            }
        }
        System.out.printf("Hit range checks %s, mismatches %s%n", counts, mismatches.byField);
        mismatches.examples.forEach(e -> System.out.println("  " + e));
        assertTrue(counts.getOrDefault("inside, hit", 0) > 500, "Too few hits: " + counts);
        assertTrue(counts.getOrDefault("outside, flew past", 0) > 10_000, "Too few misses: " + counts);
        assertEquals(Map.of(), mismatches.byField, "Hit range and engine disagree, examples:\n"
                + String.join("\n", mismatches.examples));
    }

    private static void replay(ProbeTrace trace, TreeMap<Integer, BulletTick> track, Mismatches mismatches,
                               Map<String, Integer> counts) {
        BulletTick first = track.firstEntry().getValue();
        int round = first.round();
        int fireTurn = first.turn() - 1;
        RobotTick shooter = trace.tick(round, fireTurn, first.owner());
        if (shooter == null) {
            return;
        }
        Wave wave = new Wave("owner", shooter.x(), shooter.y(), fireTurn, first.power());
        double heading = Angles.fromRobocode(first.heading());
        String id = trace.name + " round " + round + " bullet " + first.bulletId();
        for (BulletTick b : track.values()) {
            int turn = b.turn();
            if (b.state().equals("HIT_BULLET")) {
                count(counts, "turn left out: bullet hit a bullet");
                return;
            }
            for (Recording.RobotInfo robot : trace.recording.robots()) {
                int r = robot.index();
                RobotTick before = trace.tick(round, turn - 1, r);
                RobotTick after = trace.tick(round, turn, r);
                if (r == first.owner() || before == null || before.state().equals("DEAD")) {
                    continue;
                }
                double[] range = HitRange.turn(wave.x(), wave.y(), wave.radius(turn - 1), wave.radius(turn),
                        new RobotBox(before.x(), before.y()), heading, trace.width, trace.height);
                // The bullet's own heading is offset 0 from the reference.
                boolean inside = range != null && range[0] < -EDGE && range[1] > EDGE;
                boolean outside = range == null || range[0] > EDGE || range[1] < -EDGE;
                boolean hitThis = b.state().equals("HIT_VICTIM") && b.victim() == r;
                if (!inside && !outside) {
                    count(counts, "on an edge");
                } else if (hitThis) {
                    if (outside) {
                        mismatches.add(id + " turn " + turn, "hit outside the range", "robot " + r);
                    } else {
                        count(counts, "inside, hit");
                    }
                } else if (b.state().equals("HIT_VICTIM")) {
                    count(counts, "turn left out: another robot hit");
                } else if (after != null && after.state().equals("DEAD")) {
                    count(counts, "turn left out: robot died");
                } else if (inside) {
                    mismatches.add(id + " turn " + turn, "missed inside the range",
                            "robot " + r + ", bullet " + b.state());
                } else {
                    count(counts, b.state().equals("HIT_WALL") ? "outside, wall" : "outside, flew past");
                }
            }
            if (!b.state().equals("MOVING")) {
                return;
            }
        }
    }

    private static void count(Map<String, Integer> counts, String what) {
        counts.merge(what, 1, Integer::sum);
    }
}
