package cz.libors.ogi.world;

import cz.libors.ogi.team.EnemySighting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldTest {

    private static EnemySighting sighting(String name, double x, double heading, long time) {
        return new EnemySighting(name, x, 100, heading, 8, 100, time, 0);
    }

    @Test
    void turnRateIsEstimatedFromConsecutiveSightings() {
        World world = new World();
        world.observeEnemy(sighting("a", 100, 1.0, 10));
        world.observeEnemy(sighting("a", 110, 1.2, 12));
        assertEquals(0.1, world.enemies().iterator().next().turnRate(), 1e-12);
    }

    @Test
    void olderSightingsAreIgnored() {
        World world = new World();
        world.observeEnemy(sighting("a", 100, 1.0, 10));
        world.observeEnemy(sighting("a", 300, 1.0, 9));
        assertEquals(100, world.enemies().iterator().next().x(), 1e-12);
    }

    @Test
    void closestEnemyIgnoresStaleOnes() {
        World world = new World();
        world.observeEnemy(sighting("near", 110, 0, 1));
        world.observeEnemy(sighting("far", 500, 0, 20));
        assertEquals("far", world.closestEnemy(100, 100, 21, 5).orElseThrow().name());
        assertTrue(world.closestEnemy(100, 100, 100, 5).isEmpty());
    }
}
