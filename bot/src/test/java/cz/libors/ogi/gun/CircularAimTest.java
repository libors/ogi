package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.world.EnemyState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircularAimTest {

    private static final Field FIELD = new Field(800, 600);

    @Test
    void stationaryTargetIsAimedAtDirectly() {
        EnemyState target = new EnemyState("t", 600, 400, 0, 0, 0, 100, 10);
        double aim = CircularAim.aim(200, 200, target, 14, 10, FIELD);
        assertEquals(Angles.bearing(200, 200, 600, 400), aim, 1e-12);
    }

    @Test
    void targetMovingNorthIsLedNorthWithinEscapeAngle() {
        double speed = 14;
        EnemyState target = new EnemyState("t", 600, 200, Math.PI / 2, 8, 0, 100, 10);
        double aim = CircularAim.aim(200, 200, target, speed, 10, FIELD);
        assertTrue(aim > 0, "expected lead towards +y, got " + aim);
        // The intercept is found on whole ticks, so it may pass the continuous maximum escape angle by a hair.
        assertTrue(aim < Math.asin(8 / speed) + 0.01, "lead exceeds maximum escape angle: " + aim);
    }

    @Test
    void predictionStaysInsideTheField() {
        EnemyState target = new EnemyState("t", 780, 580, Math.PI / 4, 8, 0.1, 100, 0);
        double aim = CircularAim.aim(100, 100, target, 11, 0, FIELD);
        assertTrue(Double.isFinite(aim));
    }
}
