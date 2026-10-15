package cz.libors.ogi.radar;

import cz.libors.ogi.radar.MeleeRadar.Seen;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeleeRadarTest {

    /** North of us (bearing π/2), scanned at turn 90; east (bearing 0), scanned at turn 99. */
    private static final List<Seen> TWO = List.of(new Seen("north", 500, 800, 90), new Seen("east", 800, 500, 99));

    @Test
    void spinsUntilEveryEnemyHasBeenSeen() {
        MeleeRadar radar = new MeleeRadar();
        assertEquals(Double.POSITIVE_INFINITY, radar.turn(100, 500, 500, 0, TWO, 3, null, false));
    }

    @Test
    void turnsPastTheStalestEnemy() {
        MeleeRadar radar = new MeleeRadar();
        // The radar points east; the stalest enemy is north: turn counter-clockwise past π/2.
        double turn = radar.turn(100, 500, 500, 0, TWO, 2, null, false);
        assertTrue(turn > Math.PI / 2, "turn " + turn);
        // The margin is the angle it may have moved in 11 turns at 300 px, plus half a robot.
        assertEquals(Math.PI / 2 + Math.atan((8 * 11 + 18) / 300.0), turn, 1e-12);
    }

    @Test
    void theGunsTargetGoesFirstBeforeAShot() {
        MeleeRadar radar = new MeleeRadar();
        // Pointing north, the gun's target east is two turns old and the gun is cool soon: turn clockwise to it.
        double turn = radar.turn(101, 500, 500, Math.PI / 2, TWO, 2, "east", true);
        assertTrue(turn < -Math.PI / 2, "turn " + turn);
        // Without the shot coming, the stalest enemy (north) wins: the radar points at it already and sweeps on past
        // it, the way it turned last.
        double past = radar.turn(101, 500, 500, Math.PI / 2, TWO, 2, "east", false);
        assertEquals(-Math.atan((8 * 12 + 18) / 300.0), past, 1e-12);
    }
}
