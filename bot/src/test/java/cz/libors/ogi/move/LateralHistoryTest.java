package cz.libors.ogi.move;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LateralHistoryTest {

    /** A movement that repeats: 10 turns left at 8, 5 standing, 10 right at 8, and again. */
    private static LateralHistory oscillation(int turns) {
        LateralHistory h = new LateralHistory();
        for (int t = 0; t < turns; t++) {
            int phase = t % 25;
            h.add(t, phase < 10 ? 8 : phase < 15 ? 0 : -8);
        }
        return h;
    }

    @Test
    void replaysWhatFollowedTheLatestMatch() {
        LateralHistory h = oscillation(100);
        // Turn 99 ends a run right; the 6 turns after each earlier one went left at 8.
        assertEquals(48, h.replay(99, 40, 6), 1e-9);
        // Turn 59 ends a run left; after it we stood 5 turns, then went right.
        assertEquals(-8, h.replay(59, 40, 6), 1e-9);
    }

    /**
     * An orbit tilted towards the preferred distance crosses the line at 7.6 px/turn: 8 rounded, 7 truncated, as a gun
     * that casts our lateral velocity to a whole number sees it.
     */
    @Test
    void truncatesTowardsZeroForGunsThatCast() {
        LateralHistory h = new LateralHistory();
        for (int t = 0; t < 100; t++) {
            int phase = t % 25;
            h.add(t, phase < 10 ? 7.6 : phase < 15 ? 0 : -7.6);
        }
        assertEquals(48, h.replay(99, 40, 6), 1e-9);
        assertEquals(42, h.replayTruncated(99, 40, 6), 1e-9);
        assertEquals(-7, h.replayTruncated(59, 40, 6), 1e-9);
    }

    @Test
    void noMatchWithoutHistory() {
        LateralHistory h = new LateralHistory();
        for (int t = 0; t < 5; t++) {
            h.add(t, t);
        }
        assertTrue(Double.isNaN(h.replay(4, 40, 3)));
    }

    @Test
    void averagesWithinTheRound() {
        LateralHistory h = new LateralHistory();
        for (int t = 0; t < 30; t++) {
            h.add(t, 8);
        }
        // A new round: time starts again.
        for (int t = 0; t < 10; t++) {
            h.add(t, -4);
        }
        assertEquals(-4, h.average(9, 20), 1e-12, "the last round's turns do not count");
        assertEquals(-4, h.average(5, 3), 1e-12);
    }
}
