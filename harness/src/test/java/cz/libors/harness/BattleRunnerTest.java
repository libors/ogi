package cz.libors.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleRunnerTest {

    @Test
    void onlyTheEnginesLineIsASkippedTurn() {
        assertTrue(BattleRunner.skippedTurn("SYSTEM: Ogi skipped turn 43"));
        assertTrue(BattleRunner.skippedTurn("SYSTEM: kc.mega.BeepBoop skipped turn 1201"));
        assertTrue(BattleRunner.skippedTurn("SYSTEM: Ogi (2) skipped turn 7"));
        assertFalse(BattleRunner.skippedTurn("skipped turns: 0"));
        assertFalse(BattleRunner.skippedTurn("Round 3: skipped turn count 0, hits 12"));
        assertFalse(BattleRunner.skippedTurn("I skipped turn 5 on purpose"));
    }
}
