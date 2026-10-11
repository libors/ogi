package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Field;
import cz.libors.ogi.world.EnemyState;
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
    void aSizeLimitKeepsTheLatestShots() {
        GunData data = new GunData(GunParameters.ANTI_SURFER, 5);
        double[] f = new double[TargetingFeatures.COUNT];
        for (int i = 0; i < 20; i++) {
            double centre = i < 15 ? -0.5 : 0.5;
            data.add(f, centre - 0.1, centre + 0.1, 1);
        }
        assertEquals(5, data.size());
        assertEquals(0.5, data.bestGuessFactor(f), 1e-9);
    }

    @Test
    void peaksAreTheLocalMaximaNearlyAsHighAsTheBest() {
        // Weight 3 around 0.6, 2 around -0.3, 0.5 around -0.9.
        double[] at = {0.5, 0.7, -0.4, -0.2, -1.0, -0.8};
        double[] w = {3, -3, 2, -2, 0.5, -0.5};
        double[][] peaks = GunData.peaks(at.clone(), w.clone(), 6, 0.5, 5, 0.1);
        assertEquals(2, peaks.length, Arrays.deepToString(peaks));
        assertEquals(0.6, peaks[0][0], 1e-9);
        assertEquals(1, peaks[0][1], 1e-9);
        assertEquals(-0.3, peaks[1][0], 1e-9);
        assertEquals(2.0 / 3, peaks[1][1], 1e-9);
        assertEquals(1, GunData.peaks(at.clone(), w.clone(), 6, 0.7, 5, 0.1).length);
        assertEquals(1, GunData.peaks(at.clone(), w.clone(), 6, 0.5, 1, 0.1).length);
    }

    @Test
    void theAntiSurferGunFollowsTheLatestBullets() {
        // A surfer that went to guess factor -0.5 for 300 bullets and then to +0.5 for 100.
        GunModel model = new GunModel(GunParameters.DEFAULT);
        double escape = 0.6;
        Random random = new Random(1);
        for (int i = 0; i < 400; i++) {
            double gf = i < 300 ? -0.5 : 0.5;
            GunModel.Shot shot = shot(i, random, escape);
            GunModel.Aim aim = model.aim(shot);
            model.learn(shot, aim,
                    new GunModel.Pass("t", i, true, 0, escape, true, (gf - 0.05) * escape, (gf + 0.05) * escape));
        }
        GunModel.Aim aim = model.aim(shot(400, random, escape));
        assertEquals(-0.5 * escape, aim.aims()[GunModel.GUESS_FACTOR], 0.05 * escape);
        assertEquals(0.5 * escape, aim.aims()[GunModel.ANTI_SURFER], 0.05 * escape);
        // The main gun hit most of the 400 bullets: the target is not hard to hit, so the main gun aims.
        assertEquals(GunModel.GUESS_FACTOR, aim.gun());
    }

    private static GunModel.Shot shot(long time, Random random, double escape) {
        double[] f = new double[TargetingFeatures.COUNT];
        for (int j = 0; j < f.length; j++) {
            f[j] = random.nextDouble();
        }
        return new GunModel.Shot("t", time, f, 1, 0, escape, true, Double.NaN);
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
        RobotHistory h = new RobotHistory();
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
        RobotHistory h = new RobotHistory();
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

    /** The surfer asks how we moved when the enemy aimed, a few turns ago: the history as it was then. */
    @Test
    void historyUntilATurnIsTheHistoryAsItWasThen() {
        RobotHistory h = new RobotHistory();
        RobotHistory then = null;
        for (long t = 0; t < 60; t++) {
            double v = t % 17 < 9 ? 8 : -6;
            h.add(t, 100 + t, 200, 0.01 * t, v);
            if (t == 45) {
                then = h.until(t + 100);
            }
        }
        RobotHistory copy = h.until(45);
        assertEquals(45, copy.time());
        assertEquals(then.turnsSinceReversal(), copy.turnsSinceReversal());
        assertEquals(then.turnsSinceDeceleration(), copy.turnsSinceDeceleration());
        assertEquals(then.acceleration(), copy.acceleration(), 1e-12);
        assertEquals(then.turnRate(), copy.turnRate(), 1e-12);
        assertEquals(then.displacement(10), copy.displacement(10), 1e-12);
        assertTrue(h.until(10).isEmpty(), "older than the stored states");
    }

    /** A bullet an enemy bullet shot down never reached its target: its wave is learned as a virtual one. */
    @Test
    void aBulletShotDownIsLearnedAsVirtual() {
        for (boolean shotDown : new boolean[] {false, true}) {
            String name = "standing-" + shotDown;
            GuessFactorGun gun = new GuessFactorGun(FIELD);
            Boolean real = null;
            for (long t = 0; t <= 100 && real == null; t++) {
                gun.scanned(name, t, 500, 300, 0, 0);
                // The plan of turn 10 is fired in turn 11.
                gun.update(t, 200, 300, t == 11 ? name : null);
                if (shotDown && t == 12) {
                    gun.shotDown(name, 10);
                }
                for (GunModel.Pass p : gun.passed()) {
                    if (p.time() == 10) {
                        real = p.real();
                    }
                }
                EnemyState state = new EnemyState(name, 500, 300, 0, 0, 0, 100, t);
                gun.aim(name, t, 200, 300, 2, false, CircularAim.aim(200, 300, state, 14, t + 1, FIELD));
            }
            assertEquals(!shotDown, real, shotDown ? "shot down" : "reached the target");
        }
    }
}

