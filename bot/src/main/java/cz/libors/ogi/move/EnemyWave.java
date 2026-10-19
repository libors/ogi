package cz.libors.ogi.move;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.gun.TargetingFeatures;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.wave.HitRange;
import cz.libors.ogi.wave.Wave;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An enemy wave, the frame its guess factors are measured in, where the enemy is likely to have aimed, and the parts
 * our bullets shadow; and what it costs a robot whose way it crosses within a hit range.
 */
final class EnemyWave {

    /** Danger every way carries, so that the distance to the enemy decides between ways the profile calls safe. */
    private static final double DANGER_FLOOR = 0.02;

    final Wave wave;
    final HitRange range;
    final int direction;
    final double escape;
    /** Our movement as the enemy saw it when it aimed ({@link TargetingFeatures}). */
    final double[] features;
    /** Where the enemy is likely to have aimed, as its {@link DangerModel} saw it when the wave appeared. */
    final DangerModel.Prediction prediction;
    /** Guess-factor intervals {low, high, chance of a collision} that our bullets shadow, by bullet. */
    final Map<Object, List<double[]>> shadows = new HashMap<>();
    /** Whether the wave teaches the danger model: not if it was fired while we shielded instead of surfing. */
    final boolean learns;
    /** In a melee, the chance that the shooter aimed at us (as {@link EnemyWaves} estimates it); 1 in a duel. */
    double aimedAtUs = 1;
    /** The robots the shooter could aim at that it had closer than us when it aimed, which {@link #aimedAtUs} is of. */
    int closer;

    EnemyWave(Wave wave, HitRange range, int direction, double[] features, DangerModel.Prediction prediction,
              boolean learns) {
        this.learns = learns;
        this.wave = wave;
        this.range = range;
        this.direction = direction;
        this.escape = wave.maxEscapeAngle();
        this.features = features;
        this.prediction = prediction;
    }

    double guessFactor(double heading) {
        return direction * Angles.normalizeRelative(heading - range.reference()) / escape;
    }

    /** The share of the wave's predicted aim inside this hit range, times its bullet's damage. */
    double damage(HitRange range) {
        if (!range.hits()) {
            return DANGER_FLOOR * BulletMotion.damage(wave.power());
        }
        double a = direction * range.minOffset() / escape;
        double b = direction * range.maxOffset() / escape;
        double unshadowed = 1 - shadowedShare(Math.min(a, b), Math.max(a, b));
        return (DangerModel.mass(prediction.profile(), a, b) + DANGER_FLOOR) * unshadowed
                * BulletMotion.damage(wave.power());
    }

    /**
     * The share of the wave's predicted aim between guess factors {@code low} and {@code high} that is shadowed, each
     * guess factor counted by the likeliest collision there.
     */
    private double shadowedShare(double low, double high) {
        if (shadows.isEmpty() || high <= low) {
            return 0;
        }
        List<double[]> parts = new ArrayList<>();
        for (List<double[]> list : shadows.values()) {
            for (double[] s : list) {
                double from = Math.max(low, s[0]);
                double to = Math.min(high, s[1]);
                if (to > from) {
                    parts.add(new double[] {from, to, s[2]});
                }
            }
        }
        if (parts.isEmpty()) {
            return 0;
        }
        double[] cuts = new double[2 * parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            cuts[2 * i] = parts.get(i)[0];
            cuts[2 * i + 1] = parts.get(i)[1];
        }
        Arrays.sort(cuts);
        double[] profile = prediction.profile();
        double shadowed = 0;
        for (int i = 1; i < cuts.length; i++) {
            if (cuts[i] <= cuts[i - 1]) {
                continue;
            }
            double middle = (cuts[i - 1] + cuts[i]) / 2;
            double weight = 0;
            for (double[] part : parts) {
                if (part[0] <= middle && middle <= part[1]) {
                    weight = Math.max(weight, part[2]);
                }
            }
            if (weight > 0) {
                shadowed += weight * DangerModel.share(profile, cuts[i - 1], cuts[i]);
            }
        }
        double total = DangerModel.share(profile, low, high);
        return total > 0 ? Math.min(1, shadowed / total) : 0;
    }
}
