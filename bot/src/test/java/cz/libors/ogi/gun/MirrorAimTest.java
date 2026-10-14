package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MirrorAimTest {

    private static final Field FIELD = new Field(800, 600);

    /** Our wandering path: a heading that turns at random, speed 6, sliding along the walls. */
    private static List<double[]> wander(long seed, int turns) {
        Random random = new Random(seed);
        List<double[]> path = new ArrayList<>();
        double x = 200;
        double y = 200;
        double heading = 0.3;
        for (int t = 0; t < turns; t++) {
            heading += (random.nextDouble() - 0.5) * 0.4;
            x = FIELD.clampX(x + 6 * Math.cos(heading));
            y = FIELD.clampY(y + 6 * Math.sin(heading));
            if (x <= 18 || x >= 782 || y <= 18 || y >= 582) {
                heading += Math.PI / 2;
            }
            path.add(new double[] {x, y});
        }
        return path;
    }

    @Test
    void findsThePointMirrorAndItsLag() {
        String name = "mirror-lag-5";
        MirrorAim aim = new MirrorAim(FIELD);
        List<double[]> us = wander(1, 400);
        for (int t = 0; t < us.size(); t++) {
            aim.ours(t, us.get(t)[0], us.get(t)[1]);
            if (t >= 5) {
                double[] then = us.get(t - 5);
                aim.scanned(name, t, 800 - then[0], 600 - then[1]);
            }
        }
        MirrorAim.Fit fit = aim.fit(name);
        assertNotNull(fit);
        assertEquals(5, fit.lag());
        assertEquals(-1, fit.a11(), 1e-6);
        assertEquals(0, fit.a12(), 1e-6);
        assertEquals(0, fit.a21(), 1e-6);
        assertEquals(-1, fit.a22(), 1e-6);
        assertEquals(800, fit.bx(), 1e-3);
        assertEquals(600, fit.by(), 1e-3);
        assertEquals(1, fit.explained(), 1e-9);
    }

    @Test
    void aimsWhereTheMirrorWillBeWhenTheBulletArrives() {
        String name = "mirror-lag-4";
        MirrorAim aim = new MirrorAim(FIELD);
        List<double[]> us = wander(2, 600);
        int now = 450;
        for (int t = 0; t <= now; t++) {
            aim.ours(t, us.get(t)[0], us.get(t)[1]);
            if (t >= 4) {
                double[] then = us.get(t - 4);
                aim.scanned(name, t, 800 - then[0], 600 - then[1]);
            }
        }
        MirrorAim.Fit fit = aim.fit(name);
        assertNotNull(fit);
        double speed = 14;
        long fireTime = now + 1;
        double[][] future = new double[us.size() - (now + 1)][];
        for (int i = 0; i < future.length; i++) {
            future[i] = us.get(now + 1 + i);
        }
        double heading = aim.aim(fit, fireTime, speed, future);
        // A bullet along that heading meets the mirror within a robot's half width at the end of some turn.
        double ox = future[0][0];
        double oy = future[0][1];
        double nearest = Double.POSITIVE_INFINITY;
        for (int n = 1; n < 100; n++) {
            double[] then = us.get(now + 1 + n - 4);
            double ex = 800 - then[0];
            double ey = 600 - then[1];
            double bx = ox + n * speed * Math.cos(heading);
            double by = oy + n * speed * Math.sin(heading);
            nearest = Math.min(nearest, Math.max(Math.abs(bx - ex), Math.abs(by - ey)));
        }
        assertEquals(0, nearest, 18);
        // Aiming at the mirror's position now would miss it: it moves while the bullet flies.
        double[] then = us.get(now - 4);
        double straight = Angles.bearing(ox, oy, 800 - then[0], 600 - then[1]);
        assertEquals(Math.abs(Angles.normalizeRelative(straight - heading)) > 0.01, true);
    }

    @Test
    void aRobotThatMovesOnItsOwnHasNoMap() {
        String name = "independent";
        MirrorAim aim = new MirrorAim(FIELD);
        List<double[]> us = wander(3, 800);
        List<double[]> them = wander(4, 800);
        for (int t = 0; t < us.size(); t++) {
            aim.ours(t, us.get(t)[0], us.get(t)[1]);
            aim.scanned(name, t, them.get(t)[0], them.get(t)[1]);
        }
        assertNull(aim.fit(name));
    }

    @Test
    void tooFewPairsGiveNoMap() {
        String name = "mirror-early";
        MirrorAim aim = new MirrorAim(FIELD);
        List<double[]> us = wander(5, 100);
        for (int t = 0; t < us.size(); t++) {
            aim.ours(t, us.get(t)[0], us.get(t)[1]);
            aim.scanned(name, t, 800 - us.get(t)[0], 600 - us.get(t)[1]);
        }
        assertNull(aim.fit(name));
    }
}
