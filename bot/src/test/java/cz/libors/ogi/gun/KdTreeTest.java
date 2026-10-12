package cz.libors.ogi.gun;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KdTreeTest {

    private static final int DIMS = TargetingFeatures.COUNT;

    @Test
    void findsWhatSortingEveryPointFinds() {
        Random random = new Random(7);
        double[][] weightSets = {GunParameters.DEFAULT.weights(), randomWeights(random), uniformWeights()};
        for (double[] weights : weightSets) {
            double[] points = new double[3000 * DIMS];
            KdTree tree = new KdTree(DIMS, weights);
            for (int p = 0; p < 3000; p++) {
                fill(points, p, random);
                tree.add(points, p);
                if (p % 97 == 0 || p < 40) {
                    for (int q = 0; q < 5; q++) {
                        double[] query = query(points, p + 1, random);
                        for (int k : new int[] {1, 7, 40, 100}) {
                            assertSame(points, p + 1, query, k, weights, tree);
                        }
                    }
                }
            }
        }
    }

    @Test
    void ranksEquallyFarPointsByIndex() {
        double[] weights = GunParameters.DEFAULT.weights();
        double[] points = new double[600 * DIMS];
        KdTree tree = new KdTree(DIMS, weights);
        Random random = new Random(3);
        for (int p = 0; p < 600; p++) {
            // Few distinct points, so most distances tie, and leaves that cannot split.
            for (int j = 0; j < DIMS; j++) {
                points[p * DIMS + j] = random.nextInt(3) * 0.5;
            }
            tree.add(points, p);
        }
        for (int q = 0; q < 50; q++) {
            double[] query = query(points, 600, random);
            for (int k : new int[] {1, 10, 33, 100}) {
                assertSame(points, 600, query, k, weights, tree);
            }
        }
    }

    @Test
    void theGunsScanAndTreeAgree() {
        Random random = new Random(11);
        GunData data = new GunData(GunParameters.DEFAULT);
        double[] f = new double[DIMS];
        for (int i = 0; i < 2500; i++) {
            for (int j = 0; j < DIMS; j++) {
                f[j] = i % 5 == 0 ? Math.round(random.nextDouble() * 4) / 4.0 : random.nextDouble();
            }
            data.add(f, -0.1, 0.1, 1);
            if (i % 50 == 0) {
                double[] query = random.doubles(DIMS).toArray();
                for (int k : new int[] {1, 20, 100}) {
                    assertArrayEquals(data.scannedShots(query, k), data.nearestShots(query, k));
                }
            }
        }
    }

    @Test
    void sortsEventsAsAnInsertionSortDoes() {
        Random random = new Random(5);
        for (int n = 0; n < 220; n++) {
            double[] at = new double[n];
            double[] w = new double[n];
            for (int i = 0; i < n; i++) {
                at[i] = random.nextInt(9) / 8.0 - 0.5;
                w[i] = random.nextBoolean() ? 0.1 + random.nextDouble() : -0.1 - random.nextDouble();
            }
            double[] expectedAt = at.clone();
            double[] expectedW = w.clone();
            insertionSort(expectedAt, expectedW, n);
            GunData.sortEvents(at, w, n);
            assertArrayEquals(expectedAt, at);
            assertArrayEquals(expectedW, w);
        }
    }

    /** The order the gun used before the merge sort. */
    private static void insertionSort(double[] at, double[] w, int n) {
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

    private static void assertSame(double[] points, int size, double[] query, int k, double[] weights, KdTree tree) {
        double[] distance = new double[size];
        for (int p = 0; p < size; p++) {
            double sum = 0;
            for (int j = 0; j < DIMS; j++) {
                double d = (query[j] - points[p * DIMS + j]) * weights[j];
                sum += d * d;
            }
            distance[p] = sum;
        }
        int[] expected = IntStream.range(0, size).boxed()
                .sorted(Comparator.<Integer>comparingDouble(p -> distance[p]).thenComparingInt(p -> p))
                .limit(k).mapToInt(Integer::intValue).toArray();
        int[] nearest = new int[k];
        double[] distances = new double[k];
        int found = tree.nearest(points, query, k, nearest, distances);
        assertEquals(expected.length, found);
        assertArrayEquals(expected, Arrays.copyOf(nearest, found));
        for (int i = 0; i < found; i++) {
            assertEquals(distance[expected[i]], distances[i]);
        }
    }

    private static void fill(double[] points, int p, Random random) {
        // Clustered like real features: most near a few centres, some spread, some repeated exactly.
        for (int j = 0; j < DIMS; j++) {
            double centre = (p % 4) * 0.25;
            points[p * DIMS + j] = p % 13 == 0 ? 0.5 : centre + 0.05 * random.nextGaussian();
        }
    }

    private static double[] query(double[] points, int size, Random random) {
        double[] query = new double[DIMS];
        if (random.nextBoolean()) {
            System.arraycopy(points, random.nextInt(size) * DIMS, query, 0, DIMS);
        } else {
            for (int j = 0; j < DIMS; j++) {
                query[j] = random.nextDouble();
            }
        }
        return query;
    }

    private static double[] randomWeights(Random random) {
        double[] w = new double[DIMS];
        for (int j = 0; j < DIMS; j++) {
            w[j] = j % 3 == 2 ? 0 : 0.1 + 5 * random.nextDouble();
        }
        return w;
    }

    private static double[] uniformWeights() {
        double[] w = new double[DIMS];
        Arrays.fill(w, 1);
        return w;
    }
}
