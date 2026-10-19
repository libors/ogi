package cz.libors.ogi.move;

import cz.libors.ogi.move.MeleeMovement.Robot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GangWatchTest {

    /** Two enemies within the gang radius of (500, 500). */
    private static final List<Robot> GANG = List.of(new Robot(600, 500, 100, true), new Robot(500, 620, 100, true));
    /** The same enemies, one of them away. */
    private static final List<Robot> CALM = List.of(new Robot(600, 500, 100, true), new Robot(900, 900, 100, true));

    @Test
    void theDefenceWaitsForEnoughRobotTurns() {
        GangWatch watch = new GangWatch();
        for (long t = 1; t < GangWatch.MIN_TURNS; t++) {
            assertFalse(watch.turn(500, 500, GANG), "robot-turn " + t);
        }
        assertTrue(watch.turn(500, 500, GANG));
    }

    /** On from 4 % of the robot-turns ganged, 8 of 200, and off again once calm turns bring the share below it. */
    @Test
    void theDefenceIsOnFromItsShareOfGangedRobotTurns() {
        GangWatch seven = new GangWatch();
        GangWatch eight = new GangWatch();
        boolean sevenOn = false;
        boolean eightOn = false;
        for (int t = 0; t < 200; t++) {
            sevenOn = seven.turn(500, 500, t < 7 ? GANG : CALM);
            eightOn = eight.turn(500, 500, t < 8 ? GANG : CALM);
        }
        assertFalse(sevenOn, "7 of 200");
        assertTrue(eightOn, "8 of 200");
        // 8 of 201 is under 4 %.
        assertFalse(eight.turn(500, 500, CALM));
    }

    /** Every robot of ours counts: we and each teammate among the robots, as we see them. */
    @Test
    void ourTeammatesCountToo() {
        List<Robot> mateGanged = List.of(new Robot(600, 500, 100, true), new Robot(500, 620, 100, true),
                new Robot(550, 550, 0, false));
        GangWatch watch = new GangWatch();
        boolean on = false;
        for (int t = 0; t < GangWatch.MIN_TURNS / 2; t++) {
            // We are far from the enemies, the teammate between them: one of every two robot-turns is ganged.
            on = watch.turn(100, 100, mateGanged);
        }
        assertTrue(on, "100 turns of two robots each");
    }
}
