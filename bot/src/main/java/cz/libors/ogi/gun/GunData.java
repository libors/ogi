package cz.libors.ogi.gun;

import java.util.Arrays;

/**
 * Past shots at one enemy: their features and the guess factors that would have hit. A new shot takes the guess
 * factor that the most weight among its nearest neighbours would have hit with.
 *
 * <p>Brute force over primitive arrays: the reference implementation a faster search must agree with.
 */
final class GunData {

    static final int MAX_NEIGHBOURS = 50;

    private final int dims = TargetingFeatures.COUNT;
    private double[] features = new double[1024 * dims];
    private double[] low = new double[1024];
    private double[] high = new double[1024];
    private double[] weight = new double[1024];
    private int size;

    private final int[] nearest = new int[MAX_NEIGHBOURS];
    private final double[] nearestDistance = new double[MAX_NEIGHBOURS];
    private final double[] eventAt = new double[2 * MAX_NEIGHBOURS];
    private final double[] eventWeight = new double[2 * MAX_NEIGHBOURS];

    int size() {
        return size;
    }

    /** A shot whose bullets would have hit between the two guess factors. */
    void add(double[] f, double gfLow, double gfHigh, double w) {
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

    /** The number of neighbours a query uses: about the square root of the data, at least 1. */
    int neighbours() {
        return (int) Math.max(1, Math.min(MAX_NEIGHBOURS, Math.round(Math.sqrt(size))));
    }

    /**
     * The guess factor covered by the most kernel weight among the nearest shots: each neighbour contributes its
     * weight, scaled down with its distance, over its whole hitting range. NaN without data.
     */
    double bestGuessFactor(double[] query) {
        if (size == 0) {
            return Double.NaN;
        }
        int k = neighbours();
        int found = findNearest(query, k);
        double bandwidth = nearestDistance[found - 1] + 1e-12;
        for (int i = 0; i < found; i++) {
            int p = nearest[i];
            double w = weight[p] * Math.exp(-nearestDistance[i] / bandwidth);
            eventAt[2 * i] = low[p];
            eventWeight[2 * i] = w;
            eventAt[2 * i + 1] = high[p];
            eventWeight[2 * i + 1] = -w;
        }
        sortEvents(2 * found);
        double sum = 0;
        double best = Double.NEGATIVE_INFINITY;
        double bestFrom = 0;
        double bestTo = 0;
        for (int i = 0; i < 2 * found; i++) {
            sum += eventWeight[i];
            if (eventWeight[i] > 0 && sum > best) {
                best = sum;
                bestFrom = eventAt[i];
                bestTo = i + 1 < 2 * found ? eventAt[i + 1] : eventAt[i];
            }
        }
        return (bestFrom + bestTo) / 2;
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
            double d = (query[j] - features[base + j]) * TargetingFeatures.WEIGHTS[j];
            sum += d * d;
        }
        return sum;
    }

    /** Insertion sort by position; at equal positions, starts before ends, so touching ranges overlap. */
    private void sortEvents(int n) {
        for (int i = 1; i < n; i++) {
            double at = eventAt[i];
            double w = eventWeight[i];
            int j = i - 1;
            while (j >= 0 && (eventAt[j] > at || (eventAt[j] == at && eventWeight[j] < 0 && w > 0))) {
                eventAt[j + 1] = eventAt[j];
                eventWeight[j + 1] = eventWeight[j];
                j--;
            }
            eventAt[j + 1] = at;
            eventWeight[j + 1] = w;
        }
    }
}
