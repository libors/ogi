package cz.libors.ogi.gun;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Past shots at one enemy: their features and the guess factors that would have hit. A new shot takes the guess
 * factor that the most weight among its nearest neighbours would have hit with. With a size limit only the latest
 * shots are kept.
 *
 * <p>Brute force over primitive arrays: the reference implementation a faster search must agree with.
 */
final class GunData {

    private final int dims = TargetingFeatures.COUNT;
    private final double[] weights;
    private final double neighbourScale;
    private final int maxNeighbours;
    /** At most this many shots are kept, the latest; 0 keeps all. */
    private final int maxSize;
    private double[] features = new double[1024 * dims];
    private double[] low = new double[1024];
    private double[] high = new double[1024];
    private double[] weight = new double[1024];
    private int size;

    private final int[] nearest;
    private final double[] nearestDistance;
    private final double[] eventAt;
    private final double[] eventWeight;

    GunData(GunParameters parameters) {
        this(parameters, 0);
    }

    GunData(GunParameters parameters, int maxSize) {
        this.maxSize = maxSize;
        weights = parameters.weights();
        neighbourScale = parameters.neighbourScale();
        maxNeighbours = parameters.maxNeighbours();
        nearest = new int[maxNeighbours];
        nearestDistance = new double[maxNeighbours];
        eventAt = new double[2 * maxNeighbours];
        eventWeight = new double[2 * maxNeighbours];
    }

    int size() {
        return size;
    }

    /** A shot whose bullets would have hit between the two guess factors. */
    void add(double[] f, double gfLow, double gfHigh, double w) {
        if (maxSize > 0 && size == maxSize) {
            System.arraycopy(features, dims, features, 0, (size - 1) * dims);
            System.arraycopy(low, 1, low, 0, size - 1);
            System.arraycopy(high, 1, high, 0, size - 1);
            System.arraycopy(weight, 1, weight, 0, size - 1);
            size--;
        }
        if (size == low.length) {
            int capacity = 2 * size;
            features = Arrays.copyOf(features, capacity * dims);
            low = Arrays.copyOf(low, capacity);
            high = Arrays.copyOf(high, capacity);
            weight = Arrays.copyOf(weight, capacity);
        }
        System.arraycopy(f, 0, features, size * dims, dims);
        low[size] = gfLow;
        high[size] = gfHigh;
        weight[size] = w;
        size++;
    }

    /** The number of neighbours a query uses: about the square root of the data, scaled, at least 1. */
    int neighbours() {
        return (int) Math.max(1, Math.min(maxNeighbours, Math.round(neighbourScale * Math.sqrt(size))));
    }

    /**
     * The guess factor covered by the most kernel weight among the nearest shots: each neighbour contributes its
     * weight, scaled down with its distance, over its whole hitting range. NaN without data.
     */
    double bestGuessFactor(double[] query) {
        if (size == 0) {
            return Double.NaN;
        }
        int n = events(query, neighbours(), eventAt, eventWeight, 0);
        return densest(eventAt, eventWeight, n);
    }

    /**
     * Writes the hitting ranges of the {@code k} nearest shots, as kernel-weighted start and end events, into the
     * arrays from {@code from} on; returns the index after the last event written.
     */
    int events(double[] query, int k, double[] at, double[] w, int from) {
        if (size == 0) {
            return from;
        }
        int found = findNearest(query, Math.min(k, maxNeighbours));
        double bandwidth = nearestDistance[found - 1] + 1e-12;
        for (int i = 0; i < found; i++) {
            int p = nearest[i];
            double kernel = weight[p] * Math.exp(-nearestDistance[i] / bandwidth);
            at[from + 2 * i] = low[p];
            w[from + 2 * i] = kernel;
            at[from + 2 * i + 1] = high[p];
            w[from + 2 * i + 1] = -kernel;
        }
        return from + 2 * found;
    }

    /** The middle of the stretch covered by the most weight among {@code n} events; sorts them. */
    static double densest(double[] at, double[] w, int n) {
        sortEvents(at, w, n);
        double sum = 0;
        double best = Double.NEGATIVE_INFINITY;
        double bestFrom = 0;
        double bestTo = 0;
        for (int i = 0; i < n; i++) {
            sum += w[i];
            if (w[i] > 0 && sum > best) {
                best = sum;
                bestFrom = at[i];
                bestTo = i + 1 < n ? at[i + 1] : at[i];
            }
        }
        return (bestFrom + bestTo) / 2;
    }

    /**
     * The peaks among {@code n} events: guess factors where the summed weight reaches a local maximum of at least
     * {@code minShare} of the highest, as {gf, share of the highest}, the highest first, at most {@code max}, none
     * closer than {@code spacing} to one listed before; sorts the events.
     */
    static double[][] peaks(double[] at, double[] w, int n, double minShare, int max, double spacing) {
        sortEvents(at, w, n);
        double[] middle = new double[n];
        double[] value = new double[n];
        int m = 0;
        double sum = 0;
        double best = 0;
        for (int i = 0; i + 1 < n; i++) {
            sum += w[i];
            if (at[i + 1] > at[i]) {
                middle[m] = (at[i] + at[i + 1]) / 2;
                value[m] = sum;
                best = Math.max(best, sum);
                m++;
            }
        }
        List<double[]> peaks = new ArrayList<>();
        for (int i = 0; i < m && best > 0; i++) {
            if (value[i] >= minShare * best && (i == 0 || value[i] > value[i - 1])
                    && (i == m - 1 || value[i] >= value[i + 1])) {
                peaks.add(new double[] {middle[i], value[i] / best});
            }
        }
        peaks.sort((a, b) -> Double.compare(b[1], a[1]));
        List<double[]> chosen = new ArrayList<>();
        for (double[] p : peaks) {
            boolean apart = true;
            for (double[] c : chosen) {
                apart &= Math.abs(c[0] - p[0]) >= spacing;
            }
            if (apart && chosen.size() < max) {
                chosen.add(p);
            }
        }
        return chosen.toArray(new double[0][]);
    }

    /** Fills {@link #nearest} with the {@code k} closest shots, closest first; returns how many there are. */
    private int findNearest(double[] query, int k) {
        int found = 0;
        for (int p = 0; p < size; p++) {
            double d = distance(query, p);
            if (found < k) {
                insert(found++, p, d);
            } else if (d < nearestDistance[found - 1]) {
                insert(found - 1, p, d);
            }
        }
        return found;
    }

    /** Puts a shot into the sorted list at or before {@code slot}, dropping what was in {@code slot}. */
    private void insert(int slot, int p, double d) {
        int i = slot;
        while (i > 0 && nearestDistance[i - 1] > d) {
            nearest[i] = nearest[i - 1];
            nearestDistance[i] = nearestDistance[i - 1];
            i--;
        }
        nearest[i] = p;
        nearestDistance[i] = d;
    }

    private double distance(double[] query, int p) {
        double sum = 0;
        int base = p * dims;
        for (int j = 0; j < dims; j++) {
            double d = (query[j] - features[base + j]) * weights[j];
            sum += d * d;
        }
        return sum;
    }

    /** Insertion sort by position; at equal positions, starts before ends, so touching ranges overlap. */
    private static void sortEvents(double[] at, double[] w, int n) {
        for (int i = 1; i < n; i++) {
            double a = at[i];
            double v = w[i];
            int j = i - 1;
            while (j >= 0 && (at[j] > a || (at[j] == a && w[j] < 0 && v > 0))) {
                at[j + 1] = at[j];
                w[j + 1] = w[j];
                j--;
            }
            at[j + 1] = a;
            w[j + 1] = v;
        }
    }
}
