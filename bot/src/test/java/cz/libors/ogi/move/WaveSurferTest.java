package cz.libors.ogi.move;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.TargetingFeatures;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.trace.Trace;
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
        String enemy = "head-on";
        double ex = 650;
        double ey = 300;
        for (long t = 0; t <= 40; t++) {
            // Guns start hot (engine-model G-05); the enemy fires 2.0 in turn 32, the scan of turn 33 shows the drop.
            surfer.scanned(enemy, t, ex, ey, 0, 0, t <= 32 ? 100 : 98);
            surfer.ourState(t, 250, 300, Math.PI / 2, 0);
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
            surfer.scanned("died", t, 650, 300, 0, 0, t <= 32 ? 100 : 98);
            surfer.ourState(t, 250, 300, Math.PI / 2, 0);
            surfer.update(t);
        }
        surfer.robotDied("died");
        assertTrue(surfer.command(us(250, 300), 40).isPresent(), "its bullet is still in the air");
    }

    /** A drop of 0.6 while another robot was close is a ram we could not see, not a shot (fire-detection.md). */
    @Test
    void aRamAmongOtherRobotsIsNoShot() {
        for (boolean otherNear : new boolean[] {true, false}) {
            WaveSurfer surfer = new WaveSurfer(FIELD, 0.1);
            for (long t = 0; t <= 40; t++) {
                if (otherNear) {
                    surfer.teammateSeen("teammate", t, 700, 330);
                }
                surfer.scanned("rams-" + otherNear, t, 650, 300, 0, 0, t <= 32 ? 100 : 99.4);
                surfer.ourState(t, 250, 300, Math.PI / 2, 0);
                surfer.update(t);
            }
            assertEquals(!otherNear, surfer.command(us(250, 300), 40).isPresent(),
                    otherNear ? "a ram is no wave" : "alone, the same drop is a shot");
        }
    }

    /**
     * An enemy 150 px east of us drives at us at full speed, long enough to be taken for a rammer, and fires. The
     * surfer does not stand in its way and leans away from it more steeply than from an enemy that stands there.
     */
    @Test
    void movesAwayFromAnEnemyThatDrivesAtUs() {
        double[] away = new double[2];
        for (int driving = 0; driving <= 1; driving++) {
            WaveSurfer surfer = new WaveSurfer(FIELD, 0.1);
            String name = driving == 1 ? "rammer" : "standing";
            long fire = 300;
            for (long t = 0; t <= fire + 8; t++) {
                surfer.scanned(name, t, 400, 300, Math.PI, 8 * driving, t <= fire ? 100 : 98);
                surfer.ourState(t, 250, 300, Math.PI / 2, 0);
                surfer.update(t);
            }
            double[] command = surfer.command(us(250, 300), fire + 8).orElseThrow();
            assertTrue(command[0] != 0, "stood in the way of " + name);
            double heading = Angles.fromRobocode(us(250, 300).bodyHeading + command[1]);
            double drive = command[0] < 0 ? heading + Math.PI : heading;
            assertTrue(-Math.cos(drive) > 0, name + ": drives towards the enemy: " + drive);
            away[driving] = -Math.cos(drive);
        }
        assertTrue(away[1] > away[0], "the rammer's way leads away more: " + away[1] + " vs " + away[0]);
    }

    /**
     * Before its first shot of a round, an enemy that drives at us finds us moving away from it, not orbiting it at the
     * preferred distance, which from 450 px away closes in.
     */
    @Test
    void evadesAnEnemyThatDrivesAtUsBeforeItFires() {
        WaveSurfer surfer = new WaveSurfer(FIELD, 0.1);
        String name = "charger";
        for (long t = 0; t <= 300; t++) {
            surfer.scanned(name, t, 400, 300, Math.PI, 8, 100);
            surfer.ourState(t, 250, 300, Math.PI / 2, 0);
            surfer.update(t);
        }
        for (long t = 301; t <= 305; t++) {
            surfer.scanned(name, t, 700, 300, Math.PI, 8, 100);
            surfer.ourState(t, 250, 300, Math.PI / 2, 0);
            surfer.update(t);
        }
        assertTrue(surfer.command(us(250, 300), 305).isEmpty(), "no wave is in the air");
        double[] command = surfer.evade(us(250, 300), name, 305).orElseThrow();
        double heading = Angles.fromRobocode(us(250, 300).bodyHeading + command[1]);
        double drive = command[0] < 0 ? heading + Math.PI : heading;
        assertTrue(command[0] == 0 || Math.cos(drive) <= 0, "drives towards it: " + drive);
        assertTrue(surfer.evade(us(250, 300), "someone else", 305).isEmpty(), "an enemy we never saw drives at us");
    }

    /**
     * The engine reports a bullet our bullet hit one or two moves short of its wave's radius (engine-model G-04);
     * either way its wave is learned and goes.
     */
    @Test
    void aBulletOursShotDownIsLearnedWhereTheEngineReportsIt() {
        for (int back = 1; back <= 2; back++) {
            WaveSurfer surfer = new WaveSurfer(FIELD, 0.1);
            String enemy = "shot-down-" + back;
            for (long t = 0; t <= 40; t++) {
                surfer.scanned(enemy, t, 650, 300, 0, 0, t <= 32 ? 100 : 98);
                surfer.ourState(t, 250, 300, Math.PI / 2, 0);
                surfer.update(t);
            }
            Trace trace = new Trace();
            surfer.describe(40, trace);
            Trace.WaveView wave = trace.waves().get(0);
            double reported = wave.radius() - back * (20 - 3 * 2.0);
            surfer.bulletHitBullet(enemy, 40, wave.x() - reported, wave.y(), Math.PI, 2.0);
            assertTrue(surfer.command(us(250, 300), 40).isEmpty(),
                    "the wave of the bullet shot down " + back + " moves back is still there");
        }
    }

    /**
     * A gun that learns our movement learns the waves whose bullets hit us too, so the flattener still learns where we
     * were when such a wave passed us.
     */
    @Test
    void aWaveWhoseBulletHitUsStillTeachesTheFlattener() {
        WaveSurfer surfer = new WaveSurfer(FIELD, 0.1);
        String enemy = "hit-flattener";
        boolean hit = false;
        for (long t = 0; t <= 80; t++) {
            surfer.scanned(enemy, t, 650, 300, 0, 0, t <= 32 ? 100 : 98);
            Trace trace = new Trace();
            surfer.describe(t, trace);
            // The bullet reaches our box (we stand 400 px west of it) at the end of its move in turn t.
            if (!hit && !trace.waves().isEmpty() && trace.waves().get(0).radius() >= 400 - 18) {
                Trace.WaveView wave = trace.waves().get(0);
                surfer.hitByBullet(enemy, t, wave.x() - wave.radius(), wave.y(), Math.PI, 2.0);
                hit = true;
            }
            surfer.ourState(t, 250, 300, Math.PI / 2, 0);
            surfer.update(t);
        }
        assertTrue(hit, "the bullet reached us");
        double[] visits = WaveSurfer.model(enemy)
                .predict(new double[TargetingFeatures.COUNT], new double[SimpleAims.COUNT]).visits();
        double uniform = 1.0 / DangerModel.BINS;
        assertTrue(visits[DangerModel.bin(0)] > 2 * uniform, "no visit at guess factor 0: " + visits[DangerModel.bin(0)]);
    }

    @Test
    void aShadowAcrossTheOppositeBearingStaysNarrow() {
        double[] across = WaveSurfer.shadowRange(0, 1, 0.5, Math.PI - 0.01, -Math.PI + 0.01);
        assertEquals(0.04, across[1] - across[0], 1e-9, "0.02 rad of shadow at an escape angle of 0.5");
        double[] ahead = WaveSurfer.shadowRange(0, -1, 0.5, 0.1, 0.2);
        assertEquals(-0.4, ahead[0], 1e-12);
        assertEquals(-0.2, ahead[1], 1e-12);
    }

    @Test
    void ramDetectorTakesOnlyRobotsThatCloseInForRammers() {
        RamDetector charger = new RamDetector();
        RamDetector far = new RamDetector();
        RamDetector visitor = new RamDetector();
        for (int i = 0; i < 300; i++) {
            charger.observe(120, 6);
            far.observe(300, 8);
            // Comes close at full speed one scan in five, otherwise keeps its distance.
            visitor.observe(i % 5 == 0 ? 150 : 350, i % 5 == 0 ? 8 : 0);
        }
        assertTrue(charger.drivesAtUs());
        assertTrue(!far.drivesAtUs(), "approaching from far away is no ram");
        assertTrue(!visitor.drivesAtUs(), "a short visit is no ram");
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
        double[] left = surfer.orbit(me, wave.x(), wave.y(), WaveSurfer.ORBIT_LEFT);
        assertEquals(-100, left[0], 1e-12, "drives backwards instead of turning around");
        assertEquals(0, left[1], 1e-9);
        double[] stop = surfer.orbit(me, wave.x(), wave.y(), WaveSurfer.STOP);
        assertEquals(0, stop[0]);
        me.y = 560;
        double[] nearTop = surfer.orbit(me, wave.x(), wave.y(), WaveSurfer.ORBIT_RIGHT);
        double heading = Angles.fromRobocode(me.bodyHeading + nearTop[1]);
        double driveHeading = nearTop[0] < 0 ? heading + Math.PI : heading;
        assertTrue(Math.sin(driveHeading) < 0.5, "turned away from the top wall: " + driveHeading);
    }
}
