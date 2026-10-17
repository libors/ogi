package cz.libors.ogi.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhaseTest {

    @Test
    void phaseFollowsTheRobotsAlive() {
        assertEquals(Phase.DUEL, Phase.of(1, 0));
        assertEquals(Phase.MELEE, Phase.of(2, 0));
        assertEquals(Phase.MELEE, Phase.of(5, 4));
        assertEquals(Phase.TEAM_ENDGAME, Phase.of(1, 1));
        assertEquals(Phase.TEAM_ENDGAME, Phase.of(1, 4));
        assertEquals(Phase.NO_ENEMY, Phase.of(0, 0));
        assertEquals(Phase.NO_ENEMY, Phase.of(0, 3));
    }

    @Test
    void battleKindFromTheFirstRound() {
        assertEquals(BattleKind.DUEL, BattleKind.of(false, 1));
        assertEquals(BattleKind.MELEE, BattleKind.of(false, 9));
        assertEquals(BattleKind.TEAM, BattleKind.of(true, 9));
    }
}
