package cz.libors.ogi.shield;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.EngineRobot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShieldStanceTest {

    private static final Field FIELD = new Field(800, 600);

    /** We stand where we are, the body turned across the line to the enemy, the steps aside along it. */
    @Test
    void farFromTheEnemyWeStandAcrossTheLineToIt() {
        ShieldStance stance = new ShieldStance(FIELD, 0.1);
        // Heading north (Robocode 0), the enemy due east: the body is already across the line.
        ShieldStance.Stance s = stance.next(us(400, 300, 0), 700, 300, 11);
        assertEquals(0, s.bodyTurn(), 1e-12);
        assertArrayEquals(ShieldPlanner.STEPS, s.moves(), 1e-12);

        // Heading east, towards the enemy: a quarter turn either way, the shorter one taken.
        s = stance.next(us(400, 300, Math.PI / 2), 700, 300, 11);
        assertEquals(Math.PI / 2, Math.abs(s.bodyTurn()), 1e-12);
    }

    /** After a step aside the stance leads back to where we stood. */
    @Test
    void aStepAsideIsTakenBack() {
        ShieldStance stance = new ShieldStance(FIELD, 0.1);
        stance.next(us(400, 300, 0), 700, 300, 11);
        // The enemy level with us again, so that the body stays north.
        ShieldStance.Stance s = stance.next(us(400, 301, 0), 700, 301, 11);
        assertEquals(0, s.bodyTurn(), 1e-12);
        assertEquals(-1, s.moves()[0], 1e-9);
        // Pushed further than the drift, we stand where we are now.
        s = stance.next(us(400, 310, 0), 700, 310, 11);
        assertEquals(0, s.moves()[0], 1e-12);
    }

    /** Too close for our shot to meet the enemy's bullet, we back away from it, inside the field. */
    @Test
    void tooCloseWeBackAway() {
        ShieldStance stance = new ShieldStance(FIELD, 0.1);
        // 38 + 3.4 · 11 = 75.4 px.
        ShieldStance.Stance s = stance.next(us(400, 300, Math.PI / 2), 470, 300, 11);
        assertEquals(1, s.moves().length);
        // Facing the enemy: backwards, without a turn.
        assertEquals(-100, s.moves()[0], 0);
        assertEquals(0, s.bodyTurn(), 1e-12);

        // Against the east wall the retreat turns along it.
        s = stance.next(us(760, 300, Math.PI / 2), 700, 300, 11);
        double heading = Angles.fromRobocode(Math.PI / 2 + s.bodyTurn()) + (s.moves()[0] < 0 ? Math.PI : 0);
        assertTrue(760 + 60 * Math.cos(heading) < FIELD.width() - 30, "heading " + heading);
    }

    private static EngineRobot us(double x, double y, double robocodeHeading) {
        EngineRobot r = new EngineRobot();
        r.x = x;
        r.y = y;
        r.bodyHeading = robocodeHeading;
        r.energy = 100;
        return r;
    }
}
