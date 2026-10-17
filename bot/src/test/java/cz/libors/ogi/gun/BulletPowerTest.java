package cz.libors.ogi.gun;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulletPowerTest {

    private static final String ENEMY = "a.Bot 1.0";

    @Test
    void fullPowerAtCloseRangeAndTheDefaultBeyond() {
        BulletPower power = new BulletPower();
        assertEquals(3, power.power(ENEMY, 150, 100, 100, true, 0.1, 2), 0);
        assertEquals(BulletPower.DEFAULT, power.power(ENEMY, 400, 100, 100, true, 0.1, 2), 0);
    }

    @Test
    void neverMoreThanKillsTheTargetOrThanWeCanAfford() {
        BulletPower power = new BulletPower();
        assertEquals(1.1, power.power(ENEMY, 400, 4, 100, false, 0.1, 2), 1e-12);
        assertEquals(0.9, power.power(ENEMY, 400, 100, 1, false, 0.1, 2), 1e-12);
        assertEquals(0, power.power(ENEMY, 400, 100, 0.15, false, 0.1, 2), 0);
    }

    @Test
    void anEnemyWeReadGetsFullPowerAtAnyDistance() {
        BulletPower power = new BulletPower();
        for (int i = 0; i < 20; i++) {
            power.ended(ENEMY + " (" + (1 + i % 2) + ")", i % 5 != 0);
        }
        assertTrue(power.readsWell(ENEMY));
        assertEquals(3, power.power(ENEMY + " (3)", 600, 100, 100, false, 0.1, 2), 0);
    }

    @Test
    void anEnemyWeHitRarelyGetsFasterBulletsInADuelOnly() {
        BulletPower power = new BulletPower();
        for (int i = 0; i < 29; i++) {
            power.ended(ENEMY, i % 10 == 0);
        }
        assertFalse(power.hitsBelow(ENEMY, BulletPower.RARE_HIT_RATE), "too few bullets yet");
        power.ended(ENEMY, false);
        assertTrue(power.hitsBelow(ENEMY, BulletPower.RARE_HIT_RATE));
        assertEquals(BulletPower.RARE_HIT_POWER, power.power(ENEMY, 400, 100, 100, true, 0.1, 2), 0);
        assertEquals(BulletPower.DEFAULT, power.power(ENEMY, 400, 100, 100, false, 0.1, 2), 0);
    }

    @Test
    void theRaceDecidesInADuelBelowTheRaceEnergy() {
        BulletPower power = new BulletPower();
        for (int i = 0; i < 10; i++) {
            power.ended(ENEMY, i < 3);
        }
        assertTrue(BulletPower.racing(15, 100));
        assertTrue(BulletPower.racing(35, 15));
        assertFalse(BulletPower.racing(45, 15));
        double race = RacePower.choose((3 + 1.5) / (10 + 10), 0.05, 3, BulletPower.DEFAULT);
        assertEquals(race, power.power(ENEMY, 400, 100, 15, true, 0.05, 3), 0);
        assertEquals(BulletPower.DEFAULT, power.power(ENEMY, 400, 100, 15, false, 0.05, 3), 0);
    }
}
