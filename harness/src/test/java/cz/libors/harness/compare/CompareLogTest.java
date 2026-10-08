package cz.libors.harness.compare;

import cz.libors.harness.compare.CompareLog.Battle;
import cz.libors.harness.compare.CompareLog.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompareLogTest {

    private static final Map<String, String> CONFIG = Map.of("baseline", "cz.libors.Ogi abc1234");

    private static Battle battle(String opponent, int pair, Side side, double share) {
        return new Battle(opponent, pair, side, -42L * pair, share, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                16, 17, 18, 19);
    }

    @Test
    void resumesWithCompletePairsOnly(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("battles.tsv");
        Battle a0 = battle("sample.Walls", 0, Side.BASELINE, 97.12345678901234);
        Battle b0 = battle("sample.Walls", 0, Side.CANDIDATE, 1.0 / 3);
        Battle b1 = battle("sample.Walls", 1, Side.CANDIDATE, 50);
        try (CompareLog log = CompareLog.open(file, CONFIG)) {
            log.append(a0);
            log.append(b0);
            log.append(b1);
        }
        try (CompareLog log = CompareLog.open(file, CONFIG)) {
            assertEquals(List.of(a0, b0, b1), log.battles());
            Map<String, List<Battle[]>> pairs = log.completePairs();
            assertEquals(1, pairs.get("sample.Walls").size());
            assertEquals(b0, pairs.get("sample.Walls").get(0)[Side.CANDIDATE.ordinal()]);
        }
    }

    @Test
    void dropsALineCutOffByACrash(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("battles.tsv");
        Battle a0 = battle("sample.Walls", 0, Side.BASELINE, 90);
        try (CompareLog log = CompareLog.open(file, CONFIG)) {
            log.append(a0);
        }
        Files.writeString(file, "battle\tsample.Wal", StandardOpenOption.APPEND);
        Battle b0 = battle("sample.Walls", 0, Side.CANDIDATE, 91);
        try (CompareLog log = CompareLog.open(file, CONFIG)) {
            assertEquals(List.of(a0), log.battles());
            log.append(b0);
        }
        try (CompareLog log = CompareLog.open(file, CONFIG)) {
            assertEquals(1, log.completePairs().get("sample.Walls").size());
        }
    }

    @Test
    void refusesAnotherConfiguration(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("battles.tsv");
        CompareLog.open(file, CONFIG).close();
        assertThrows(IOException.class, () -> CompareLog.open(file, Map.of("baseline", "cz.libors.Ogi def5678")));
    }
}
