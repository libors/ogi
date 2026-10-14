package cz.libors.ogi.gun;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RacePowerTest {

    @Test
    void againstAnEnemyThatRarelyHitsUsAWeakBulletWins() {
        // It hits us 5 % with power 2 and keeps firing; we hit it 15 %: its own shots run it down while ours cost us.
        assertEquals(0.1, RacePower.choose(0.15, 0.05, 2, 1.916666666666667), 1e-9);
    }

    @Test
    void againstAnEnemyThatHitsUsOftenOurDamageHasToEndTheRound() {
        // It hits us 30 %: its hits drain us whatever we fire, so our damage is what shortens the race.
        assertEquals(1.916666666666667, RacePower.choose(0.2, 0.3, 2, 1.916666666666667), 1e-9);
    }

    @Test
    void neverAboveTheOtherRulesPower() {
        assertTrue(RacePower.choose(0.6, 0.3, 3, 0.8) <= 0.8);
    }

    @Test
    void aFasterBulletHitsMoreOften() {
        // With h at 1.9 measured as 0, the ratio is the same for every power once our hit rate is scaled; with h > 0
        // the scaled hit rate of 0.1 is higher than measured, so its ratio beats the unscaled one.
        double scaled = RacePower.ratio(0.1, 0.2, 0.1, 2, 1.9);
        double unscaled = RacePower.ratio(0.1, 0.2, 0.1, 2, 0.1);
        assertTrue(scaled > unscaled, scaled + " vs " + unscaled);
    }
}
