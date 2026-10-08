package cz.libors.ogi.wave;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.EngineBullet;
import cz.libors.ogi.physics.RobotBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HitRange} against a reference that flies single bullets turn by turn with the engine's arithmetic. */
class HitRangeTest {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final double TOLERANCE = 1e-9;

    @Test
    void stationaryTargetIsSeenUnderItsNearCorners() {
        Wave wave = new Wave("s", 100, 300, 0, 3);
        HitRange range = new HitRange(wave, 0, WIDTH, HEIGHT);
        for (long t = 0; !range.passed(); t++) {
            range.observe(t, 300, 300);
        }
        double expected = Math.atan2(18, 182);
        assertEquals(-expected, range.minOffset(), 1e-6);
        assertEquals(expected, range.maxOffset(), 1e-6);
    }

    @Test
    void matchesSingleBulletsOnRandomPaths() {
        Random random = new Random(3);
        int hits = 0;
        int wallClipped = 0;
        for (int scenario = 0; scenario < 300; scenario++) {
            Scenario s = Scenario.random(random, scenario % 3 == 0);
            HitRange range = new HitRange(s.wave, s.reference(), WIDTH, HEIGHT);
            for (int i = 0; i < s.path.size(); i++) {
                range.observe(s.wave.fireTime() + i, s.path.get(i)[0], s.path.get(i)[1]);
            }
            double[] brute = s.bruteForce();
            if (brute == null) {
                assertFalse(range.hits(), "scenario " + scenario + ": reference never hits");
                continue;
            }
            hits++;
            assertTrue(range.hits(), "scenario " + scenario + ": reference hits");
            assertEquals(brute[0], range.minOffset(), TOLERANCE, "scenario " + scenario + " min");
            assertEquals(brute[1], range.maxOffset(), TOLERANCE, "scenario " + scenario + " max");
            if (s.clippedByWall()) {
                wallClipped++;
            }
        }
        assertTrue(hits > 200, "too few hitting scenarios: " + hits);
        assertTrue(wallClipped > 0, "no scenario where the wall rule mattered");
    }

    /** A wave and a target path, one position per turn from the fire turn on. */
    private record Scenario(Wave wave, List<double[]> path) {

        static Scenario random(Random random, boolean nearWall) {
            double tx = nearWall ? 18 + random.nextDouble() * 10 : 50 + random.nextDouble() * (WIDTH - 100);
            double ty = 50 + random.nextDouble() * (HEIGHT - 100);
            double ox;
            double oy;
            do {
                ox = 20 + random.nextDouble() * (WIDTH - 40);
                oy = 20 + random.nextDouble() * (HEIGHT - 40);
            } while (Math.hypot(ox - tx, oy - ty) < 60);
            Wave wave = new Wave("s", ox, oy, 7, 0.1 + 2.9 * random.nextDouble());
            List<double[]> path = new ArrayList<>();
            double heading = random.nextDouble() * Angles.TWO_PI;
            double velocity = 8 * (2 * random.nextDouble() - 1);
            for (int i = 0; i < 200; i++) {
                path.add(new double[] {tx, ty});
                heading += 0.2 * random.nextGaussian();
                velocity = Math.max(-8, Math.min(8, velocity + random.nextGaussian()));
                tx = Math.max(18, Math.min(WIDTH - 18, tx + velocity * Math.cos(heading)));
                ty = Math.max(18, Math.min(HEIGHT - 18, ty + velocity * Math.sin(heading)));
            }
            return new Scenario(wave, path);
        }

        double reference() {
            return Angles.bearing(wave.x(), wave.y(), path.get(0)[0], path.get(0)[1]);
        }

        /** Whether a bullet along this offset hits the target, flown turn by turn as the engine does. */
        boolean hits(double offset, boolean ignoreWalls) {
            EngineBullet b = new EngineBullet();
            b.x = wave.x();
            b.y = wave.y();
            b.heading = Angles.toRobocode(reference() + offset);
            b.power = wave.power();
            for (double[] p : path) {
                BulletMotion.move(b);
                if (!ignoreWalls && BulletMotion.hitsWall(b, WIDTH, HEIGHT)) {
                    return false;
                }
                if (BulletMotion.hits(b, new RobotBox(p[0], p[1]))) {
                    return true;
                }
            }
            return false;
        }

        /** The lowest and highest hitting offsets, found on a grid and refined by bisection, or null. */
        double[] bruteForce() {
            return bruteForce(false);
        }

        boolean clippedByWall() {
            double[] withWalls = bruteForce(false);
            double[] without = bruteForce(true);
            return without != null && (withWalls == null || Math.abs(withWalls[0] - without[0]) > 1e-6
                    || Math.abs(withWalls[1] - without[1]) > 1e-6);
        }

        private double[] bruteForce(boolean ignoreWalls) {
            double step = 1e-3;
            Double first = null;
            Double last = null;
            for (double o = -Math.PI / 2; o <= Math.PI / 2; o += step) {
                if (hits(o, ignoreWalls)) {
                    first = first == null ? o : first;
                    last = o;
                }
            }
            if (first == null) {
                return null;
            }
            return new double[] {edge(first - step, first, ignoreWalls), edge(last + step, last, ignoreWalls)};
        }

        /** Bisects between a missing and a hitting offset. */
        private double edge(double miss, double hit, boolean ignoreWalls) {
            for (int i = 0; i < 60; i++) {
                double mid = (miss + hit) / 2;
                if (hits(mid, ignoreWalls)) {
                    hit = mid;
                } else {
                    miss = mid;
                }
            }
            return hit;
        }
    }
}
