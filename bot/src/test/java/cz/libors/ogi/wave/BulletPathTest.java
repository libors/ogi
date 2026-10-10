package cz.libors.ogi.wave;

import cz.libors.ogi.geom.Angles;
import org.junit.jupiter.api.Test;

import java.awt.geom.Line2D;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulletPathTest {

    /**
     * Bullet shadows against the engine's rule (engine-model G-04): an enemy bullet of the wave, at any bearing,
     * collides with our bullet for sure when both moves of some turn cross (a shadow of chance 1), and only if the
     * engine moves one of them first when one bullet's move crosses the other's move of the turn before (chance ½).
     * Brute force: both bullets' moves turn by turn, crossing tested as {@code BulletPeer} does, with
     * {@code Line2D.linesIntersect}.
     */
    @Test
    void shadowsAreTheBearingsWhoseBulletsCrossOurs() {
        Random random = new Random(7);
        int sure = 0;
        int mixed = 0;
        for (int k = 0; k < 200; k++) {
            Wave wave = new Wave("enemy", 400 + 200 * random.nextDouble(), 100 + 400 * random.nextDouble(),
                    10 + random.nextInt(10), 0.1 + 2.9 * random.nextDouble());
            double heading = Angles.TWO_PI * random.nextDouble();
            double speed = 20 - 3 * (0.1 + 2.9 * random.nextDouble());
            BulletPath b = new BulletPath("b", random.nextInt(25), 50 + 300 * random.nextDouble(),
                    100 + 400 * random.nextDouble(), Math.cos(heading), Math.sin(heading), speed, 70);
            List<double[]> shadows = b.shadows(wave);
            for (int i = 0; i < 2000; i++) {
                double bearing = Angles.TWO_PI * i / 2000;
                boolean collides = false;
                boolean mayCollide = false;
                for (long t = Math.max(b.fireTime(), wave.fireTime()) + 1; t <= b.endTime(); t++) {
                    collides |= crosses(b, t, wave, t, bearing);
                    mayCollide |= t - 1 > wave.fireTime() && crosses(b, t, wave, t - 1, bearing)
                            || t - 1 > b.fireTime() && crosses(b, t - 1, wave, t, bearing);
                }
                boolean[] inside = new boolean[2];
                boolean near = false;
                for (double[] s : shadows) {
                    double from = Math.min(Angles.normalizeRelative(s[0] - bearing),
                            Angles.normalizeRelative(s[1] - bearing));
                    double to = Math.max(Angles.normalizeRelative(s[0] - bearing),
                            Angles.normalizeRelative(s[1] - bearing));
                    inside[s[2] == 1 ? 0 : 1] |= from <= 0 && to >= 0 && to - from < Math.PI;
                    near |= Math.abs(from) < 1e-6 || Math.abs(to) < 1e-6;
                }
                if (!near) {
                    assertEquals(collides, inside[0], "wave " + wave + ", bearing " + bearing);
                    assertEquals(mayCollide, inside[1], "wave " + wave + ", bearing " + bearing + " (mixed order)");
                }
                sure += collides ? 1 : 0;
                mixed += mayCollide ? 1 : 0;
            }
        }
        assertTrue(sure > 100 && mixed > 100, "the random cases cross: " + sure + ", " + mixed);
    }

    /** Whether our bullet's move in turn {@code ours} crosses the move in turn {@code theirs} of the wave's bullet. */
    private static boolean crosses(BulletPath b, long ours, Wave wave, long theirs, double bearing) {
        return Line2D.linesIntersect(b.x(ours - 1), b.y(ours - 1), b.x(ours), b.y(ours),
                wave.x() + wave.radius(theirs - 1) * Math.cos(bearing),
                wave.y() + wave.radius(theirs - 1) * Math.sin(bearing),
                wave.x() + wave.radius(theirs) * Math.cos(bearing),
                wave.y() + wave.radius(theirs) * Math.sin(bearing));
    }
}
