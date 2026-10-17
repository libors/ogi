package cz.libors.ogi.move;

import cz.libors.ogi.gun.TargetingFeatures;

import java.util.Arrays;
import java.util.Locale;

/**
 * The tunable numbers of the surfer's danger model. The bot surfs with {@link #DEFAULT}; the offline danger bench
 * replays recorded battles with others.
 *
 * @param weights         one per feature, in the order of {@link TargetingFeatures#compute}, which describes our
 *                        movement as the enemy saw it when it aimed; the distance between two bullets is the sum of
 *                        their squared weighted differences
 * @param neighbourScale  a profile takes about {@code neighbourScale · √n} of the {@code n} observed bullets, at
 *                        least one
 * @param maxNeighbours   and at most this many
 * @param bandwidth       the width of one bullet's kernel, in bins
 * @param knnShare        the share of the nearest bullets in the data's profile once there are many; the rest is all
 *                        bullets alike
 * @param knnRamp         the number of observed bullets at which the nearest ones get half of {@code knnShare}
 * @param priorWeight     how many bullets the prior of a gun aiming straight at us counts as
 * @param simplePrior     the weight of each simple gun ({@link SimpleAims}) against the data's 1 before any bullet;
 *                        0 leaves them out
 * @param simpleBandwidth the width of a simple gun's kernel, in bins
 * @param temper          how strongly an estimator's record of observed bullets sets its weight: weights go with
 *                        {@code exp(temper · Σ ln share)}
 * @param decay           how much of an estimator's record each new bullet keeps, 1 for all
 * @param share           the part of the weights that stays with the priors, so that an estimator can come back
 * @param flatPrior       the weight of the flattener (where we were when the enemy's waves passed us) against the
 *                        data's 1 before any bullet; 0 leaves it out
 * @param flatWindow      how many of our latest visits the flattener keeps
 */
public record DangerParameters(double[] weights, double neighbourScale, int maxNeighbours, double bandwidth,
                               double knnShare, double knnRamp, double priorWeight, double simplePrior,
                               double simpleBandwidth, double temper, double decay, double share, double flatPrior,
                               int flatWindow) {

    /** One histogram of all bullets, the surfer's first model: the baseline of the danger bench. */
    public static final DangerParameters HISTOGRAM =
            new DangerParameters(new double[] {3, 3, 1.5, 2.5, 2, 1.5, 3, 1, 1.5, 2}, 1, 50, 1, 0, 1, 1, 0, 1, 1, 1,
                    0, 0, 1000);

    /** Tuned on the danger bench, the later simple guns with the same prior (bot.md "Movement", Danger). */
    public static final DangerParameters DEFAULT =
            new DangerParameters(new double[] {8.23, 3, 1.44, 1.225, 0, 1.05, 2.1, 1.4, 1.029, 0}, 1.4, 18, 0.686, 1,
                    9.8, 8, 0.28, 0.686, 0.245, 0.92, 0, 0.3, 1000);

    public DangerParameters {
        if (weights.length != TargetingFeatures.COUNT) {
            throw new IllegalArgumentException(
                    "expected " + TargetingFeatures.COUNT + " weights, got " + weights.length);
        }
        if (maxNeighbours < 1 || bandwidth <= 0 || knnShare < 0 || knnShare > 1 || knnRamp < 0 || priorWeight < 0
                || simplePrior < 0 || simpleBandwidth <= 0 || temper < 0 || decay < 0 || decay > 1 || share < 0
                || share > 1 || flatPrior < 0 || flatWindow < 1) {
            throw new IllegalArgumentException("out of range: " + Arrays.toString(weights) + " " + maxNeighbours + " "
                    + bandwidth + " " + knnShare + " " + knnRamp + " " + priorWeight + " " + simplePrior + " "
                    + simpleBandwidth + " " + temper + " " + decay + " " + share + " " + flatPrior + " " + flatWindow);
        }
        weights = weights.clone();
    }

    @Override
    public double[] weights() {
        return weights.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DangerParameters p && Arrays.equals(weights, p.weights)
                && Double.compare(neighbourScale, p.neighbourScale) == 0 && maxNeighbours == p.maxNeighbours
                && Double.compare(bandwidth, p.bandwidth) == 0 && Double.compare(knnShare, p.knnShare) == 0
                && Double.compare(knnRamp, p.knnRamp) == 0 && Double.compare(priorWeight, p.priorWeight) == 0
                && Double.compare(simplePrior, p.simplePrior) == 0
                && Double.compare(simpleBandwidth, p.simpleBandwidth) == 0 && Double.compare(temper, p.temper) == 0
                && Double.compare(decay, p.decay) == 0 && Double.compare(share, p.share) == 0
                && Double.compare(flatPrior, p.flatPrior) == 0 && flatWindow == p.flatWindow;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(weights) + 31 * Double.hashCode(neighbourScale) + 7 * maxNeighbours
                + 11 * Double.hashCode(bandwidth) + 13 * Double.hashCode(knnShare) + 17 * Double.hashCode(knnRamp)
                + 19 * Double.hashCode(priorWeight) + 23 * Double.hashCode(simplePrior)
                + 29 * Double.hashCode(simpleBandwidth) + 37 * Double.hashCode(temper) + 41 * Double.hashCode(decay)
                + 43 * Double.hashCode(share) + 47 * Double.hashCode(flatPrior) + 53 * flatWindow;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "weights=%s neighbourScale=%s maxNeighbours=%d bandwidth=%s knnShare=%s "
                        + "knnRamp=%s priorWeight=%s simplePrior=%s simpleBandwidth=%s temper=%s decay=%s share=%s "
                        + "flatPrior=%s flatWindow=%d", Arrays.toString(weights), neighbourScale, maxNeighbours,
                bandwidth, knnShare, knnRamp, priorWeight, simplePrior, simpleBandwidth, temper, decay, share, flatPrior,
                flatWindow);
    }
}
