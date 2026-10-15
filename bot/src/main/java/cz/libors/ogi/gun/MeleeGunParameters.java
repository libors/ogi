package cz.libors.ogi.gun;

import java.util.Arrays;
import java.util.Locale;

/**
 * The tunable numbers of the melee gun ({@link MeleeGun}). The bot plays with {@link #DEFAULT}; the offline melee
 * bench replays recorded battles with others.
 *
 * @param weights             one per feature, in the order of {@link MeleeGun#FEATURES}; the distance between two
 *                            situations is the square root of the sum of their weighted squared differences
 * @param neighbours          the most similar records each enemy's aim uses
 * @param wallRange           the wall ahead counts up to this far (px)
 * @param neighbourRange      the closest robot's distance counts up to this far (px)
 * @param distanceSoftening   a neighbour weighs 1 / (its feature distance + this)
 * @param enemyDistancePower  an enemy's positions weigh 1 / its distance from us to this power
 * @param kernelWidth         a position covers this many robot half-widths around its bearing
 * @param maxAge              enemies whose last scan is older than this many turns are not aimed at
 * @param horizon             displacements are collected for this many turns after a record's scan
 * @param maxRecords          records kept per enemy; the oldest go first
 * @param everyRound          whether the records of earlier rounds count whatever turn of their round they were
 *                            scanned in; otherwise only those scanned before the enemy's last scan in its round
 * @param reachingOnly        whether the neighbours come only from records whose displacements reach the bullet's
 *                            flight; otherwise a record that ends sooner takes a neighbour's place and gives nothing
 */
public record MeleeGunParameters(double[] weights, int neighbours, double wallRange, double neighbourRange,
                                 double distanceSoftening, double enemyDistancePower, double kernelWidth, int maxAge,
                                 int horizon, int maxRecords, boolean everyRound, boolean reachingOnly) {

    /**
     * Tuned on the melee bench (coordinate search over half of 99 rumble draws, checked on the other half), with the
     * counts cut back for the gun's time: the turn rate and the wall ahead weigh most, the closest robot's bearing
     * least.
     */
    public static final MeleeGunParameters DEFAULT = new MeleeGunParameters(
            new double[] {2, 1.4, 7.84, 11.2, 0.98, 0.5, 0.25, 0.5}, 24, 560, 1680, 0.8, 0.245, 2, 32, 140, 1500, true,
            true);

    public MeleeGunParameters {
        if (weights.length != MeleeGun.FEATURES) {
            throw new IllegalArgumentException("expected " + MeleeGun.FEATURES + " weights, got " + weights.length);
        }
        if (neighbours < 1 || maxAge < 0 || horizon < 1 || maxRecords < 1) {
            throw new IllegalArgumentException("neighbours, horizon and maxRecords must be positive, maxAge not "
                    + "negative: " + neighbours + ", " + horizon + ", " + maxRecords + ", " + maxAge);
        }
        weights = weights.clone();
    }

    @Override
    public double[] weights() {
        return weights.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MeleeGunParameters p && Arrays.equals(weights, p.weights) && neighbours == p.neighbours
                && Double.compare(wallRange, p.wallRange) == 0 && Double.compare(neighbourRange, p.neighbourRange) == 0
                && Double.compare(distanceSoftening, p.distanceSoftening) == 0
                && Double.compare(enemyDistancePower, p.enemyDistancePower) == 0
                && Double.compare(kernelWidth, p.kernelWidth) == 0 && maxAge == p.maxAge && horizon == p.horizon
                && maxRecords == p.maxRecords && everyRound == p.everyRound && reachingOnly == p.reachingOnly;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new double[] {Arrays.hashCode(weights), neighbours, wallRange, neighbourRange,
                distanceSoftening, enemyDistancePower, kernelWidth, maxAge, horizon, maxRecords, everyRound ? 1 : 0,
                reachingOnly ? 1 : 0});
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "weights=%s neighbours=%d wallRange=%s neighbourRange=%s "
                        + "distanceSoftening=%s enemyDistancePower=%s kernelWidth=%s maxAge=%d horizon=%d maxRecords=%d "
                        + "everyRound=%s reachingOnly=%s",
                Arrays.toString(weights), neighbours, wallRange, neighbourRange, distanceSoftening,
                enemyDistancePower, kernelWidth, maxAge, horizon, maxRecords, everyRound, reachingOnly);
    }
}
