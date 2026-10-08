package cz.libors.harness.compare;

import cz.libors.harness.compare.Sprt.Bounds;
import cz.libors.harness.compare.Sprt.Decision;
import cz.libors.harness.compare.Sprt.Estimate;
import cz.libors.harness.compare.Stratum.Metric;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SprtTest {

    private static final Bounds GAINER = Bounds.parse("gainer");

    @Test
    void boundsAreFishtestsForFivePercentErrors() {
        assertEquals(-2.944, GAINER.lower(), 1e-3);
        assertEquals(2.944, GAINER.upper(), 1e-3);
        assertEquals(new Bounds(-0.75, 0.25, 0.05, 0.05), Bounds.parse("nonregression"));
        assertEquals(new Bounds(-1, 2, 0.05, 0.05), Bounds.parse("-1, 2"));
    }

    @Test
    void llrFollowsTheNormalApproximation() {
        // (δ1 − δ0)(2φ̂ − δ0 − δ1) / (2V) = 0.5 · (0.6 − 0.5) / 0.02
        assertEquals(2.5, Sprt.llr(new Estimate(0.3, 0.01), GAINER), 1e-12);
        assertEquals(Double.NEGATIVE_INFINITY, Sprt.llr(new Estimate(0, 0), GAINER));
    }

    @Test
    void estimateWeighsOpponentsEquallyAndShrinksSmallStrata() {
        Stratum many = new Stratum("many", false);
        for (int i = 0; i < 6; i++) {
            many.add(50, 50 + (i % 2 == 0 ? 1 : 3)); // differences 1, 3, ...: mean 2, variance 1.2
        }
        Stratum few = new Stratum("few", true);
        few.add(80, 70);
        few.add(80, 74); // mean −8; too few pairs for its own variance
        List<Stratum> strata = List.of(many, few);

        double pooled = (5 * 1.2 + 1 * 8.0) / 6;
        assertEquals(pooled, Sprt.pooledVariance(strata, Metric.DIFFERENCE), 1e-12);
        Estimate e = Sprt.estimate(strata, Metric.DIFFERENCE, s -> true);
        assertEquals((2 - 8) / 2.0, e.mean(), 1e-12);
        assertEquals((1.2 / 6 + pooled / 2) / 4, e.variance(), 1e-12);
        Estimate strong = Sprt.estimate(strata, Metric.DIFFERENCE, Stratum::strong);
        assertEquals(-8, strong.mean(), 1e-12);
        assertEquals(pooled / 2, strong.variance(), 1e-12);
    }

    @Test
    void noDecisionDuringTheBurnIn() {
        Stratum a = new Stratum("a", false);
        Stratum b = new Stratum("b", false);
        for (int i = 0; i < 50; i++) {
            a.add(50, 40 + i % 3);
        }
        b.add(50, 40);
        assertEquals(Decision.CONTINUE, Sprt.decide(List.of(a, b), GAINER));
        assertEquals(1, Sprt.next(List.of(a, b)));
        b.add(50, 41);
        assertEquals(Decision.H0, Sprt.decide(List.of(a, b), GAINER));
    }

    @Test
    void allocationFollowsTheStandardDeviations() {
        Random random = new Random(1);
        double[] sd = {1, 4, 0};
        List<Stratum> strata = List.of(new Stratum("calm", false), new Stratum("noisy", false),
                new Stratum("exact", false));
        for (int i = 0; i < 1000; i++) {
            int h = Sprt.next(strata);
            strata.get(h).add(50, 50 + sd[h] * random.nextGaussian());
        }
        // Neyman: n_h ∝ s_h, so about 200 and 800, and nothing more where every pair agreed once its own variance
        // counts.
        assertEquals(Sprt.OWN_VARIANCE_PAIRS, strata.get(2).size());
        assertEquals(4.0, strata.get(1).size() / (double) strata.get(0).size(), 0.5);
    }

    /** Simulated tests at the hypotheses: error rates and lengths as the SPRT theory predicts. */
    @Test
    void simulatedTestsMeetTheErrorRatesAndExpectedLengths() {
        double sigma = 3;
        for (double trueMean : new double[] {GAINER.delta0(), GAINER.delta1()}) {
            Random random = new Random(7);
            int runs = 400;
            int acceptedH1 = 0;
            long pairs = 0;
            for (int run = 0; run < runs; run++) {
                List<Stratum> strata = List.of(new Stratum("only", false));
                Decision d;
                while ((d = Sprt.decide(strata, GAINER)) == Decision.CONTINUE) {
                    strata.get(0).add(50, 50 + trueMean + sigma * random.nextGaussian());
                }
                acceptedH1 += d == Decision.H1 ? 1 : 0;
                pairs += strata.get(0).size();
            }
            double errorRate = (trueMean == GAINER.delta0() ? acceptedH1 : runs - acceptedH1) / (double) runs;
            double meanPairs = pairs / (double) runs;
            double expected = Sprt.expectedPairs(sigma, GAINER, trueMean);
            assertEquals(191, expected, 1);
            assertTrue(errorRate <= 0.07, "error rate " + errorRate + " at Δ = " + trueMean);
            assertEquals(expected, meanPairs, 0.2 * expected, "mean pairs at Δ = " + trueMean);
        }
    }

    @Test
    void expectedLengthMidwayIsLongest() {
        assertEquals(8.67 / 5.30, Sprt.expectedPairs(3, GAINER, 0.25) / Sprt.expectedPairs(3, GAINER, 0), 0.01);
        List<Double> lengths = new ArrayList<>();
        for (double mean = -0.5; mean <= 1.0; mean += 0.25) {
            lengths.add(Sprt.expectedPairs(3, GAINER, mean));
        }
        assertEquals(lengths.get(3), lengths.stream().max(Double::compare).orElseThrow());
    }
}
