package cz.libors.harness.compare;

import cz.libors.harness.compare.CompareLog.Battle;
import cz.libors.harness.compare.CompareLog.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompareTest {

    /** Two opponents in group a, one in group b; each opponent's pairs are (baseline, candidate). */
    private static List<Stratum> strata() {
        Stratum a1 = new Stratum("a1", false, "a");
        a1.add(50, 52);
        a1.add(50, 54);
        Stratum a2 = new Stratum("a2", false, "a");
        a2.add(40, 40);
        a2.add(40, 42);
        Stratum b1 = new Stratum("b1", false, "b");
        b1.add(80, 80);
        b1.add(80, 81);
        return List.of(a1, a2, b1);
    }

    private static List<Compare.OpponentRow> rows(List<Stratum> strata) {
        return strata.stream().map(s -> new Compare.OpponentRow(s.opponent(), false, s.size(), 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, s.opponent().startsWith("a") ? 20 : 50, 0, 0, 0)).toList();
    }

    @Test
    void weightsAsTheGroupsSizesGiveTheEqualWeightEstimate() {
        List<Stratum> strata = strata();
        Compare.Scoreboard weighted = Compare.weighted("rumble", strata, rows(strata), Map.of("a", 2.0, "b", 1.0));
        Sprt.Estimate all = Sprt.estimate(strata, Stratum.Metric.DIFFERENCE, s -> true);
        assertEquals(all.mean(), weighted.difference(), 1e-12);
        assertEquals(all.halfWidth(), weighted.halfWidth(), 1e-12);
        assertEquals(30, weighted.hitRateBaseline(), 1e-12);
    }

    @Test
    void groupsWeighAsTheRobotsTheyStandFor() {
        List<Stratum> strata = strata();
        Compare.Scoreboard weighted = Compare.weighted("rumble", strata, rows(strata), Map.of("a", 1.0, "b", 3.0));
        Sprt.Estimate a = Sprt.estimate(strata, Stratum.Metric.DIFFERENCE, s -> s.group().equals("a"));
        Sprt.Estimate b = Sprt.estimate(strata, Stratum.Metric.DIFFERENCE, s -> s.group().equals("b"));
        // Group a's mean difference is 2, group b's 0.5.
        assertEquals(0.25 * 2 + 0.75 * 0.5, weighted.difference(), 1e-12);
        assertEquals(1.96 * Math.sqrt(0.25 * 0.25 * a.variance() + 0.75 * 0.75 * b.variance()), weighted.halfWidth(),
                1e-12);
        assertEquals(0.25 * 20 + 0.75 * 50, weighted.hitRateBaseline(), 1e-12);
        assertEquals(3, weighted.opponents());
    }

    private static Battle battle(String opponent, int pair, Side side, double share, int fired, int hits,
                                 int opponentFired, int opponentHits) {
        return new Battle(opponent, pair, side, 1, share, (int) share, 100 - (int) share, 0, 0, 0, 0, 0, 0, 0, 0,
                fired, hits, opponentFired, opponentHits, 0, 0, 0, 0, 1000);
    }

    @Test
    void theScoreboardsAveragePerOpponentFromTheLogsPairs(@TempDir Path tmp) throws IOException {
        CompareLog log = CompareLog.open(tmp.resolve("battles.tsv"), Map.of("test", "1"));
        // Opponent x: shares 40 and a tie for the baseline, 60 and 70 for the candidate; y fires nothing at all.
        log.append(battle("x", 0, Side.BASELINE, 40, 10, 2, 20, 5));
        log.append(battle("x", 0, Side.CANDIDATE, 60, 10, 4, 20, 2));
        log.append(battle("x", 1, Side.BASELINE, 50, 10, 3, 20, 5));
        log.append(battle("x", 1, Side.CANDIDATE, 70, 10, 5, 20, 2));
        log.append(battle("y", 0, Side.BASELINE, 80, 0, 0, 0, 0));
        log.append(battle("y", 0, Side.CANDIDATE, 70, 0, 0, 0, 0));
        log.append(battle("y", 1, Side.BASELINE, 90, 0, 0, 0, 0));
        log.append(battle("y", 1, Side.CANDIDATE, 80, 0, 0, 0, 0));
        // A pair with one battle only stays out.
        log.append(battle("y", 2, Side.BASELINE, 10, 0, 0, 0, 0));
        Stratum x = new Stratum("x", true, "strong");
        x.add(40, 60);
        x.add(50, 70);
        Stratum y = new Stratum("y", false, "other");
        y.add(80, 70);
        y.add(90, 80);
        Compare.Summary summary = Compare.summarize(Map.of(), Map.of(), "score", Sprt.Bounds.parse("gainer"),
                List.of(x, y), Map.of(), log, 2);
        Compare.Scoreboard all = summary.scoreboards().get(0);
        assertEquals("all", all.name());
        assertEquals(65, all.apsBaseline(), 1e-12);
        assertEquals(70, all.apsCandidate(), 1e-12);
        assertEquals((25 + 100) / 2.0, all.pwinBaseline(), 1e-12, "a tie counts half a win");
        assertEquals(100, all.pwinCandidate(), 1e-12);
        // Hit rates pool the bullets of an opponent's pairs; an opponent without bullets has none and stays out.
        assertEquals(25, all.hitRateBaseline(), 1e-12);
        assertEquals(45, all.hitRateCandidate(), 1e-12);
        assertEquals(25, all.hitByBaseline(), 1e-12);
        assertEquals(10, all.hitByCandidate(), 1e-12);
        Compare.Scoreboard strong = summary.scoreboards().get(1);
        assertEquals("strong", strong.name());
        assertEquals(45, strong.apsBaseline(), 1e-12);
        assertEquals(20, strong.difference(), 1e-12);
    }
}
