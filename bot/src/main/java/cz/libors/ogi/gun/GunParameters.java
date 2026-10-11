package cz.libors.ogi.gun;

import java.util.Arrays;
import java.util.Locale;

/**
 * The tunable numbers of the guess-factor gun. The bot plays with {@link #DEFAULT}; the offline gun bench replays
 * recorded battles with others.
 *
 * @param weights        one per feature, in the order of {@link TargetingFeatures#compute}; the distance between two
 *                       shots is the sum of their squared weighted differences
 * @param neighbourScale a search takes about {@code neighbourScale · √n} of the {@code n} stored shots, at least one
 * @param maxNeighbours  and at most this many
 * @param virtualWeight  what a wave without a bullet counts, against 1 for a wave with one
 * @param minTries       weighted tries the guess-factor gun needs before it may replace circular targeting
 */
public record GunParameters(double[] weights, double neighbourScale, int maxNeighbours, double virtualWeight,
                            double minTries) {

    /**
     * Tuned on the gun bench (coordinate search over half of a rumble sample, checked on the other half). Turns since
     * the last reversal barely count, and the wall behind and the path's curve not at all.
     */
    public static final GunParameters DEFAULT =
            new GunParameters(new double[] {12, 3, 4.2, 1.75, 0.17, 2.1, 2.1, 0, 2.1, 0}, 1, 100, 0.125, 0);

    /**
     * The anti-surfer gun's: the main gun's weights over its own data, the waves of our bullets in three views (see
     * {@link GunModel}). A fixed copy: parameters given to the main gun do not reach it.
     */
    public static final GunParameters ANTI_SURFER =
            new GunParameters(new double[] {12, 3, 4.2, 1.75, 0.17, 2.1, 2.1, 0, 2.1, 0}, 1, 100, 1, 0);

    public GunParameters {
        if (weights.length != TargetingFeatures.COUNT) {
            throw new IllegalArgumentException(
                    "expected " + TargetingFeatures.COUNT + " weights, got " + weights.length);
        }
        if (maxNeighbours < 1) {
            throw new IllegalArgumentException("maxNeighbours must be positive: " + maxNeighbours);
        }
        weights = weights.clone();
    }

    @Override
    public double[] weights() {
        return weights.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GunParameters p && Arrays.equals(weights, p.weights)
                && Double.compare(neighbourScale, p.neighbourScale) == 0 && maxNeighbours == p.maxNeighbours
                && Double.compare(virtualWeight, p.virtualWeight) == 0 && Double.compare(minTries, p.minTries) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(weights) + Double.hashCode(neighbourScale) + 7 * maxNeighbours
                + 11 * Double.hashCode(virtualWeight) + 13 * Double.hashCode(minTries);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "weights=%s neighbourScale=%s maxNeighbours=%d virtualWeight=%s minTries=%s",
                Arrays.toString(weights), neighbourScale, maxNeighbours, virtualWeight, minTries);
    }
}
