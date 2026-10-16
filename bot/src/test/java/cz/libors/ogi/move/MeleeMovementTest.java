package cz.libors.ogi.move;

import cz.libors.ogi.geom.Field;
import cz.libors.ogi.move.MeleeMovement.Robot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeleeMovementTest {

    private static final Field FIELD = new Field(1000, 1000);

    @Test
    void drivesAwayFromALoneEnemy() {
        MeleeMovement m = new MeleeMovement(FIELD, new Random(1));
        m.command(0, 500, 500, 0, 100, List.of(new Robot(800, 500, 100, true), new Robot(800, 520, 100, true)));
        double[] d = m.destination();
        assertTrue(d[0] < 500, "west of us, away from the enemies at x = 800: " + d[0]);
    }

    @Test
    void beingAnEnemysClosestRobotIsRiskier() {
        MeleeMovement m = new MeleeMovement(FIELD, new Random(1));
        // A destination 300 px south of the enemy at (500, 500), with and without another enemy 290 px north of it:
        // with it, we are not the first enemy's closest robot there, and the other one is 590 px away.
        List<Robot> alone = List.of(new Robot(500, 500, 100, true));
        List<Robot> covered = List.of(new Robot(500, 500, 100, true), new Robot(500, 790, 100, true));
        double exposed = m.risk(400, 200, 500, 200, 100, alone, new double[][] {{0}});
        double shielded = m.risk(400, 200, 500, 200, 100, covered, new double[][] {{0, 290}, {290, 0}});
        assertTrue(shielded < exposed, shielded + " with a closer robot against " + exposed + " without");
    }

    @Test
    void movingAcrossTheLineToAnEnemyIsSaferThanAlongIt() {
        MeleeMovement m = new MeleeMovement(FIELD, new Random(1));
        List<Robot> robots = List.of(new Robot(500, 900, 100, true));
        double[][] distances = {{0}};
        // Both destinations are 400 px from the enemy; one is reached along the line to it, one across it.
        double along = m.risk(500, 400, 500, 500, 100, robots, distances);
        double across = m.risk(400, 500, 500, 500, 100, robots, distances);
        assertTrue(across < along, across + " across against " + along + " along");
    }

    @Test
    void destinationsKeepAwayFromTheWallsAndRepeatWithTheSeed() {
        List<Robot> robots = List.of(new Robot(200, 500, 100, true), new Robot(300, 300, 100, true),
                new Robot(150, 150, 50, true));
        double[] first = null;
        for (int run = 0; run < 2; run++) {
            MeleeMovement m = new MeleeMovement(FIELD, new Random(5));
            for (long t = 0; t < 50; t++) {
                m.command(t, 60, 60, 0, 100, robots);
                double[] d = m.destination();
                assertTrue(FIELD.contains(d[0], d[1], MeleeMovement.WALL_MARGIN), d[0] + ", " + d[1]);
            }
            if (first == null) {
                first = m.destination();
            } else {
                assertArrayEquals(first, m.destination());
            }
        }
    }
}
