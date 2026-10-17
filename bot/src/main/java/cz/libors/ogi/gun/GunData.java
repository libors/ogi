package cz.libors.ogi.gun;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Past shots at one enemy: their features and the guess factors that would have hit. A new shot takes the guess
 * factor that the most weight among its nearest neighbours would have hit with. With a size limit only the latest
 * shots are kept.
 *
 * <p>Data that keeps every shot is searched with a {@link KdTree}; with a size limit (a few hundred shots at most) a
 * scan finds the same neighbours. Both rank by distance, then by the order the shots came in.
 */
final class GunData {

    private final int dims = TargetingFeatures.COUNT;
    private final double[] weights;
    /** The features whose weight is not 0. */
    private final int[] used;
    private final double neighbourScale;
    private final int maxNeighbours;
    /** At most this many shots are kept, the latest; 0 keeps all. */
    private final int maxSize;
    private double[] features = new double[1024 * dims];
    private double[] low = new double[1024];
    private double[] high = new double[1024];
    private double[] weight = new double[1024];
    private int size;
    /** Over all shots when there is no size limit, else null. */
    private final KdTree tree;

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
        used = KdTree.usedFeatures(weights);
        neighbourScale = parameters.neighbourScale();
        maxNeighbours = parameters.maxNeighbours();
        nearest = new int[maxNeighbours];
        nearestDistance = new double[maxNeighbours];
        eventAt = new double[2 * maxNeighbours];
        eventWeight = new double[2 * maxNeighbours];
        tree = maxSize == 0 ? new KdTree(dims, weights) : null;
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
        if (tree != null) {
            tree.add(features, size - 1);
        }
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
     * The kernel weight of the nearest shots that covers each of {@code bins} guess factors spread evenly from −1 to
     * 1: the sum {@link #bestGuessFactor} takes the highest of, for the overlay. Null without data.
     */
    double[] coverage(double[] query, int bins) {
        if (size == 0) {
            return null;
        }
        double[] at = new double[2 * maxNeighbours];
        double[] w = new double[2 * maxNeighbours];
        int n = events(query, neighbours(), at, w, 0);
        double[] sums = new double[bins];
        for (int b = 0; b < bins; b++) {
            double gf = -1 + 2.0 * b / (bins - 1);
            for (int i = 0; i + 1 < n; i += 2) {
                if (at[i] <= gf && gf <= at[i + 1]) {
                    sums[b] += w[i];
                }
            }
        }
        return sums;
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
        return tree != null ? tree.nearest(features, query, k, nearest, nearestDistance) : scan(query, k);
    }

    /** The k nearest shots by looking at every one; equally far shots stay in the order they came. */
    private int scan(double[] query, int k) {
        int found = 0;
        int p = 0;
        for (; p < size && found < k; p++) {
            insert(found++, p, KdTree.distance(features, dims, p, query, weights, used));
        }
        if (found == 0) {
            return 0;
        }
        double worst = nearestDistance[found - 1];
        for (; p < size; p++) {
            // The terms are not negative, so a shot is out as soon as its partial sum reaches the k-th distance; a
            // completed sum is the same number as the full distance.
            int base = p * dims;
            double sum = 0;
            int i = 0;
            for (; i < used.length; i++) {
                int j = used[i];
                double d = (query[j] - features[base + j]) * weights[j];
                sum += d * d;
                if (sum >= worst) {
                    break;
                }
            }
            if (i == used.length) {
                insert(found - 1, p, sum);
                worst = nearestDistance[found - 1];
            }
        }
        return found;
    }

    /** The indices of the {@code k} (at most the maximum neighbours) nearest shots, nearest first. */
    int[] nearestShots(double[] query, int k) {
        return Arrays.copyOf(nearest, findNearest(query, k));
    }

    /** The same, found by a scan of every shot whatever the data's search. */
    int[] scannedShots(double[] query, int k) {
        return Arrays.copyOf(nearest, scan(query, k));
    }

    /** Puts a shot into the sorted list at or before {@code slot}, after equally far ones, dropping what was there. */
    private void insert(int slot, int p, double d) {
        int lo = 0;
        int hi = slot;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (nearestDistance[mid] > d) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        System.arraycopy(nearest, lo, nearest, lo + 1, slot - lo);
        System.arraycopy(nearestDistance, lo, nearestDistance, lo + 1, slot - lo);
        nearest[lo] = p;
        nearestDistance[lo] = d;
    }

    /**
     * Stable merge sort by position; at equal positions, starts before ends, so touching ranges overlap. Kernels are
     * never 0, so every event is a start or an end and the order is a strict weak one: the same order an insertion
     * sort gives.
     */
    static void sortEvents(double[] at, double[] w, int n) {
        double[] fromAt = at;
        double[] fromW = w;
        double[] toAt = new double[n];
        double[] toW = new double[n];
        for (int width = 1; width < n; width *= 2) {
            for (int lo = 0; lo < n; lo += 2 * width) {
                int mid = Math.min(lo + width, n);
                int hi = Math.min(lo + 2 * width, n);
                int i = lo;
                int j = mid;
                for (int k = lo; k < hi; k++) {
                    // The right one goes first only if it must, so equal ones keep their order.
                    if (j < hi && (i >= mid || before(fromAt[j], fromW[j], fromAt[i], fromW[i]))) {
                        toAt[k] = fromAt[j];
                        toW[k] = fromW[j++];
                    } else {
                        toAt[k] = fromAt[i];
                        toW[k] = fromW[i++];
                    }
                }
            }
            double[] t = fromAt;
            fromAt = toAt;
            toAt = t;
            t = fromW;
            fromW = toW;
            toW = t;
        }
        if (fromAt != at) {
            System.arraycopy(fromAt, 0, at, 0, n);
            System.arraycopy(fromW, 0, w, 0, n);
        }
    }

    /** Whether the event at {@code a} with weight {@code v} goes before the one at {@code b} with weight {@code u}. */
    private static boolean before(double a, double v, double b, double u) {
        return a < b || (a == b && v > 0 && u < 0);
    }
}
