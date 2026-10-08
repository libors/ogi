package cz.libors.harness.compare;

import cz.libors.harness.compare.Stratum.Metric;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Stratified sequential test on paired score differences.
 *
 * <p>The estimate weighs every opponent equally, as the rumble's APS does: the mean difference is the average of
 * the per-opponent mean differences, and its variance is {@code (1/H²)·Σ s_h²/n_h}. The log-likelihood ratio is
 * fishtest's normal approximation of the generalized SPRT, {@code LLR = (δ1 − δ0)(2φ̂ − δ0 − δ1)/(2V)}, which holds
 * for any statistic with a variance estimate (eq. 2.1 of https://www.cantate.be/Fishtest/GSPRT_approximation.pdf).
 */
final class Sprt {

    /** Below this many pairs, an opponent's variance is replaced by the variance pooled over all opponents. */
    static final int OWN_VARIANCE_PAIRS = 5;

    /** Pairs every opponent gets before the allocation follows the variances and before any decision. */
    static final int BURN_IN_PAIRS = 2;

    /**
     * Degrees of freedom of the pooled variance before any decision. With fewer, a variance estimated too low by
     * chance makes the LLR jump past a bound after a handful of pairs.
     */
    static final int MIN_FREEDOM = 20;

    private Sprt() {
    }

    /**
     * Hypotheses about the candidate's gain in score-share points, H0: Δ = δ0 against H1: Δ = δ1, with error rates
     * α (accepting H1 when H0 holds) and β.
     */
    record Bounds(double delta0, double delta1, double alpha, double beta) {

        static final double DEFAULT_ERROR = 0.05;

        /** {@code gainer} [0, 0.5], {@code screening} [0, 1.5], {@code nonregression} [−0.75, 0.25], or "δ0,δ1". */
        static Bounds parse(String text) {
            return switch (text) {
                case "gainer" -> of(0, 0.5);
                case "screening" -> of(0, 1.5);
                case "nonregression" -> of(-0.75, 0.25);
                default -> {
                    String[] parts = text.split(",");
                    if (parts.length != 2) {
                        throw new IllegalArgumentException("bounds: gainer, screening, nonregression or δ0,δ1");
                    }
                    yield of(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
                }
            };
        }

        static Bounds of(double delta0, double delta1) {
            if (!(delta1 > delta0)) {
                throw new IllegalArgumentException("bounds need δ0 < δ1: " + delta0 + ", " + delta1);
            }
            return new Bounds(delta0, delta1, DEFAULT_ERROR, DEFAULT_ERROR);
        }

        /** LLR at or below which H0 is accepted. */
        double lower() {
            return Math.log(beta / (1 - alpha));
        }

        /** LLR at or above which H1 is accepted. */
        double upper() {
            return Math.log((1 - beta) / alpha);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "[%+.2f, %+.2f]", delta0, delta1);
        }
    }

    enum Decision {
        CONTINUE, H0, H1
    }

    /** A stratified estimate and its variance. */
    record Estimate(double mean, double variance) {

        /** Half width of the 95 % confidence interval. */
        double halfWidth() {
            return 1.96 * Math.sqrt(variance);
        }
    }

    /**
     * Equal-weight estimate of a metric over the given opponents, or NaN until each has a pair and some opponent has
     * two.
     */
    static Estimate estimate(List<Stratum> strata, Metric metric, Predicate<Stratum> include) {
        List<Stratum> selected = strata.stream().filter(include).toList();
        if (selected.isEmpty() || selected.stream().anyMatch(s -> s.size() == 0)) {
            return new Estimate(Double.NaN, Double.NaN);
        }
        double pooled = pooledVariance(strata, metric);
        double mean = 0;
        double variance = 0;
        for (Stratum s : selected) {
            mean += s.mean(metric);
            variance += variance(s, metric, pooled) / s.size();
        }
        int h = selected.size();
        return new Estimate(mean / h, variance / ((double) h * h));
    }

    static double llr(Estimate e, Bounds b) {
        double numerator = (b.delta1() - b.delta0()) * (2 * e.mean() - b.delta0() - b.delta1());
        if (e.variance() == 0) {
            // Every pair agreed exactly; only the sign of the evidence is left.
            return numerator == 0 ? 0 : Math.copySign(Double.POSITIVE_INFINITY, numerator);
        }
        return numerator / (2 * e.variance());
    }

    /** The decision after the burn-in, given the current estimate of the difference. */
    static Decision decide(List<Stratum> strata, Bounds b) {
        int freedom = strata.stream().mapToInt(s -> Math.max(s.size() - 1, 0)).sum();
        if (strata.stream().anyMatch(s -> s.size() < BURN_IN_PAIRS) || freedom < MIN_FREEDOM) {
            return Decision.CONTINUE;
        }
        double llr = llr(estimate(strata, Metric.DIFFERENCE, s -> true), b);
        if (llr >= b.upper()) {
            return Decision.H1;
        }
        return llr <= b.lower() ? Decision.H0 : Decision.CONTINUE;
    }

    /**
     * The opponent for the next pair: the first one below the burn-in, otherwise the one furthest below its Neyman
     * share, {@code n_h ∝ s_h} of the difference. Opponents where every pair agreed exactly get no more pairs.
     */
    static int next(List<Stratum> strata) {
        int best = -1;
        double bestRatio = Double.POSITIVE_INFINITY;
        for (int i = 0; i < strata.size(); i++) {
            if (strata.get(i).size() < BURN_IN_PAIRS) {
                return i;
            }
        }
        double pooled = pooledVariance(strata, Metric.DIFFERENCE);
        for (int i = 0; i < strata.size(); i++) {
            Stratum s = strata.get(i);
            double sd = Math.sqrt(variance(s, Metric.DIFFERENCE, pooled));
            double ratio = sd > 0 ? s.size() / sd : Double.POSITIVE_INFINITY;
            if (ratio < bestRatio) {
                best = i;
                bestRatio = ratio;
            }
        }
        if (best >= 0) {
            return best;
        }
        // No opponent shows any variance yet: spread evenly.
        for (int i = 0; i < strata.size(); i++) {
            if (best < 0 || strata.get(i).size() < strata.get(best).size()) {
                best = i;
            }
        }
        return best;
    }

    /**
     * Expected number of pairs until a decision when the true difference is {@code trueMean}, from the Brownian
     * approximation of the SPRT (https://www.cantate.be/Fishtest/sprta.pdf): {@code T(h)·σ²/(δ1 − δ0)²}. Valid for
     * α = β.
     *
     * @param sigma standard deviation of the estimate times the square root of the number of pairs
     */
    static double expectedPairs(double sigma, Bounds b, double trueMean) {
        double width = b.delta1() - b.delta0();
        double h = (2 * trueMean - b.delta0() - b.delta1()) / width;
        double limit = b.upper();
        double t = Math.abs(h) < 1e-9 ? limit * limit
                : 2 * limit / h * (1 - Math.exp(-h * limit)) / (1 + Math.exp(-h * limit));
        return t * sigma * sigma / (width * width);
    }

    /** Variance of one opponent's metric, shrunk to the pooled variance while it has few pairs. */
    static double variance(Stratum s, Metric metric, double pooled) {
        return s.size() < OWN_VARIANCE_PAIRS ? pooled : s.variance(metric);
    }

    /** Variance pooled over the opponents with two or more pairs, weighted by degrees of freedom. */
    static double pooledVariance(List<Stratum> strata, Metric metric) {
        double sum = 0;
        int freedom = 0;
        for (Stratum s : strata) {
            if (s.size() >= 2) {
                sum += (s.size() - 1) * s.variance(metric);
                freedom += s.size() - 1;
            }
        }
        return freedom > 0 ? sum / freedom : Double.NaN;
    }
}
