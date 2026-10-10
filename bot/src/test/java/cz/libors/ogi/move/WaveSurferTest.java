package cz.libors.ogi.move;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.TargetingFeatures;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.wave.Wave;
import org.junit.jupiter.api.Test;

import java.awt.geom.Line2D;
import java.util.List;
import java.util.Optional;
import java.util.Random;

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
            WaveSurfer.OurBullet b = new WaveSurfer.OurBullet("b", random.nextInt(25), 50 + 300 * random.nextDouble(),
                    100 + 400 * random.nextDouble(), Math.cos(heading), Math.sin(heading), speed, 70);
            List<double[]> shadows = WaveSurfer.shadowAngles(wave, b);
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
    private static boolean crosses(WaveSurfer.OurBullet b, long ours, Wave wave, long theirs, double bearing) {
        return Line2D.linesIntersect(b.x(ours - 1), b.y(ours - 1), b.x(ours), b.y(ours),
                wave.x() + wave.radius(theirs - 1) * Math.cos(bearing),
                wave.y() + wave.radius(theirs - 1) * Math.sin(bearing),
                wave.x() + wave.radius(theirs) * Math.cos(bearing),
                wave.y() + wave.radius(theirs) * Math.sin(bearing));
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
