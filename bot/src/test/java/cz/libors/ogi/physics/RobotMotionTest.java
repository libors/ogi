package cz.libors.ogi.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Readable examples of the engine rules. The proof that they match the engine is {@code RobotMotionReplayTest} in
 * the harness, which replays recordings of the real engine.
 */
class RobotMotionTest {

    @Test
    void acceleratesByOneAndBrakesByTwo() {
        assertEquals(1, RobotMotion.newVelocity(0, 100, 8));
        assertEquals(8, RobotMotion.newVelocity(7.5, 100, 8));
        assertEquals(6, RobotMotion.newVelocity(8, -100, 8));
    }

    @Test
    void reversingThroughZeroSpendsTheRestOfTheTurnAccelerating() {
        // Half a turn braking from 1 to 0 at 2, half a turn accelerating at 1.
        assertEquals(-0.5, RobotMotion.newVelocity(1, -100, 8));
        assertEquals(-0.25, RobotMotion.newVelocity(1.5, -100, 8));
        // Braking from 2 takes the whole turn.
        assertEquals(0, Math.abs(RobotMotion.newVelocity(2, -100, 8)));
    }

    @Test
    void slowsDownToStopExactlyAtTheEndOfTheDistance() {
        assertEquals(3, RobotMotion.maxVelocityFor(4));
        assertEquals(3, RobotMotion.newVelocity(4, 4, 8));
        assertEquals(8, RobotMotion.maxVelocityFor(Double.POSITIVE_INFINITY));
    }

    @Test
    void bodyTurnRateUsesTheVelocityBeforeThisTurn() {
        EngineRobot r = robotAt(400, 300);
        r.velocity = 8;
        r.bodyTurnRemaining = 1;
        r.distanceRemaining = -100;
        RobotMotion.move(r, 800, 600, 0.1);
        // At velocity 8 the rate is 4°, although the robot brakes to 6 in the same turn.
        assertEquals(0.4 * RobotMotion.MAX_TURN_RATE, r.bodyHeading, 1e-15);
        assertEquals(6, r.velocity);
    }

    @Test
    void gunAndRadarTurnWithTheBodyUnlessAdjusted() {
        EngineRobot r = robotAt(400, 300);
        r.bodyTurnRemaining = 0.1;
        RobotMotion.move(r, 800, 600, 0.1);
        assertEquals(0.1, r.gunHeading, 1e-15);
        assertEquals(0.1, r.radarHeading, 1e-15);

        EngineRobot adjusted = robotAt(400, 300);
        adjusted.adjustGunForBodyTurn = true;
        adjusted.adjustRadarForGunTurn = true;
        adjusted.adjustRadarForBodyTurn = true;
        adjusted.bodyTurnRemaining = 0.1;
        RobotMotion.move(adjusted, 800, 600, 0.1);
        // Gun and radar turn with the body, then back by the compensating turn within the same engine turn.
        assertEquals(0.1, adjusted.bodyHeading, 1e-15);
        assertEquals(0, adjusted.gunHeading);
        assertEquals(0, adjusted.radarHeading);
    }

    @Test
    void wallStopsTheRobotEighteenPixelsFromTheEdge() {
        EngineRobot r = robotAt(400, 579);
        r.velocity = 8;
        r.distanceRemaining = 100;
        assertTrue(RobotMotion.move(r, 800, 600, 0.1));
        assertEquals(582, r.y);
        assertEquals(0, r.velocity);
        assertEquals(0, r.distanceRemaining);
        assertEquals(100 - RobotMotion.wallHitDamage(8), r.energy);
    }

    @Test
    void firesOnlyWithACoolGun() {
        EngineRobot r = robotAt(400, 300);
        r.gunHeat = 0.1;
        assertEquals(0, RobotMotion.fire(r, 3));
        r.gunHeat = 0;
        assertEquals(3, RobotMotion.fire(r, 5));
        assertEquals(97, r.energy);
        assertEquals(1.6, r.gunHeat, 1e-15);
        assertFalse(RobotMotion.move(r, 800, 600, 0.1));
        assertEquals(1.5, r.gunHeat, 1e-15);
    }

    private static EngineRobot robotAt(double x, double y) {
        EngineRobot r = new EngineRobot();
        r.x = x;
        r.y = y;
        r.energy = 100;
        return r;
    }
}
