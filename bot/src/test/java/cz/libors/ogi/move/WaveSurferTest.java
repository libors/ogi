package cz.libors.ogi.move;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.TargetingFeatures;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.trace.Trace;
import cz.libors.ogi.wave.Wave;
import org.junit.jupiter.api.Test;

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
        EnemyWaves waves = new EnemyWaves(FIELD, 0.1);
        WaveSurfer surfer = new WaveSurfer(waves);
        String enemy = "head-on";
        double ex = 650;
        double ey = 300;
        for (long t = 0; t <= 40; t++) {
            // Guns start hot (engine-model G-05); the enemy fires 2.0 in turn 32, the scan of turn 33 shows the drop.
            waves.scanned(enemy, t, ex, ey, 0, 0, t <= 32 ? 100 : 98);
            waves.ourState(t, 250, 300, Math.PI / 2, 0);
            waves.update(t);
        }
        EngineRobot me = new EngineRobot();
        me.x = 250;
        me.y = 300;
        me.bodyHeading = Angles.toRobocode(Math.PI / 2);
        Optional<Move> command = surfer.command(me, 40);
        assertTrue(command.isPresent(), "a wave is in the air");
        assertTrue(command.get().distance() != 0, "standing still in front of a head-on gun");
    }

    /** A dead robot's bullets keep flying and hitting (engine-model G-44), so the surfer still dodges them. */
    @Test
    void keepsTheWavesOfAShooterThatDied() {
        EnemyWaves waves = new EnemyWaves(FIELD, 0.1);
        WaveSurfer surfer = new WaveSurfer(waves);
        for (long t = 0; t <= 40; t++) {
            waves.scanned("died", t, 650, 300, 0, 0, t <= 32 ? 100 : 98);
            waves.ourState(t, 250, 300, Math.PI / 2, 0);
            waves.update(t);
        }
        waves.robotDied("died");
        assertTrue(surfer.command(us(250, 300), 40).isPresent(), "its bullet is still in the air");
    }

    /** A drop of 0.6 while another robot was close is a ram we could not see, not a shot (fire-detection.md). */
    @Test
    void aRamAmongOtherRobotsIsNoShot() {
        for (boolean otherNear : new boolean[] {true, false}) {
            EnemyWaves waves = new EnemyWaves(FIELD, 0.1);
            WaveSurfer surfer = new WaveSurfer(waves);
            for (long t = 0; t <= 40; t++) {
                if (otherNear) {
                    waves.teammateSeen("teammate", t, 700, 330);
                }
                waves.scanned("rams-" + otherNear, t, 650, 300, 0, 0, t <= 32 ? 100 : 99.4);
                waves.ourState(t, 250, 300, Math.PI / 2, 0);
                waves.update(t);
            }
            assertEquals(!otherNear, surfer.command(us(250, 300), 40).isPresent(),
                    otherNear ? "a ram is no wave" : "alone, the same drop is a shot");
        }
    }

    /**
     * An enemy 150 px east of us drives at us at full speed, long enough to be taken for a rammer, and fires. The
     * surfer does not stand in its way and leans away from it at least as steeply as from an enemy that stands there
     * (against which the steeper held ways retreat too).
     */
    @Test
    void movesAwayFromAnEnemyThatDrivesAtUs() {
        double[] away = new double[2];
        for (int driving = 0; driving <= 1; driving++) {
            EnemyWaves waves = new EnemyWaves(FIELD, 0.1);
            WaveSurfer surfer = new WaveSurfer(waves);
            String name = driving == 1 ? "rammer" : "standing";
            long fire = 300;
            for (long t = 0; t <= fire + 8; t++) {
                waves.scanned(name, t, 400, 300, Math.PI, 8 * driving, t <= fire ? 100 : 98);
                waves.ourState(t, 250, 300, Math.PI / 2, 0);
                waves.update(t);
            }
            Move command = surfer.command(us(250, 300), fire + 8).orElseThrow();
            assertTrue(command.distance() != 0, "stood in the way of " + name);
            double heading = Angles.fromRobocode(us(250, 300).bodyHeading + command.rightTurn());
            double drive = command.distance() < 0 ? heading + Math.PI : heading;
            assertTrue(-Math.cos(drive) > 0, name + ": drives towards the enemy: " + drive);
            away[driving] = -Math.cos(drive);
        }
        assertTrue(away[1] >= away[0] - 1e-9, "the rammer's way leads away less: " + away[1] + " vs " + away[0]);
    }

    /**
     * Before its first shot of a round, an enemy that drives at us finds us moving away from it, not orbiting it at the
     * preferred distance, which from 450 px away closes in.
     */
    @Test
    void evadesAnEnemyThatDrivesAtUsBeforeItFires() {
        EnemyWaves waves = new EnemyWaves(FIELD, 0.1);
        WaveSurfer surfer = new WaveSurfer(waves);
        String name = "charger";
        for (long t = 0; t <= 300; t++) {
            waves.scanned(name, t, 400, 300, Math.PI, 8, 100);
            waves.ourState(t, 250, 300, Math.PI / 2, 0);
            waves.update(t);
        }
        for (long t = 301; t <= 305; t++) {
            waves.scanned(name, t, 700, 300, Math.PI, 8, 100);
            waves.ourState(t, 250, 300, Math.PI / 2, 0);
            waves.update(t);
        }
        assertTrue(surfer.command(us(250, 300), 305).isEmpty(), "no wave is in the air");
        Move command = surfer.evade(us(250, 300), name, 305).orElseThrow();
        double heading = Angles.fromRobocode(us(250, 300).bodyHeading + command.rightTurn());
        double drive = command.distance() < 0 ? heading + Math.PI : heading;
        assertTrue(command.distance() == 0 || Math.cos(drive) <= 0, "drives towards it: " + drive);
        assertTrue(surfer.evade(us(250, 300), "someone else", 305).isEmpty(), "an enemy we never saw drives at us");
    }

    /**
     * Guns start hot and cool by 0.1 a turn (engine-model G-05): while no wave is in the air, the wave an enemy can
     * fire next turn is surfed once its gun is cool, and not before.
     */
    @Test
    void surfsTheWaveACoolGunCanFireNext() {
        EnemyWaves waves = new EnemyWaves(FIELD, 0.1);
        WaveSurfer surfer = new WaveSurfer(waves);
        String enemy = "holds-fire";
        for (long t = 0; t <= 35; t++) {
            waves.scanned(enemy, t, 650, 300, 0, 0, 100);
            waves.ourState(t, 250, 300, Math.PI / 2, 0);
            waves.update(t);
            if (t == 20) {
                assertTrue(surfer.gunHeatCommand(us(250, 300), enemy, t).isEmpty(), "its gun is still hot");
            }
        }
        assertTrue(surfer.command(us(250, 300), 35).isEmpty(), "no wave is in the air");
        assertTrue(surfer.gunHeatCommand(us(250, 300), enemy, 35).isPresent(), "its gun is cool");
        assertTrue(surfer.gunHeatCommand(us(250, 300), "someone else", 35).isEmpty(), "an enemy we never saw");
    }

    /**
     * The engine reports a bullet our bullet hit one or two moves short of its wave's radius (engine-model G-04);
     * either way its wave is learned and goes.
     */
    @Test
    void aBulletOursShotDownIsLearnedWhereTheEngineReportsIt() {
        for (int back = 1; back <= 2; back++) {
            EnemyWaves waves = new EnemyWaves(FIELD, 0.1);
            WaveSurfer surfer = new WaveSurfer(waves);
            String enemy = "shot-down-" + back;
            for (long t = 0; t <= 40; t++) {
                waves.scanned(enemy, t, 650, 300, 0, 0, t <= 32 ? 100 : 98);
                waves.ourState(t, 250, 300, Math.PI / 2, 0);
                waves.update(t);
            }
            Trace trace = new Trace();
            waves.describe(40, trace);
            Trace.WaveView wave = trace.waves().get(0);
            double reported = wave.radius() - back * (20 - 3 * 2.0);
            waves.bulletHitBullet(enemy, 40, wave.x() - reported, wave.y(), Math.PI, 2.0);
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
        EnemyWaves waves = new EnemyWaves(FIELD, 0.1);
        WaveSurfer surfer = new WaveSurfer(waves);
        String enemy = "hit-flattener";
        boolean hit = false;
        for (long t = 0; t <= 80; t++) {
            waves.scanned(enemy, t, 650, 300, 0, 0, t <= 32 ? 100 : 98);
            Trace trace = new Trace();
            waves.describe(t, trace);
            // The bullet reaches our box (we stand 400 px west of it) at the end of its move in turn t.
            if (!hit && !trace.waves().isEmpty() && trace.waves().get(0).radius() >= 400 - 18) {
                Trace.WaveView wave = trace.waves().get(0);
                waves.hitByBullet(enemy, t, wave.x() - wave.radius(), wave.y(), Math.PI, 2.0);
                hit = true;
            }
            waves.ourState(t, 250, 300, Math.PI / 2, 0);
            waves.update(t);
        }
        assertTrue(hit, "the bullet reached us");
        double[] visits = waves.model(enemy)
                .predict(new double[TargetingFeatures.COUNT], new double[SimpleAims.COUNT]).visits();
        double uniform = 1.0 / DangerModel.BINS;
        assertTrue(visits[DangerModel.bin(0)] > 2 * uniform, "no visit at guess factor 0: " + visits[DangerModel.bin(0)]);
    }

    @Test
    void aPathChangesItsWayOnceAndGoesOnATurnLater() {
        WaveSurfer.Path back = new WaveSurfer.Path(WaveSurfer.ORBIT_LEFT, 2, WaveSurfer.ORBIT_RIGHT);
        assertEquals(WaveSurfer.ORBIT_LEFT, back.at(1));
        assertEquals(WaveSurfer.ORBIT_RIGHT, back.at(2));
        assertEquals(new WaveSurfer.Path(WaveSurfer.ORBIT_LEFT, 1, WaveSurfer.ORBIT_RIGHT), back.next());
        assertEquals(WaveSurfer.Path.held(WaveSurfer.ORBIT_RIGHT), back.next().next(), "the switch is behind it");
        WaveSurfer.Path stop = WaveSurfer.Path.held(WaveSurfer.STOP);
        assertEquals(stop, stop.next());
        assertEquals(WaveSurfer.STOP, stop.at(1000));
    }

    @Test
    void aShadowAcrossTheOppositeBearingStaysNarrow() {
        double[] across = EnemyWaves.shadowRange(0, 1, 0.5, Math.PI - 0.01, -Math.PI + 0.01);
        assertEquals(0.04, across[1] - across[0], 1e-9, "0.02 rad of shadow at an escape angle of 0.5");
        double[] ahead = EnemyWaves.shadowRange(0, -1, 0.5, 0.1, 0.2);
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

    /**
     * A teammate 300 px west of us fires along the line to us in turn 10; we learn it a turn later. Standing in its
     * line costs the bullet's whole damage, a way out of its line nothing; a bullet fired away from us costs nothing.
     */
    @Test
    void aTeammatesBulletCountsOnlyOnAWayAcrossItsLine() {
        for (double heading : new double[] {0, Math.PI}) {
            EnemyWaves waves = new EnemyWaves(FIELD, 0.1);
            WaveSurfer surfer = new WaveSurfer(waves);
            for (long t = 0; t <= 10; t++) {
                waves.ourState(t, 400, 300, Math.PI / 2, 0);
                waves.update(t);
            }
            waves.teammateFired("teammate", 10, 100, 300, heading, 2, 11);
            waves.ourState(11, 400, 300, Math.PI / 2, 0);
            waves.update(11);
            double stay = waves.meleeDanger(us(400, 300), 11, 400, 300);
            double away = waves.meleeDanger(us(400, 300), 11, 400, 500);
            assertEquals(heading == 0 ? BulletMotion.damage(2) : 0, stay, 1e-12, "standing, heading " + heading);
            assertEquals(0, away, 1e-12, "out of its line, heading " + heading);
        }
    }

    /**
     * In a melee another enemy nearer to the shooter quarters the chance that its wave was aimed at us. In a team
     * battle an enemy aims at us or at our teammates, never at its own: a teammate nearer to the shooter quarters the
     * chance, the shooter's teammate does not. A team hands the scan that reveals the shot on a turn late, and the
     * wave is followed from the shot all the same.
     */
    @Test
    void inATeamBattleOnlyTeammatesNearerToTheShooterTakeItsAim() {
        double[] dangers = new double[4];
        for (int c = 0; c < 4; c++) {
            boolean team = c >= 2;
            boolean teammateNearer = c % 2 == 1;
            EnemyWaves waves = new EnemyWaves(FIELD, 0.1);
            WaveSurfer surfer = new WaveSurfer(waves);
            if (team) {
                waves.teamBattle();
            }
            for (long t = 0; t <= 34; t++) {
                waves.teammateSeen("teammate", t, teammateNearer ? 560 : 100, 500);
                waves.seen("friend-of-shooter", t, 640, 420, 0, 0, true);
                waves.seen("shooter", t, 650, 300, 0, 0, true);
                waves.ourState(t, 250, 300, Math.PI / 2, 0);
                // Guns start hot (engine-model G-05); it fires 2.0 in turn 32, the scan of turn 33 shows the drop,
                // and the team hands that scan on in turn 34.
                if (t >= 1) {
                    waves.energySeen("shooter", t - 1, 650, 300, 0, t - 1 <= 32 ? 100 : 98, t);
                }
                waves.update(t);
            }
            dangers[c] = waves.meleeDanger(us(250, 300), 34, 250, 300);
        }
        assertTrue(dangers[0] > 0, "the wave counts");
        assertEquals(dangers[0], dangers[1], 1e-12, "in a melee the other enemy nearer to the shooter takes its aim");
        assertEquals(4 * dangers[0], dangers[2], 1e-12, "in a team battle the shooter's own teammate does not");
        assertEquals(dangers[2] / 4, dangers[3], 1e-12, "a teammate nearer to the shooter quarters the chance");
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
        EnemyWaves waves = new EnemyWaves(FIELD, 0.1);
        WaveSurfer surfer = new WaveSurfer(waves);
        Wave wave = new Wave("e", 650, 300, 0, 2);
        EngineRobot me = new EngineRobot();
        me.x = 650 - WaveSurfer.PREFERRED_DISTANCE;
        me.y = 300;
        me.bodyHeading = Angles.toRobocode(Math.PI / 2);
        // At the preferred distance, orbiting left (counter-clockwise around the origin) is heading south.
        Move left = surfer.orbit(me, wave.x(), wave.y(), WaveSurfer.ORBIT_LEFT);
        assertEquals(-100, left.distance(), 1e-12, "drives backwards instead of turning around");
        assertEquals(0, left.rightTurn(), 1e-9);
        Move stop = surfer.orbit(me, wave.x(), wave.y(), WaveSurfer.STOP);
        assertEquals(0, stop.distance());
        me.y = 560;
        Move nearTop = surfer.orbit(me, wave.x(), wave.y(), WaveSurfer.ORBIT_RIGHT);
        double heading = Angles.fromRobocode(me.bodyHeading + nearTop.rightTurn());
        double driveHeading = nearTop.distance() < 0 ? heading + Math.PI : heading;
        assertTrue(Math.sin(driveHeading) < 0.5, "turned away from the top wall: " + driveHeading);
    }

    @Test
    void beyondThePreferredDistanceTheWallSmoothingTurnsTowardsTheOrigin() {
        EnemyWaves waves = new EnemyWaves(FIELD, 0.1);
        WaveSurfer surfer = new WaveSurfer(waves);
        Wave wave = new Wave("e", 650, 300, 0, 2);
        // 520 px from the origin, under the top wall: orbiting right (clockwise) heads for the wall.
        EngineRobot me = us(200, 560);
        Move right = surfer.orbit(me, wave.x(), wave.y(), WaveSurfer.ORBIT_RIGHT);
        double heading = Angles.fromRobocode(me.bodyHeading + right.rightTurn());
        double driveHeading = right.distance() < 0 ? heading + Math.PI : heading;
        double towards = Math.cos(driveHeading - Angles.bearing(me.x, me.y, wave.x(), wave.y()));
        assertTrue(towards > 0.5, "turned towards the origin, not away from it: " + driveHeading);
        // Nearer than the preferred distance, it turns away from the origin.
        me = us(480, 560);
        right = surfer.orbit(me, wave.x(), wave.y(), WaveSurfer.ORBIT_RIGHT);
        heading = Angles.fromRobocode(me.bodyHeading + right.rightTurn());
        driveHeading = right.distance() < 0 ? heading + Math.PI : heading;
        towards = Math.cos(driveHeading - Angles.bearing(me.x, me.y, wave.x(), wave.y()));
        assertTrue(towards < 0, "turned away from the origin: " + driveHeading);
    }

    /** The wall smoothing steps over headings that cannot clear the walls; it must land on the same number. */
    @Test
    void wallSmoothingReturnsTheHeadingOfATestAtEveryStep() {
        Random random = new Random(9);
        for (int i = 0; i < 200_000; i++) {
            // Most positions near the walls and in the corners, where the smoothing turns far.
            double x = random.nextBoolean() ? 18 + 150 * random.nextDouble() : 18 + 764 * random.nextDouble();
            double y = random.nextBoolean() ? 582 - 150 * random.nextDouble() : 18 + 564 * random.nextDouble();
            double heading = 4 * Math.PI * random.nextDouble() - Math.PI;
            int direction = random.nextBoolean() ? 1 : -1;
            assertEquals(Double.doubleToLongBits(everyStep(x, y, heading, direction)),
                    Double.doubleToLongBits(WaveSurfer.smoothed(FIELD, x, y, heading, direction)),
                    "at " + x + ", " + y + " heading " + heading + " direction " + direction);
        }
    }

    /** The smoothing before it stepped over headings. */
    private static double everyStep(double x, double y, double heading, int direction) {
        for (int i = 0; i < Angles.TWO_PI / 0.05; i++) {
            if (FIELD.contains(x + 120 * Math.cos(heading), y + 120 * Math.sin(heading), 25)) {
                break;
            }
            heading -= direction * 0.05;
        }
        return heading;
    }
}
