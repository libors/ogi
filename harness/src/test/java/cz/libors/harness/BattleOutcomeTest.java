package cz.libors.harness;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BattleOutcomeTest {

    private static ContestantResult result(String name, int engineRank, int score) {
        return new ContestantResult(name, engineRank, score, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void placeIsByScoreNotByTheEngineRank() {
        // The engine's rank is the setup position: the subject listed first has rank 1 whatever it scored.
        BattleOutcome outcome = new BattleOutcome(null, 1,
                List.of(result("sample.Walls", 3, 900), result("sample.SpinBot", 2, 500),
                        result("cz.libors.Ogi 1", 1, 100)),
                Map.of(), List.of(), 0, Map.of(), 0, Map.of());
        assertEquals(3, outcome.placeByScore("cz.libors."));
        assertEquals(1, outcome.placeByScore("sample.Walls"));
        assertEquals(Integer.MAX_VALUE, outcome.placeByScore("sample.Crazy"));
    }
}
