package cz.libors;

import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.MeleeGun;
import cz.libors.ogi.gun.MeleeGunParameters;
import cz.libors.ogi.gun.VirtualGuns;
import cz.libors.ogi.move.EnemyWaves;
import cz.libors.ogi.move.MeleeMovement;
import cz.libors.ogi.team.EnemySighting;
import cz.libors.ogi.trace.HitRates;
import cz.libors.ogi.trace.Trace;
import cz.libors.ogi.world.BattleKind;
import cz.libors.ogi.world.EnemyState;
import cz.libors.ogi.world.Phase;
import cz.libors.ogi.world.World;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayFeedTest {

    private static final Field FIELD = new Field(800, 600);

    /** A painted turn shows the last turn's states, in the overlay's order, and what the components describe. */
    @Test
    void aPaintedTurnShowsTheLastTurnsStates() {
        World world = new World();
        world.observeEnemy(new EnemySighting("e", 600, 300, 0, 8, 100, 10, 0));
        VirtualGuns guns = new VirtualGuns(FIELD);
        HitRates rates = new HitRates();
        for (int i = 0; i < 5; i++) {
            rates.aimed("e", i < 2);
        }
        OverlayFeed feed = new OverlayFeed(FIELD, world, guns,
                new MeleeGun(FIELD, new MeleeGun.Memory(), MeleeGunParameters.DEFAULT),
                new MeleeMovement(FIELD, new Random(1)), new EnemyWaves(FIELD, 0.1), null, rates);
        Graphics2D g = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB).createGraphics();

        assertFalse(feed.painting(10));
        feed.turnStarted();
        feed.state(Trace.State.SAVING_ENERGY);
        feed.state(Trace.State.SHIELD);
        feed.paint(g, 10, 200, 300, BattleKind.DUEL, Phase.DUEL, 1, "us", Double.NEGATIVE_INFINITY, List.of());
        assertTrue(feed.painting(10));
        assertEquals(List.of(Trace.State.SHIELD, Trace.State.SAVING_ENERGY, Trace.State.SEARCHING),
                feed.trace().states().states());
        Trace.EnemyInfo info = feed.trace().info().enemies().get(0);
        assertEquals(0.4, info.ourRate(), 1e-12);
        assertEquals(-1, info.scanAge(), "a duel's radar locks");

        // The plan of a painted turn is traced, and a new turn's states start anew.
        guns.scanned("e", 10, 600, 300, 0, 8);
        EnemyState target = world.enemy("e").orElseThrow();
        guns.aim("e", 10, 200, 300, 2, false, Double.NaN);
        feed.planned(target, 10, 200, 300, 14, false);
        feed.turnStarted();
        feed.paint(g, 11, 200, 300, BattleKind.DUEL, Phase.DUEL, 1, "us", 0.5, List.of());
        assertEquals(List.of(), feed.trace().states().states());
        g.dispose();
    }
}
