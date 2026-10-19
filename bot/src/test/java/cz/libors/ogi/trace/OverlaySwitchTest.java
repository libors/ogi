package cz.libors.ogi.trace;

import cz.libors.ogi.world.BattleKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The layers' switches: what each kind of battle offers and shows, the keys, and the duel at a round's end. */
class OverlaySwitchTest {

    private static final String KEYS = "egmrhabicd";

    @BeforeEach
    void reset() {
        Overlay.reset();
    }

    /** Before the battle's first round no layer is offered, so a key switches nothing. */
    @Test
    void aKeyBeforeTheBattleSwitchesNothing() {
        Overlay.key('e');
        Overlay.battle(BattleKind.MELEE);
        assertFalse(Overlay.shown('e'));
    }

    @Test
    void aDuelOffersNeitherMovementNorRadar() {
        Overlay.battle(BattleKind.DUEL);
        assertTrue(Overlay.shown('e') && Overlay.shown('g'), "a duel shows both kinds of waves");
        for (char k : "mrac".toCharArray()) {
            assertFalse(Overlay.offered(k), "layer " + k);
            Overlay.key(k);
            assertFalse(Overlay.shown(k), "layer " + k);
        }
        Overlay.key('e');
        assertFalse(Overlay.shown('e'));
        Overlay.key('E');
        assertTrue(Overlay.shown('e'), "upper case too");
    }

    @Test
    void aMeleeOffersNoRadarAndATeamBattleShowsIt() {
        Overlay.battle(BattleKind.MELEE);
        assertFalse(Overlay.offered('r'));
        assertTrue(Overlay.shown('m') && Overlay.shown('a') && Overlay.shown('c') && Overlay.shown('d'));
        Overlay.reset();
        Overlay.battle(BattleKind.TEAM);
        assertTrue(Overlay.shown('r'));
        assertFalse(Overlay.shown('e') || Overlay.shown('g') || Overlay.shown('m'),
                "the waves of all robots would cover each other");
    }

    /** The battle's kind comes from its first round: a later round's call changes nothing. */
    @Test
    void theFirstKindWins() {
        Overlay.battle(BattleKind.MELEE);
        Overlay.key('h');
        Overlay.battle(BattleKind.TEAM);
        assertFalse(Overlay.offered('r'), "a melee offers no team radar");
        assertFalse(Overlay.shown('h'), "the key's choice stays");
    }

    @Test
    void theDuelShowsBothWavesAndTheNextRoundRestores() {
        Overlay.battle(BattleKind.MELEE);
        boolean[] before = shown();
        Overlay.duel(true);
        assertTrue(Overlay.shown('e') && Overlay.shown('g'), "both kinds of waves with the gun panel");
        assertFalse(Overlay.shown('a') || Overlay.shown('m'), "no melee aim, and in a melee no movement: it surfs");
        Overlay.duel(true);
        assertTrue(Overlay.shown('e'), "the next turn of the duel keeps it");
        Overlay.duel(false);
        assertArrayEquals(before, shown());
    }

    /** A team's endgame moves as a melee does: its movement layer stays as it was. */
    @Test
    void aTeamsDuelKeepsTheMovement() {
        Overlay.battle(BattleKind.TEAM);
        Overlay.key('m');
        Overlay.duel(true);
        assertTrue(Overlay.shown('m'));
        assertTrue(Overlay.shown('e') && Overlay.shown('g'));
    }

    /** A duel battle is a duel from the start: the duel switch leaves its layers alone. */
    @Test
    void aDuelBattleIgnoresTheDuelSwitch() {
        Overlay.battle(BattleKind.DUEL);
        Overlay.key('e');
        Overlay.duel(true);
        assertFalse(Overlay.shown('e'));
    }

    private static boolean[] shown() {
        boolean[] shown = new boolean[KEYS.length()];
        for (int i = 0; i < shown.length; i++) {
            shown[i] = Overlay.shown(KEYS.charAt(i));
        }
        return shown;
    }
}
