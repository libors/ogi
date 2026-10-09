package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Field;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GunTest {

    private static final Field FIELD = new Field(800, 600);

    @Test
    void bestGuessFactorIsWhereMostNeighboursWouldHaveHit() {
        GunData data = new GunData(GunParameters.DEFAULT);
        assertTrue(Double.isNaN(data.bestGuessFactor(new double[TargetingFeatures.COUNT])));
        Random random = new Random(1);
        double[] f = new double[TargetingFeatures.COUNT];
        for (int i = 0; i < 400; i++) {
            for (int j = 0; j < f.length; j++) {
                f[j] = 0.5 + 0.01 * random.nextGaussian();
            }
            // Most shots would have hit around 0.6; a minority around -0.8.
            double centre = i % 4 == 0 ? -0.8 : 0.6;
            data.add(f, centre - 0.1, centre + 0.1, 1);
        }
        double[] query = new double[TargetingFeatures.COUNT];
        Arrays.fill(query, 0.5);
        assertEquals(0.6, data.bestGuessFactor(query), 0.1);
        assertEquals(20, data.neighbours());
    }

    @Test
    void nearerNeighboursDecide() {
        GunData data = new GunData(GunParameters.DEFAULT);
        double[] near = new double[TargetingFeatures.COUNT];
        double[] far = new double[TargetingFeatures.COUNT];
        Arrays.fill(far, 1);
        for (int i = 0; i < 50; i++) {
            data.add(i % 2 == 0 ? near : far, i % 2 == 0 ? 0.2 : -0.5, i % 2 == 0 ? 0.3 : -0.4, 1);
        }
        assertEquals(0.25, data.bestGuessFactor(near), 1e-9);
        assertEquals(-0.45, data.bestGuessFactor(far), 1e-9);
    }

    @Test
    void wallRoomShrinksTowardsAWall() {
        // Target 200 px east of the origin, orbiting counter-clockwise (north) towards the top wall.
        double escape = Math.asin(8 / 14.0);
        double open = TargetingFeatures.wallRoom(400, 300, 200, 0, 1, escape, FIELD);
        double nearTop = TargetingFeatures.wallRoom(400, 500, 200, 0, 1, escape, FIELD);
        double awayFromTop = TargetingFeatures.wallRoom(400, 500, 200, 0, -1, escape, FIELD);
        assertEquals(1.5, open, 1e-9);
        // The wall stops the orbit where 500 + 200·sin(a) = 600 − 18, one 1/40 escape-angle step short at most.
        double limit = Math.asin(82.0 / 200) / escape;
        assertTrue(nearTop <= limit && nearTop > limit - 1.5 / 60, "near the top wall: " + nearTop);
        assertEquals(1.5, awayFromTop, 1e-9);
    }

    @Test
    void directionFollowsTheLateralVelocity() {
        EnemyHistory h = new EnemyHistory();
        // East of the origin, heading north: counter-clockwise around the origin.
        h.add(1, 600, 300, Math.PI / 2, 8);
        double[] out = new double[TargetingFeatures.COUNT];
        assertEquals(1, TargetingFeatures.compute(400, 300, h, 14, FIELD, -1, out));
        assertEquals(1, out[1], 1e-12);
        h.add(2, 600, 292, Math.PI / 2, -8);
        assertEquals(-1, TargetingFeatures.compute(400, 300, h, 14, FIELD, 1, out));
        h.add(3, 600, 292, Math.PI / 2, 0);
        assertEquals(-1, TargetingFeatures.compute(400, 300, h, 14, FIELD, -1, out), "no lateral velocity");
    }

    @Test
    void historyTracksReversalsDecelerationAndTurning() {
        EnemyHistory h = new EnemyHistory();
        h.add(10, 100, 100, 0, 8);
        h.add(11, 108, 100, 0.1, 8);
        h.add(12, 114, 100, 0.2, 6);
        assertEquals(0, h.turnsSinceDeceleration());
        assertEquals(-2, h.acceleration(), 1e-12);
        assertEquals(0.1, h.turnRate(), 1e-12);
        h.add(13, 112, 100, 0.2, -2);
        assertEquals(0, h.turnsSinceReversal());
        h.add(14, 111, 100, 0.2, -1);
        assertEquals(1, h.turnsSinceReversal());
        assertEquals(11, h.displacement(10), 1e-12);
    }
}
