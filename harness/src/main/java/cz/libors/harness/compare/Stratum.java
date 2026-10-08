package cz.libors.harness.compare;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Paired battles against one opponent. A pair is the baseline's and the candidate's score share, in points (0–100),
 * from two battles on the same seed.
 */
final class Stratum {

    /** What a statistic is taken of: either version's score share, or the candidate minus the baseline. */
    enum Metric {
        BASELINE(p -> p[0]),
        CANDIDATE(p -> p[1]),
        DIFFERENCE(p -> p[1] - p[0]);

        private final ToDoubleFunction<double[]> value;

        Metric(ToDoubleFunction<double[]> value) {
            this.value = value;
        }
    }

    private final String opponent;
    private final boolean strong;
    private final List<double[]> pairs = new ArrayList<>();

    Stratum(String opponent, boolean strong) {
        this.opponent = opponent;
        this.strong = strong;
    }

    String opponent() {
        return opponent;
    }

    boolean strong() {
        return strong;
    }

    void add(double baseline, double candidate) {
        pairs.add(new double[] {baseline, candidate});
    }

    int size() {
        return pairs.size();
    }

    /** Mean of the metric, or NaN without pairs. */
    double mean(Metric metric) {
        return pairs.stream().mapToDouble(metric.value).average().orElse(Double.NaN);
    }

    /** Sample variance (n − 1) of the metric, or NaN below two pairs. */
    double variance(Metric metric) {
        return covariance(metric, metric);
    }

    /** Correlation of the baseline's and the candidate's score share, or NaN if either does not vary. */
    double correlation() {
        double va = variance(Metric.BASELINE);
        double vb = variance(Metric.CANDIDATE);
        return va > 0 && vb > 0 ? covariance(Metric.BASELINE, Metric.CANDIDATE) / Math.sqrt(va * vb) : Double.NaN;
    }

    /** Share of battles won (score share above 50; a tie counts half), or NaN without pairs. */
    double wins(Metric metric) {
        return pairs.stream().mapToDouble(p -> {
            double share = metric.value.applyAsDouble(p);
            return share > 50 ? 1 : share == 50 ? 0.5 : 0;
        }).average().orElse(Double.NaN);
    }

    private double covariance(Metric x, Metric y) {
        int n = pairs.size();
        if (n < 2) {
            return Double.NaN;
        }
        double mx = mean(x);
        double my = mean(y);
        double sum = 0;
        for (double[] p : pairs) {
            sum += (x.value.applyAsDouble(p) - mx) * (y.value.applyAsDouble(p) - my);
        }
        return sum / (n - 1);
    }
}
