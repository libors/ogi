package cz.libors.harness.compare;

import org.junit.jupiter.api.Test;

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
}
