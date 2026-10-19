package cz.libors;

import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.MeleeGun;
import cz.libors.ogi.gun.MeleeGunParameters;
import cz.libors.ogi.gun.VirtualGuns;
import cz.libors.ogi.move.EnemyWaves;
import cz.libors.ogi.move.MeleeMovement;
import cz.libors.ogi.team.EnemySighting;
import cz.libors.ogi.team.TeamShot;
import cz.libors.ogi.trace.HitRates;
import cz.libors.ogi.trace.Overlay;
import cz.libors.ogi.trace.OverlaySwitches;
import cz.libors.ogi.trace.Trace;
import cz.libors.ogi.world.BattleKind;
import cz.libors.ogi.world.EnemyState;
import cz.libors.ogi.world.Phase;
import cz.libors.ogi.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import robocode.Bullet;
import robocode.Rules;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayFeedTest {

    private static final Field FIELD = new Field(800, 600);
    private static final String US = "cz.libors.OgiTeam (1)";
    private static final String MATE = "cz.libors.OgiTeam (2)";

    @BeforeEach
    void reset() {
        OverlaySwitches.reset();
    }

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
        feed.paint(g, 10, 200, 300, 100, BattleKind.DUEL, Phase.DUEL, 1, "us", Double.NEGATIVE_INFINITY, List.of());
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
        feed.paint(g, 11, 200, 300, 100, BattleKind.DUEL, Phase.DUEL, 1, "us", 0.5, List.of());
        assertEquals(List.of(), feed.trace().states().states());
        assertTrue(Double.isNaN(feed.trace().hits().duel()), "a duel from the start has no duel's start");
        g.dispose();
    }

    /**
     * The bullets that hit us while we paint, with where they came from and their shooter's view, the damage dealt,
     * and our energy lead when the melee came down to a duel.
     */
    @Test
    void hitsOnUsShowWhereTheyCameFromWhileWePaint() {
        World world = new World();
        world.observeEnemy(new EnemySighting("e", 600, 300, 0, 8, 100, 10, 0));
        world.observeEnemy(new EnemySighting("f", 250, 320, 0, 8, 70, 10, 0));
        OverlayFeed feed = feed(world);
        Graphics2D g = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB).createGraphics();

        // Not painting yet: not kept.
        feed.hitUs(5, 200, 300, "e", 2, new EnemyWaves.HitOrigin(600, 300, 1, 0));
        feed.hitEnemy(5, 3);
        feed.paint(g, 10, 200, 300, 80, BattleKind.MELEE, Phase.MELEE, 2, "us", 0.5, List.of());
        assertEquals(List.of(), feed.trace().hits().hits());
        // A bullet fire detection saw, one it missed (towards the shooter's last scan), and one of ours that hit.
        feed.hitUs(11, 200, 300, "e", 2, new EnemyWaves.HitOrigin(590, 310, 1, 3));
        feed.hitUs(11, 205, 300, "f", 3, null);
        feed.hitEnemy(11, 3);
        feed.paint(g, 12, 200, 300, 80, BattleKind.MELEE, Phase.MELEE, 2, "us", 0.5, List.of());
        Trace.HitsView v = feed.trace().hits();
        assertEquals(List.of(new Trace.Hit(200, 300, 590, 310, 1, 3), new Trace.Hit(205, 300, 250, 320, 1, -1)),
                v.hits());
        assertArrayEquals(new double[] {0, 0, 10, 16}, v.taken(), 1e-12);
        assertEquals(16, v.dealt(), 1e-12);
        assertTrue(Double.isNaN(v.duel()));
        // f is closest to e, we are closest to f.
        Trace.ClosestView c = feed.trace().closest();
        assertEquals(1, c.ours().length);
        assertEquals(1, c.ours()[0][2]);

        // Down to one enemy: our energy lead then, kept; the hits fade out.
        world.robotDied("f");
        feed.paint(g, 13, 200, 300, 80, BattleKind.MELEE, Phase.DUEL, 1, "us", 0.5, List.of());
        assertEquals(-20, feed.trace().hits().duel(), 1e-12);
        feed.paint(g, 11 + Overlay.HIT_TURNS, 200, 300, 40, BattleKind.MELEE, Phase.DUEL, 1, "us", 0.5, List.of());
        assertEquals(-20, feed.trace().hits().duel(), 1e-12);
        assertEquals(List.of(), feed.trace().hits().hits());
        g.dispose();
    }

    /** In a team an enemy's closest robot is one of ours: it does not fire at its teammates. */
    @Test
    void inATeamOnlyOurRobotsCountAsClosest() {
        World world = new World();
        world.observeEnemy(new EnemySighting("e", 600, 300, 0, 8, 100, 10, 0));
        world.observeEnemy(new EnemySighting("f", 610, 300, 0, 8, 100, 10, 0));
        world.observeTeammate(new World.Teammate("cz.libors.OgiTeam (2)", 500, 300, 0, 0, 100, 10));
        OverlayFeed feed = feed(world);
        feed.roundStarted(BattleKind.TEAM, 2);
        Graphics2D g = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB).createGraphics();
        feed.paint(g, 10, 200, 300, 100, BattleKind.TEAM, Phase.MELEE, 2, US, 0.5, List.of());
        // Both enemies' closest robot is the teammate at (500, 300), not each other.
        Trace.ClosestView c = feed.trace().closest();
        assertEquals(0, c.ours()[0][2]);
        assertEquals(2, c.ours()[1][2]);
        g.dispose();
    }

    /**
     * The round's start offers the layers, painting or not: a key pressed before this robot's first paint switches
     * them as on the teammates that paint already.
     */
    @Test
    void aLayerKeyBeforeTheFirstPaintCounts() {
        OverlayFeed feed = feed(new World());
        feed.roundStarted(BattleKind.TEAM, 5);
        assertFalse(OverlaySwitches.shown('e'), "a team battle starts without the enemy waves");
        Overlay.key('e');
        assertTrue(OverlaySwitches.shown('e'));
        Graphics2D g = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB).createGraphics();
        feed.paint(g, 10, 200, 300, 100, BattleKind.TEAM, Phase.MELEE, 2, US, 0.5, List.of());
        assertTrue(OverlaySwitches.shown('e'), "the first paint keeps the choice");
        g.dispose();
    }

    /**
     * A plan made in a painted turn becomes our bullet's wave when it fires in a painted turn, the plan's head-on aim
     * its reference. Without painting in either turn nothing is traced: a battle without the GUI computes nothing
     * more. The gun of our last bullet is kept all the same.
     */
    @Test
    void aFiredPlanBecomesAWaveOnlyWhilePainting() {
        for (int unpainted = 0; unpainted < 3; unpainted++) {
            World world = new World();
            world.observeEnemy(new EnemySighting("e", 600, 300, 0, 8, 100, 10, 0));
            VirtualGuns guns = new VirtualGuns(FIELD);
            guns.scanned("e", 10, 600, 300, 0, 8);
            guns.aim("e", 10, 200, 300, 2, false, Double.NaN);
            OverlayFeed feed = new OverlayFeed(FIELD, world, guns,
                    new MeleeGun(FIELD, new MeleeGun.Memory(), MeleeGunParameters.DEFAULT),
                    new MeleeMovement(FIELD, new Random(1)), new EnemyWaves(FIELD, 0.1), null, new HitRates());
            Graphics2D g = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB).createGraphics();
            // Turn 10 plans the shot, turn 11 fires it: both painted, or turn 10 or turn 11 not.
            if (unpainted != 1) {
                feed.paint(g, 10, 200, 300, 100, BattleKind.DUEL, Phase.DUEL, 1, "us", 0.5, List.of());
            }
            feed.planned(world.enemy("e").orElseThrow(), 10, 200, 300, Rules.getBulletSpeed(2), false);
            if (unpainted != 2) {
                feed.paint(g, 11, 200, 300, 100, BattleKind.DUEL, Phase.DUEL, 1, "us", 0.5, List.of());
            }
            feed.fired(11, new Bullet(Math.PI / 2, 214, 300, 2, "us", null, true, 1), "e", false,
                    VirtualGuns.GUESS_FACTOR);
            feed.paint(g, 12, 200, 300, 100, BattleKind.DUEL, Phase.DUEL, 1, "us", 0.5, List.of());
            List<Trace.WaveView> ours = feed.trace().waves().stream().filter(w -> !w.enemy()).toList();
            if (unpainted == 0) {
                assertEquals(1, ours.size());
                assertEquals(0, ours.get(0).reference(), 1e-12, "the head-on aim from (200, 300) at (600, 300)");
                assertEquals(Rules.getBulletSpeed(2), ours.get(0).radius(), 1e-12);
            } else {
                assertEquals(List.of(), ours, "turn " + (9 + unpainted) + " not painted");
            }
            assertEquals("e", feed.trace().lastShotTarget());
            assertEquals(VirtualGuns.gunName(VirtualGuns.GUESS_FACTOR), feed.trace().lastShotGun());
            g.dispose();
        }
    }

    /** A teammate's bullet reported while we paint is shown where it has flown; one reported before, never. */
    @Test
    void aTeammatesBulletIsShownOnlyIfReportedWhilePainting() {
        World world = new World();
        OverlayFeed feed = feed(world);
        feed.roundStarted(BattleKind.TEAM, 2);
        Graphics2D g = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB).createGraphics();
        feed.teamShot(new TeamShot(MATE, 100, 500, 0, 2, 9, 0), 10);
        feed.paint(g, 11, 200, 300, 100, BattleKind.TEAM, Phase.MELEE, 2, US, 0.5, List.of());
        assertEquals(List.of(), feed.trace().powers().others());
        feed.teamShot(new TeamShot(MATE, 100, 500, 0, 2, 11, 0), 12);
        feed.paint(g, 13, 200, 300, 100, BattleKind.TEAM, Phase.MELEE, 2, US, 0.5, List.of());
        assertEquals(List.of(new Trace.PowerMark(100 + 2 * Rules.getBulletSpeed(2), 500, 2)),
                feed.trace().powers().others());
        assertEquals(List.of(), feed.trace().powers().ours(), "not one of the guns' bullets");
        g.dispose();
    }

    /** A teammate's bullet that reaches us ends there, as at any other robot: friendly fire hits us too. */
    @Test
    void aTeammatesBulletEndsAtUs() {
        OverlayFeed feed = feed(new World());
        feed.roundStarted(BattleKind.TEAM, 2);
        Graphics2D g = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB).createGraphics();
        feed.paint(g, 10, 200, 300, 100, BattleKind.TEAM, Phase.MELEE, 2, US, 0.5, List.of());
        // From (100, 300) eastwards at 14 px a turn: 30 px short of our centre after 5 turns, inside our box after 6.
        feed.teamShot(new TeamShot(MATE, 100, 300, 0, 2, 10, 0), 11);
        feed.paint(g, 15, 200, 300, 100, BattleKind.TEAM, Phase.MELEE, 2, US, 0.5, List.of());
        assertEquals(1, feed.trace().powers().others().size());
        feed.paint(g, 16, 200, 300, 100, BattleKind.TEAM, Phase.MELEE, 2, US, 0.5, List.of());
        assertEquals(List.of(), feed.trace().powers().others());
        feed.paint(g, 17, 300, 300, 100, BattleKind.TEAM, Phase.MELEE, 2, US, 0.5, List.of());
        assertEquals(List.of(), feed.trace().powers().others(), "it does not come back once we move on");
        g.dispose();
    }

    /**
     * Our bullets fired while we shield have no gun wave: their power is shown while they fly, if they were fired
     * while painting.
     */
    @Test
    void aShieldShotShowsItsPower() {
        OverlayFeed feed = feed(new World());
        feed.roundStarted(BattleKind.DUEL, 0);
        Graphics2D g = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB).createGraphics();
        feed.shieldFired(9, new Bullet(Math.PI / 2, 210, 300, 0.3, "us", null, true, 1));
        feed.paint(g, 10, 200, 300, 100, BattleKind.DUEL, Phase.DUEL, 1, "us", 0.5, List.of());
        feed.shieldFired(10, new Bullet(Math.PI / 2, 219.4, 300, 0.2, "us", null, true, 2));
        feed.shieldFired(10, new Bullet(Math.PI / 2, 220, 300, 0.1, "us", null, false, 3));
        feed.shieldFired(10, null);
        feed.paint(g, 11, 200, 300, 100, BattleKind.DUEL, Phase.DUEL, 1, "us", 0.5, List.of());
        assertEquals(List.of(new Trace.PowerMark(219.4, 300, 0.2)), feed.trace().powers().others());
        assertEquals(List.of(), feed.trace().powers().ours());
        g.dispose();
    }

    /**
     * In a team neither hit rate is shown beside an enemy: ours would be the painting robot's bullets alone, drawn
     * once as if the team's, and the enemy's its hits on the painting robot over its shots at anyone.
     */
    @Test
    void inATeamOurRateIsNotShownAsTheTeams() {
        World world = new World();
        world.observeEnemy(new EnemySighting("e", 600, 300, 0, 8, 100, 10, 0));
        HitRates rates = new HitRates();
        for (int i = 0; i < 5; i++) {
            rates.aimed("e", i < 2);
        }
        OverlayFeed feed = new OverlayFeed(FIELD, world, new VirtualGuns(FIELD),
                new MeleeGun(FIELD, new MeleeGun.Memory(), MeleeGunParameters.DEFAULT),
                new MeleeMovement(FIELD, new Random(1)), new EnemyWaves(FIELD, 0.1), null, rates);
        feed.roundStarted(BattleKind.TEAM, 2);
        Graphics2D g = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB).createGraphics();
        feed.paint(g, 10, 200, 300, 100, BattleKind.TEAM, Phase.TEAM_ENDGAME, 1, US, 0.5, List.of());
        Trace.EnemyInfo info = feed.trace().info().enemies().get(0);
        assertTrue(Double.isNaN(info.ourRate()));
        assertTrue(Double.isNaN(info.theirRate()));
        g.dispose();
    }

    private static OverlayFeed feed(World world) {
        return new OverlayFeed(FIELD, world, new VirtualGuns(FIELD),
                new MeleeGun(FIELD, new MeleeGun.Memory(), MeleeGunParameters.DEFAULT),
                new MeleeMovement(FIELD, new Random(1)), new EnemyWaves(FIELD, 0.1), null, new HitRates());
    }
}
