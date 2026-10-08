package cz.libors.ogi.move;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
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
        DangerStats stats = new DangerStats();
        assertEquals(1, stats.danger(-1, 1), 1e-12);
        double head = stats.danger(-0.05, 0.05);
        double side = stats.danger(0.6, 0.7);
        assertTrue(head > 5 * side, "the prior is a gun aiming straight at us: " + head + " vs " + side);
        for (int i = 0; i < 20; i++) {
            stats.hit(0.65);
        }
        assertTrue(stats.danger(0.6, 0.7) > stats.danger(-0.05, 0.05));
        assertEquals(DangerStats.BINS / 2, DangerStats.bin(0));
        assertEquals(DangerStats.BINS - 1, DangerStats.bin(5));
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
