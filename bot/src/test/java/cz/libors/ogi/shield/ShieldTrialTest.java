package cz.libors.ogi.shield;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShieldTrialTest {

    @Test
    void damageBeyondTheBudgetDropsTheShieldOnce() {
        ShieldTrial trial = new ShieldTrial();
        for (int i = 0; i < 20; i++) {
            assertNull(trial.fired("e"));
        }
        // A budget of 35 + 0.25 · 20 = 40.
        assertNull(trial.shotDown("e"));
        assertNull(trial.hit("e", 16));
        assertNull(trial.hit("e", 16));
        assertNull(trial.hit("e", 8));
        assertFalse(trial.dropped("e"));
        assertEquals("dropped damage 40.4 shots 20 lost 0 down 1 hit 4", trial.hit("e", 0.4));
        assertTrue(trial.dropped("e"));
        assertNull(trial.hit("e", 16));
        assertFalse(trial.dropped("other"));
    }

    @Test
    void twoLostRoundsDropTheShield() {
        ShieldTrial trial = new ShieldTrial();
        assertNull(trial.roundLost("e"));
        assertEquals("dropped damage 0.0 shots 0 lost 2 down 0 hit 0", trial.roundLost("e"));
    }

    @Test
    void fewBulletsShotDownDropTheShield() {
        ShieldTrial trial = new ShieldTrial();
        for (int i = 0; i < 1000; i++) {
            trial.fired("e");
        }
        trial.shotDown("e");
        for (int i = 0; i < 9; i++) {
            assertNull(trial.shotDown("e"));
            assertNull(trial.hit("e", 0.4));
        }
        // 19 ended, 10 of them shot down; the 20th, a hit, leaves 10 of 20: not fewer than half.
        assertNull(trial.hit("e", 0.4));
        assertNotNullAfter(trial.hit("e", 0.4));
    }

    @Test
    void hitsBeforeTheFirstBulletShotDownDropTheShield() {
        ShieldTrial trial = new ShieldTrial();
        for (int i = 0; i < 100; i++) {
            trial.fired("e");
        }
        for (int i = 0; i < 5; i++) {
            assertNull(trial.hit("e", 0.4));
        }
        assertEquals("dropped damage 2.4 shots 100 lost 0 down 0 hit 6", trial.hit("e", 0.4));

        ShieldTrial downFirst = new ShieldTrial();
        for (int i = 0; i < 100; i++) {
            downFirst.fired("e");
        }
        downFirst.shotDown("e");
        for (int i = 0; i < 6; i++) {
            assertNull(downFirst.hit("e", 0.4));
        }
    }

    private static void assertNotNullAfter(String verdict) {
        assertTrue(verdict != null && verdict.startsWith("dropped"), String.valueOf(verdict));
    }
}
