package cz.libors.ogi.move;

import cz.libors.ogi.gun.TargetingFeatures;

import java.util.Arrays;

/**
 * Where one enemy's bullets go, learned from the bullets we observe (those that hit us or one of our bullets): a
 * profile over guess factors, in the frame of the enemy's waves, for each new wave.
 *
 * <p>The data's profile mixes two smoothed histograms. One holds every observed bullet alike, plus a prior of a gun
 * aiming straight at us. The other holds the bullets fired in situations most like this wave's: the nearest ones by
 * the {@link TargetingFeatures} of our movement as the enemy saw it when it aimed, so that a gun whose aim depends on
 * how we move (linear targeting, guess-factor guns) becomes predictable. The nearest bullets get more of the profile
 * as data grows ({@link DangerParameters}).
 *
 * <p>The data can be one estimator in an ensemble:
 * <ul>
 *   <li>the {@link SimpleAims}: each puts a narrow kernel where that simple gun would have aimed at this wave
 *       ({@link DangerParameters#simplePrior()} above 0);</li>
 *   <li>a flattener: where we ourselves were when this enemy's waves passed us, in situations like this one, which is
 *       where a gun that learns our movement aims ({@link DangerParameters#flatPrior()} above 0).</li>
 * </ul>
 * An estimator's weight follows the share it gave the bullets observed so far, so one that does not describe the
 * enemy's gun loses its weight within a few bullets and costs us no room, and one that does takes over.
 *
 * <p>The surfer runs it for every enemy wave; the offline danger bench runs it on recorded battles, through the same
 * code. Brute force over primitive arrays: a battle has some hundreds of observed bullets.
 */
public final class DangerModel {

    public static final int BINS = 47;
    private static final int MIDDLE = BINS / 2;
    /** The smallest share an estimator can give a bullet, so that its record stays finite. */
    private static final double FLOOR = 1e-6;
    private static final int DATA = 0;
    private static final int FLATTENER = 1 + SimpleAims.COUNT;
    private static final int ESTIMATORS = 2 + SimpleAims.COUNT;

    /**
     * What the model expects of one wave.
     *
     * @param profile the share of the enemy's aim in each bin; sums to 1
     * @param data    the data's own profile
     * @param simple  the guess factors at which the simple guns would have aimed ({@link SimpleAims} order)
     * @param visits  the flattener's profile, null without one
     */
    public record Prediction(double[] profile, double[] data, double[] simple, double[] visits) {
    }

    private final DangerParameters parameters;
    private final double[] all = new double[BINS];
    private double allTotal;
    private final Store bullets;
    private final Store visits;
    /** Per estimator: the decayed sum of ln(the share it gave each observed bullet). */
    private final double[] records = new double[ESTIMATORS];

    public DangerModel(DangerParameters parameters) {
        this.parameters = parameters;
        bullets = new Store(parameters, 0);
        visits = new Store(parameters, parameters.flatWindow());
        if (parameters.priorWeight() > 0) {
            allTotal += addKernel(all, MIDDLE, parameters.priorWeight(), parameters.bandwidth());
        }
    }

    /** Observed bullets. */
    public int size() {
        return bullets.size;
    }

    /** The profile of a wave fired at us in the situation {@code query}, at which the simple guns would have aimed. */
    public Prediction predict(double[] query, double[] simpleGuessFactors) {
        double[] data = profile(query);
        double[] visited = parameters.flatPrior() > 0 ? visits.profile(query, parameters.bandwidth()) : null;
        if (!ensemble()) {
            return new Prediction(data, data, simpleGuessFactors, visited);
        }
        double[] w = estimatorWeights();
        double[] mixed = new double[BINS];
        for (int i = 0; i < BINS; i++) {
            mixed[i] = w[DATA] * data[i] + (visited == null ? 0 : w[FLATTENER] * visited[i]);
        }
        if (parameters.simplePrior() > 0) {
            for (int g = 0; g < SimpleAims.COUNT; g++) {
                int at = bin(simpleGuessFactors[g]);
                double total = kernelTotal(at, parameters.simpleBandwidth());
                for (int i = 0; i < BINS; i++) {
                    mixed[i] += w[1 + g] * kernel(i, at, parameters.simpleBandwidth()) / total;
                }
            }
        }
        return new Prediction(mixed, data, simpleGuessFactors, visited);
    }

    /**
     * A bullet of this enemy passed us at this guess factor, from a wave with this prediction: the estimators are
     * scored on it and the data learns it.
     */
    public void learn(double[] f, Prediction prediction, double guessFactor) {
        if (ensemble()) {
            int at = bin(guessFactor);
            score(DATA, prediction.data()[at]);
            if (parameters.simplePrior() > 0) {
                for (int g = 0; g < SimpleAims.COUNT; g++) {
                    int aim = bin(prediction.simple()[g]);
                    score(1 + g, kernel(at, aim, parameters.simpleBandwidth())
                            / kernelTotal(aim, parameters.simpleBandwidth()));
                }
            }
            if (prediction.visits() != null) {
                score(FLATTENER, prediction.visits()[at]);
            }
        }
        learn(f, guessFactor);
    }

    /** One of this enemy's waves passed us at this guess factor, fired at us in the situation {@code f}. */
    public void visit(double[] f, double guessFactor) {
        if (parameters.flatPrior() > 0) {
            visits.add(f, bin(guessFactor));
        }
    }

    private boolean ensemble() {
        return parameters.simplePrior() > 0 || parameters.flatPrior() > 0;
    }

    private void score(int estimator, double share) {
        records[estimator] = parameters.decay() * records[estimator] + Math.log(Math.max(share, FLOOR));
    }

    /**
     * Each estimator's weight, the data first: its prior times {@code exp(temper · record)}, normalised, with
     * {@link DangerParameters#share()} kept at the priors.
     */
    double[] estimatorWeights() {
        double[] prior = new double[ESTIMATORS];
        prior[DATA] = 1;
        for (int g = 0; g < SimpleAims.COUNT; g++) {
            prior[1 + g] = parameters.simplePrior();
        }
        prior[FLATTENER] = parameters.flatPrior();
        double priorTotal = 0;
        for (double p : prior) {
            priorTotal += p;
        }
        double best = Double.NEGATIVE_INFINITY;
        for (int e = 0; e < ESTIMATORS; e++) {
            if (prior[e] > 0) {
                best = Math.max(best, records[e]);
            }
        }
        double[] w = new double[ESTIMATORS];
        double total = 0;
        for (int e = 0; e < ESTIMATORS; e++) {
            prior[e] /= priorTotal;
            // An estimator without prior weight takes no part: 0 · exp(a large gap) would be NaN.
            w[e] = prior[e] > 0 ? prior[e] * Math.exp(parameters.temper() * (records[e] - best)) : 0;
            total += w[e];
        }
        for (int e = 0; e < ESTIMATORS; e++) {
            w[e] = (1 - parameters.share()) * w[e] / total + parameters.share() * prior[e];
        }
        return w;
    }

    /** A bullet of this enemy passed us at this guess factor, fired at us in the situation {@code f}. */
    void learn(double[] f, double guessFactor) {
        int at = bin(guessFactor);
        allTotal += addKernel(all, at, 1, parameters.bandwidth());
        bullets.add(f, at);
    }

    /** The data's share of the enemy's aim in each bin for a wave fired at us in the situation {@code query}. */
    public double[] profile(double[] query) {
        double[] profile = new double[BINS];
        for (int i = 0; i < BINS; i++) {
            profile[i] = allTotal > 0 ? all[i] / allTotal : 1.0 / BINS;
        }
        double share = bullets.size == 0 ? 0
                : parameters.knnShare() * bullets.size / (bullets.size + parameters.knnRamp());
        if (share == 0) {
            return profile;
        }
        double[] near = bullets.profile(query, parameters.bandwidth());
        for (int i = 0; i < BINS; i++) {
            profile[i] = (1 - share) * profile[i] + share * near[i];
        }
        return profile;
    }

    /** The share of a profile between two guess factors, the bins of both ends included. */
    public static double mass(double[] profile, double low, double high) {
        int from = bin(Math.min(low, high));
        int to = bin(Math.max(low, high));
        double sum = 0;
        for (int i = from; i <= to; i++) {
            sum += profile[i];
        }
        return sum;
    }

    /**
     * The share of a profile between two guess factors, each bin counted by the part of its width inside: bin i covers
     * guess factors ((i − 23 − ½) / 23, (i − 23 + ½) / 23).
     */
    public static double share(double[] profile, double low, double high) {
        double sum = 0;
        for (int i = bin(low); i <= bin(high); i++) {
            double from = Math.max(low, (i - MIDDLE - 0.5) / MIDDLE);
            double to = Math.min(high, (i - MIDDLE + 0.5) / MIDDLE);
            if (to > from) {
                sum += profile[i] * (to - from) * MIDDLE;
            }
        }
        return sum;
    }

    public static int bin(double guessFactor) {
        double clamped = Math.max(-1, Math.min(1, guessFactor));
        return (int) Math.round(clamped * MIDDLE) + MIDDLE;
    }

    /** A Cauchy kernel centred on bin {@code at}; returns the weight it added over all bins. */
    private static double addKernel(double[] target, int at, double weight, double bandwidth) {
        double added = 0;
        for (int i = 0; i < BINS; i++) {
            double w = weight * kernel(i, at, bandwidth);
            target[i] += w;
            added += w;
        }
        return added;
    }

    private static double kernel(int i, int at, double bandwidth) {
        double d = (i - at) / bandwidth;
        return 1 / (d * d + 1);
    }

    private static double kernelTotal(int at, double bandwidth) {
        double total = 0;
        for (int i = 0; i < BINS; i++) {
            total += kernel(i, at, bandwidth);
        }
        return total;
    }

    /** Situations and the bins they ended in; with a window, only the latest that many. */
    private static final class Store {

        private final int dims = TargetingFeatures.COUNT;
        private final double[] weights;
        private final double neighbourScale;
        private final int maxNeighbours;
        private final int window;
        private double[] features = new double[256 * dims];
        private int[] bins = new int[256];
        private int size;
        /** With a window: where the next entry goes once the window is full. */
        private int next;
        private final int[] nearest;
        private final double[] nearestDistance;

        Store(DangerParameters parameters, int window) {
            weights = parameters.weights();
            neighbourScale = parameters.neighbourScale();
            maxNeighbours = parameters.maxNeighbours();
            this.window = window;
            nearest = new int[maxNeighbours];
            nearestDistance = new double[maxNeighbours];
        }

        void add(double[] f, int bin) {
            int at;
            if (window > 0 && size == window) {
                at = next;
                next = (next + 1) % window;
            } else {
                if (size == bins.length) {
                    features = Arrays.copyOf(features, 2 * size * dims);
                    bins = Arrays.copyOf(bins, 2 * size);
                }
                at = size++;
            }
            System.arraycopy(f, 0, features, at * dims, dims);
            bins[at] = bin;
        }

        /**
         * A smoothed histogram of the bins of the nearest entries, about {@code neighbourScale · √n} of them, each
         * weighted down with its distance; sums to 1, uniform without entries.
         */
        double[] profile(double[] query, double bandwidth) {
            double[] profile = new double[BINS];
            if (size == 0) {
                Arrays.fill(profile, 1.0 / BINS);
                return profile;
            }
            int k = (int) Math.max(1, Math.min(maxNeighbours, Math.round(neighbourScale * Math.sqrt(size))));
            int found = findNearest(query, k);
            double scale = nearestDistance[found - 1] + 1e-12;
            double total = 0;
            for (int j = 0; j < found; j++) {
                total += addKernel(profile, bins[nearest[j]], Math.exp(-nearestDistance[j] / scale), bandwidth);
            }
            for (int i = 0; i < BINS; i++) {
                profile[i] /= total;
            }
            return profile;
        }

        /** Fills {@link #nearest} with the {@code k} closest entries, closest first; returns how many there are. */
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
    }
}
