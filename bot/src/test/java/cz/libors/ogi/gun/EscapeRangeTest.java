package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.BulletMotion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EscapeRangeTest {

    private static final Field FIELD = new Field(800, 600);
    private static final double SPEED = BulletMotion.speed(1.9);
    private static final double ESCAPE = Math.asin(8 / SPEED);

    /** The range of a target 400 px east of an origin at (200, 300), the wave fired in the turn after the scan. */
    private static double[] range(double x, double y, double heading, double velocity, int direction) {
        double reference = Angles.bearing(200, 300, x, y);
        return EscapeRange.guessFactors(200, 300, 11, 10, x, y, heading, velocity, SPEED, reference, direction,
                ESCAPE, FIELD);
    }

    @Test
    void aStillTargetInTheOpenEscapesEquallyBothWaysAndWithinTheMaximumAngle() {
        // Broadside: heading north, the orbit's tangent.
        double[] r = range(600, 300, Math.PI / 2, 0, 1);
        assertEquals(-r[0], r[1], 0.02);
        double half = Math.atan(EscapeRange.HALF_WIDTH / 400) / ESCAPE;
        assertTrue(r[1] < 1 + half, "high " + r[1]);
        // From standing it reaches most of the angle a full-speed run would.
        assertTrue(r[1] > 0.6, "high " + r[1]);
    }

    @Test
    void aMovingTargetEscapesFurtherAhead() {
        // At full speed north (counter-clockwise around the origin): direction +1 is ahead.
        double[] r = range(600, 300, Math.PI / 2, 8, 1);
        assertTrue(r[1] > -r[0] + 0.1, "low " + r[0] + ", high " + r[1]);
    }

    @Test
    void aWallAheadCutsTheRangeOnThatSide() {
        double[] open = range(600, 300, Math.PI / 2, 8, 1);
        // 60 px below the top wall, driving towards it.
        double[] walled = range(600, 540, Math.PI / 2, 8, 1);
        assertTrue(walled[1] < open[1] - 0.1, "open " + open[1] + ", walled " + walled[1]);
    }

    @Test
    void theSignFollowsTheDirection() {
        double[] plus = range(600, 300, Math.PI / 2, 8, 1);
        double[] minus = range(600, 300, Math.PI / 2, 8, -1);
        assertEquals(plus[1], -minus[0], 1e-12);
        assertEquals(plus[0], -minus[1], 1e-12);
    }
}
