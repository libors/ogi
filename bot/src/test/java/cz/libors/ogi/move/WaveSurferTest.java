package cz.libors.ogi.move;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.TargetingFeatures;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.wave.Wave;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaveSurferTest {

    private static final Field FIELD = new Field(800, 600);

    @Test
    void dangerIsTheShareOfAimBetweenTwoGuessFactors() {
        DangerModel model = new DangerModel(DangerParameters.HISTOGRAM);
        double[] situation = new double[TargetingFeatures.COUNT];
        double[] prior = model.profile(situation);
        assertEquals(1, DangerModel.mass(prior, -1, 1), 1e-12);
        double head = DangerModel.mass(prior, -0.05, 0.05);
        double side = DangerModel.mass(prior, 0.6, 0.7);
        assertTrue(head > 5 * side, "the prior is a gun aiming straight at us: " + head + " vs " + side);
        for (int i = 0; i < 20; i++) {
            model.learn(situation, 0.65);
        }
        double[] learned = model.profile(situation);
        assertTrue(DangerModel.mass(learned, 0.6, 0.7) > DangerModel.mass(learned, -0.05, 0.05));
        assertEquals(DangerModel.BINS / 2, DangerModel.bin(0));
        assertEquals(DangerModel.BINS - 1, DangerModel.bin(5));
    }

    /**
     * A linear gun aims where our lateral velocity takes us: far ahead while we drive, straight at us while we stand.
     * One histogram of all its bullets fears both; the nearest bullets tell the two situations apart.
     */
    @Test
    void theNearestBulletsSeparateSituations() {
        double[] weights = DangerParameters.HISTOGRAM.weights();
        DangerParameters knn = new DangerParameters(weights, 1, 50, 1, 1, 0, 1, 0, 1, 1, 1, 0, 0, 1000);
        double[] driving = new double[TargetingFeatures.COUNT];
        double[] standing = new double[TargetingFeatures.COUNT];
        driving[1] = 1;
        DangerModel histogram = new DangerModel(DangerParameters.HISTOGRAM);
        DangerModel nearest = new DangerModel(knn);
        for (int i = 0; i < 30; i++) {
            for (DangerModel m : new DangerModel[] {histogram, nearest}) {
                m.learn(driving, 0.9);
                m.learn(standing, 0);
            }
        }
        double[] h = histogram.profile(driving);
        double[] n = nearest.profile(driving);
        assertTrue(DangerModel.mass(h, -0.05, 0.05) > 0.2, "the histogram fears standing still too");
        assertTrue(DangerModel.mass(n, 0.85, 0.95) > 5 * DangerModel.mass(n, -0.05, 0.05),
                "driving: the nearest bullets went ahead");
        double[] still = nearest.profile(standing);
        assertTrue(DangerModel.mass(still, -0.05, 0.05) > 5 * DangerModel.mass(still, 0.85, 0.95),
                "standing: the nearest bullets came straight at us");
    }

    /**
     * An enemy east of us fires a shot we detect from its energy. Against the prior of a head-on gun, standing still
     * is the most dangerous choice, so the surfer moves.
     */
    @Test
    void movesAwayFromAHeadOnShot() {
        WaveSurfer surfer = new WaveSurfer(FIELD, 0.1);
        String enemy = "enemy";
        double ex = 650;
        double ey = 300;
        for (long t = 0; t <= 40; t++) {
            surfer.ourState(t, 250, 300, Math.PI / 2, 0);
            // Guns start hot (engine-model G-05); the enemy fires 2.0 in turn 32, the scan of turn 33 shows the drop.
            surfer.scanned(enemy, t, ex, ey, 0, t <= 32 ? 100 : 98);
            surfer.update(t);
        }
        EngineRobot me = new EngineRobot();
        me.x = 250;
        me.y = 300;
        me.bodyHeading = Angles.toRobocode(Math.PI / 2);
        Optional<double[]> command = surfer.command(me, 40);
        assertTrue(command.isPresent(), "a wave is in the air");
        assertTrue(command.get()[0] != 0, "standing still in front of a head-on gun");
    }

    /** A dead robot's bullets keep flying and hitting (engine-model G-44), so the surfer still dodges them. */
    @Test
    void keepsTheWavesOfAShooterThatDied() {
        WaveSurfer surfer = new WaveSurfer(FIELD, 0.1);
        for (long t = 0; t <= 40; t++) {
            surfer.ourState(t, 250, 300, Math.PI / 2, 0);
            surfer.scanned("enemy", t, 650, 300, 0, t <= 32 ? 100 : 98);
            surfer.update(t);
        }
        surfer.robotDied("enemy");
        assertTrue(surfer.command(us(250, 300), 40).isPresent(), "its bullet is still in the air");
    }

    /** A drop of 0.6 while another robot was close is a ram we could not see, not a shot (fire-detection.md). */
    @Test
    void aRamAmongOtherRobotsIsNoShot() {
        for (boolean otherNear : new boolean[] {true, false}) {
            WaveSurfer surfer = new WaveSurfer(FIELD, 0.1);
            for (long t = 0; t <= 40; t++) {
                surfer.ourState(t, 250, 300, Math.PI / 2, 0);
                if (otherNear) {
                    surfer.teammateSeen("teammate", t, 700, 330);
                }
                surfer.scanned("enemy", t, 650, 300, 0, t <= 32 ? 100 : 99.4);
                surfer.update(t);
            }
            assertEquals(!otherNear, surfer.command(us(250, 300), 40).isPresent(),
                    otherNear ? "a ram is no wave" : "alone, the same drop is a shot");
        }
    }

    private static EngineRobot us(double x, double y) {
        EngineRobot me = new EngineRobot();
        me.x = x;
        me.y = y;
        me.bodyHeading = Angles.toRobocode(Math.PI / 2);
        return me;
    }

    @Test
    void orbitIsPerpendicularToTheWaveAndSlidesAlongWalls() {
        WaveSurfer surfer = new WaveSurfer(FIELD, 0.1);
        Wave wave = new Wave("e", 650, 300, 0, 2);
        EngineRobot me = new EngineRobot();
        me.x = 650 - WaveSurfer.PREFERRED_DISTANCE;
        me.y = 300;
        me.bodyHeading = Angles.toRobocode(Math.PI / 2);
        // At the preferred distance, orbiting left (counter-clockwise around the origin) is heading south.
        double[] left = surfer.orbit(me, wave, WaveSurfer.ORBIT_LEFT);
        assertEquals(-100, left[0], 1e-12, "drives backwards instead of turning around");
        assertEquals(0, left[1], 1e-9);
        double[] stop = surfer.orbit(me, wave, WaveSurfer.STOP);
        assertEquals(0, stop[0]);
        me.y = 560;
        double[] nearTop = surfer.orbit(me, wave, WaveSurfer.ORBIT_RIGHT);
        double heading = Angles.fromRobocode(me.bodyHeading - nearTop[1]);
        double driveHeading = nearTop[0] < 0 ? heading + Math.PI : heading;
        assertTrue(Math.sin(driveHeading) < 0.5, "turned away from the top wall: " + driveHeading);
    }
}
