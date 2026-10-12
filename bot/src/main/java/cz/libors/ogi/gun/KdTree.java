package cz.libors.ogi.gun;

import java.util.Arrays;

/**
 * A bucket kd-tree over points that live in a flat array elsewhere ({@code dims} values per point, point {@code p} at
 * {@code p · dims}), for the k nearest by a weighted squared Euclidean distance. Leaves hold up to {@link #BUCKET}
 * points and split at the middle of their widest weighted spread.
 *
 * <p>Exact: a search finds the same points, in the same order, as a scan that sorts all points by distance and then
 * by index. A point's distance is summed in feature order ({@link #distance}); a node's nearest possible distance
 * sums the gaps from the query to the node's bounds the same way, and each gap is no larger than the point's own
 * difference, so with rounding that only grows with its arguments the bound is never above a distance inside. A node
 * is skipped only when its bound is above the k-th distance found.
 */
final class KdTree {

    static final int BUCKET = 32;

    private final int dims;
    private final double[] weights;
    /** The features whose weight is not 0: the others add nothing to a distance. */
    private final int[] used;

    private int nodes;
    /** Per node: its children, -1 for a leaf. */
    private int[] left = new int[64];
    private int[] right = new int[64];
    private int[] splitDim = new int[64];
    private double[] splitValue = new double[64];
    /** Per node and feature: the lowest and highest value of the node's points. */
    private double[] low;
    private double[] high;
    /** Per leaf: its points' indices. */
    private int[][] bucket = new int[64][];
    private int[] count = new int[64];

    private int[] nearest;
    private double[] nearestDistance;
    private int found;

    KdTree(int dims, double[] weights) {
        this.dims = dims;
        this.weights = weights.clone();
        used = usedFeatures(weights);
        low = new double[64 * dims];
        high = new double[64 * dims];
        newNode();
    }

    /** The indices of the features with a weight other than 0. */
    static int[] usedFeatures(double[] weights) {
        int n = 0;
        int[] used = new int[weights.length];
        for (int j = 0; j < weights.length; j++) {
            if (weights[j] != 0) {
                used[n++] = j;
            }
        }
        return Arrays.copyOf(used, n);
    }

    /** The squared weighted distance from the query to point {@code p}, summed in feature order. */
    static double distance(double[] points, int dims, int p, double[] query, double[] weights, int[] used) {
        double sum = 0;
        int base = p * dims;
        for (int j : used) {
            double d = (query[j] - points[base + j]) * weights[j];
            sum += d * d;
        }
        return sum;
    }

    /** Adds point {@code p}, whose values are already in {@code points}. */
    void add(double[] points, int p) {
        int n = 0;
        while (true) {
            extend(n, points, p);
            if (left[n] < 0) {
                break;
            }
            n = points[p * dims + splitDim[n]] < splitValue[n] ? left[n] : right[n];
        }
        append(n, p);
        if (count[n] > BUCKET) {
            split(n, points);
        }
    }

    /**
     * Writes the {@code k} nearest points into {@code nearest} and their distances into {@code distances}, nearest
     * first, equally far ones by index; returns how many there are.
     */
    int nearest(double[] points, double[] query, int k, int[] nearest, double[] distances) {
        this.nearest = nearest;
        this.nearestDistance = distances;
        found = 0;
        if (k > 0) {
            search(0, points, query, k);
        }
        this.nearest = null;
        this.nearestDistance = null;
        return found;
    }

    private void search(int n, double[] points, double[] query, int k) {
        if (left[n] < 0) {
            int[] members = bucket[n];
            for (int i = 0; i < count[n]; i++) {
                int p = members[i];
                offer(p, distance(points, dims, p, query, weights, used), k);
            }
            return;
        }
        int near = query[splitDim[n]] < splitValue[n] ? left[n] : right[n];
        int far = near == left[n] ? right[n] : left[n];
        if (found < k || bound(near, query) <= nearestDistance[k - 1]) {
            search(near, points, query, k);
        }
        if (found < k || bound(far, query) <= nearestDistance[k - 1]) {
            search(far, points, query, k);
        }
    }

    /** Keeps point {@code p} if it is among the k nearest found so far, by distance and then by index. */
    private void offer(int p, double d, int k) {
        if (found == k) {
            double worst = nearestDistance[k - 1];
            if (d > worst || (d == worst && p > nearest[k - 1])) {
                return;
            }
        }
        int slot = found < k ? found++ : k - 1;
        int lo = 0;
        int hi = slot;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (nearestDistance[mid] > d || (nearestDistance[mid] == d && nearest[mid] > p)) {
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

    /** The nearest possible distance from the query to a point of node {@code n}. */
    private double bound(int n, double[] query) {
        int b = n * dims;
        double sum = 0;
        for (int j : used) {
            double q = query[j];
            double gap = q < low[b + j] ? q - low[b + j] : q > high[b + j] ? q - high[b + j] : 0;
            double d = gap * weights[j];
            sum += d * d;
        }
        return sum;
    }

    private void split(int n, double[] points) {
        int b = n * dims;
        int dim = -1;
        double widest = 0;
        for (int j : used) {
            double spread = (high[b + j] - low[b + j]) * weights[j];
            if (spread > widest) {
                widest = spread;
                dim = j;
            }
        }
        if (dim < 0) {
            // All its points are equal in every feature that counts: the leaf just grows.
            return;
        }
        double value = (low[b + dim] + high[b + dim]) / 2;
        if (!(value > low[b + dim])) {
            return;
        }
        int[] members = bucket[n];
        int c = count[n];
        int l = newNode();
        int r = newNode();
        left[n] = l;
        right[n] = r;
        splitDim[n] = dim;
        splitValue[n] = value;
        bucket[n] = null;
        count[n] = 0;
        for (int i = 0; i < c; i++) {
            int p = members[i];
            int to = points[p * dims + dim] < value ? l : r;
            extend(to, points, p);
            append(to, p);
        }
    }

    private void append(int n, int p) {
        if (count[n] == bucket[n].length) {
            bucket[n] = Arrays.copyOf(bucket[n], 2 * count[n]);
        }
        bucket[n][count[n]++] = p;
    }

    private void extend(int n, double[] points, int p) {
        int b = n * dims;
        int f = p * dims;
        for (int j = 0; j < dims; j++) {
            double v = points[f + j];
            if (v < low[b + j]) {
                low[b + j] = v;
            }
            if (v > high[b + j]) {
                high[b + j] = v;
            }
        }
    }

    private int newNode() {
        if (nodes == left.length) {
            int capacity = 2 * nodes;
            left = Arrays.copyOf(left, capacity);
            right = Arrays.copyOf(right, capacity);
            splitDim = Arrays.copyOf(splitDim, capacity);
            splitValue = Arrays.copyOf(splitValue, capacity);
            low = Arrays.copyOf(low, capacity * dims);
            high = Arrays.copyOf(high, capacity * dims);
            bucket = Arrays.copyOf(bucket, capacity);
            count = Arrays.copyOf(count, capacity);
        }
        int n = nodes++;
        left[n] = -1;
        right[n] = -1;
        bucket[n] = new int[BUCKET + 1];
        count[n] = 0;
        Arrays.fill(low, n * dims, (n + 1) * dims, Double.POSITIVE_INFINITY);
        Arrays.fill(high, n * dims, (n + 1) * dims, Double.NEGATIVE_INFINITY);
        return n;
    }
}
