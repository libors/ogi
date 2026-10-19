package cz.libors.ogi.radar;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.world.EnemyState;
import cz.libors.ogi.world.Phase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadarControlTest {

    private static final long NOW = 20;
    private static final EnemyState A = enemy("a", 700, 500, NOW);
    private static final EnemyState B = enemy("b", 300, 800, NOW - 1);

    /**
     * At a melee round's start the radar spins until every enemy has been seen; while the gun is too hot to aim soon
     * it spins along and carries the radar. The carrying starts and ends with a change of the radar's adjustment.
     */
    @Test
    void theGunCarriesTheRadarThroughTheFirstSweepWhileIdle() {
        RadarControl radar = new RadarControl(null);
        RadarControl.Command c = melee(radar, List.of(A), true);
        assertTrue(Double.isInfinite(c.radarTurn()), "spinning: one of two enemies seen");
        assertTrue(c.gunCarries() && c.sweepChanged());
        assertTrue(radar.sweeping());
        c = melee(radar, List.of(A), true);
        assertTrue(c.gunCarries());
        assertFalse(c.sweepChanged(), "carrying on");
        // Both seen: the melee radar sweeps to the stalest, and the gun is free to aim.
        c = melee(radar, List.of(A, B), true);
        assertFalse(Double.isInfinite(c.radarTurn()));
        assertTrue(!c.gunCarries() && c.sweepChanged());
        assertFalse(radar.sweeping());
    }

    /** A gun that is about to aim does not carry the radar, spinning or not. */
    @Test
    void aGunAboutToAimCarriesNothing() {
        RadarControl radar = new RadarControl(null);
        RadarControl.Command c = melee(radar, List.of(A), false);
        assertTrue(Double.isInfinite(c.radarTurn()));
        assertFalse(c.gunCarries() || c.sweepChanged() || radar.sweeping());
    }

    /** In a duel the radar locks on a target scanned in the last turn, twice its bearing's offset; else it spins. */
    @Test
    void aDuelLocksOnAFreshTarget() {
        RadarControl radar = new RadarControl(null);
        double heading = 0.3;
        RadarControl.Command c = radar.steer(NOW, "us", 500, 500, heading, Phase.DUEL, 1, 0, List.of(A), A, false,
                false);
        assertEquals(-2 * Angles.robocodeTurn(heading, Angles.bearing(500, 500, A.x(), A.y())), c.radarTurn(),
                1e-12);
        assertFalse(c.gunCarries() || c.sweepChanged());
        EnemyState old = enemy("a", 700, 500, NOW - 2);
        assertEquals(Double.NEGATIVE_INFINITY,
                radar.steer(NOW, "us", 500, 500, heading, Phase.DUEL, 1, 0, List.of(old), old, false, false)
                        .radarTurn());
    }

    /** In a team's endgame each of our robots sweeps the last enemy's arc. */
    @Test
    void aTeamsEndgameSweepsTheLastEnemy() {
        RadarControl radar = new RadarControl(new TeamRadar());
        double heading = 1;
        RadarControl.Command c = radar.steer(NOW, "us", 500, 500, heading, Phase.TEAM_ENDGAME, 1, 2, List.of(A), A,
                false, false);
        TeamRadar.Arc arc = TeamRadar.arc(NOW, 500, 500, List.of(new MeleeRadar.Seen("a", 700, 500, NOW)));
        assertEquals(TeamRadar.sweep(heading, arc), c.radarTurn(), 1e-12);
    }

    /**
     * Once every enemy has been seen, a team member's radar sweeps the enemies the team's plan gives it; before a shot
     * a stale target goes first, by the melee radar.
     */
    @Test
    void inATeamTheRadarSweepsWhatThePlanGivesUs() {
        TeamRadar team = new TeamRadar();
        team.reported(new TeamRadar.Watcher("us", 500, 500, 0, NOW));
        team.reported(new TeamRadar.Watcher("mate", 200, 900, 0, NOW));
        team.scanned("a", A.x(), A.y(), NOW - 1);
        team.scanned("b", B.x(), B.y(), NOW - 1);
        RadarControl radar = new RadarControl(team);
        List<EnemyState> known = List.of(enemy("a", 700, 500, NOW - 1), enemy("b", 300, 800, NOW - 1));
        RadarControl.Command c = radar.steer(NOW, "us", 500, 500, 0, Phase.MELEE, 2, 1, known, known.get(0), false,
                false);
        List<String> mine = team.watch(NOW, "us");
        assertEquals(List.of("a"), mine, "the nearer robot watches each enemy");
        TeamRadar.Arc arc = TeamRadar.arc(NOW, 500, 500, List.of(new MeleeRadar.Seen("a", 700, 500, NOW - 1)));
        assertEquals(TeamRadar.sweep(0, arc), c.radarTurn(), 1e-12);
        // The gun is cool soon and its target's scan is two turns old: the melee radar turns to it first.
        EnemyState stale = enemy("b", 300, 800, NOW - 2);
        RadarControl.Command first = radar.steer(NOW, "us", 500, 500, 0, Phase.MELEE, 2, 1,
                List.of(known.get(0), stale), stale, true, false);
        double offset = Angles.normalizeRelative(Angles.bearing(500, 500, 300, 800));
        assertEquals(Math.signum(offset), Math.signum(first.radarTurn()), "towards the target");
        assertTrue(Math.abs(first.radarTurn()) > Math.abs(offset), "past its bearing");
    }

    private static RadarControl.Command melee(RadarControl radar, List<EnemyState> known, boolean gunIdle) {
        return radar.steer(NOW, "us", 500, 500, 0, Phase.MELEE, 2, 0, known, known.get(0), false, gunIdle);
    }

    private static EnemyState enemy(String name, double x, double y, long time) {
        return new EnemyState(name, x, y, 0, 0, 0, 100, time);
    }
}
