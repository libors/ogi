package cz.libors.harness.dangerbench;

import cz.libors.ogi.move.DangerModel;
import cz.libors.ogi.move.DangerParameters;

import java.util.HashMap;
import java.util.Map;

/**
 * Replays one battle's enemy bullets through a {@link DangerModel} with given parameters: the model learns the
 * observable bullets as the surfer would, and every bullet scores the model's profile for its wave.
 */
public final class DangerReplay {

    /** The smallest share a profile can give a bullet, so that its logarithm stays finite. */
    private static final double FLOOR = 1e-6;

    /**
     * Sums over the bullets of one or more battles.
     *
     * @param shots   bullets scored
     * @param logMass the sum of ln(the profile's share over the bullet's window: its guess factor ± our half width)
     * @param mass    the sum of those shares
     * @param hits    bullets that hit us
     * @param hitMass the sum of the shares of those
     * @param learned bullets the model learned
     */
    public record Tally(long shots, double logMass, double mass, long hits, double hitMass, long learned) {

        static final Tally ZERO = new Tally(0, 0, 0, 0, 0, 0);

        Tally plus(Tally o) {
            return new Tally(shots + o.shots, logMass + o.logMass, mass + o.mass, hits + o.hits,
                    hitMass + o.hitMass, learned + o.learned);
        }

        /** Mean log share per bullet: higher is better; a uniform profile scores about −2.3 on real recordings. */
        double meanLog() {
            return shots == 0 ? Double.NaN : logMass / shots;
        }

        double meanMass() {
            return shots == 0 ? Double.NaN : mass / shots;
        }

        double meanHitMass() {
            return hits == 0 ? Double.NaN : hitMass / hits;
        }
    }

    private DangerReplay() {
    }

    /** A bullet's contribution to {@code logMass}: the logarithm of its share, floored. */
    static double logScore(double mass) {
        return Math.log(Math.max(mass, FLOOR));
    }

    /** One bullet's score: the profile's share over its window ({@link Tally}'s {@code mass}). */
    public interface Scored {
        void shot(DangerBattle.Shot shot, double mass);
    }

    public static Tally replay(DangerBattle battle, DangerParameters parameters) {
        return replay(battle, parameters, (shot, mass) -> { });
    }

    /** As {@link #replay(DangerBattle, DangerParameters)}, telling {@code scored} each bullet's score in turn. */
    public static Tally replay(DangerBattle battle, DangerParameters parameters, Scored scored) {
        DangerModel model = new DangerModel(parameters);
        Map<Integer, DangerBattle.Shot> shots = new HashMap<>();
        Map<Integer, DangerModel.Prediction> predictions = new HashMap<>();
        long n = 0;
        long hits = 0;
        long learned = 0;
        double logMass = 0;
        double mass = 0;
        double hitMass = 0;
        for (Object e : battle.events()) {
            if (e instanceof DangerBattle.Shot s) {
                shots.put(s.id(), s);
                DangerModel.Prediction prediction = model.predict(s.features(), s.simple());
                predictions.put(s.id(), prediction);
                double m = DangerModel.mass(prediction.profile(), s.guessFactor() - s.halfWidth(),
                        s.guessFactor() + s.halfWidth());
                scored.shot(s, m);
                n++;
                mass += m;
                logMass += logScore(m);
                if (s.hitUs()) {
                    hits++;
                    hitMass += m;
                }
            } else if (e instanceof DangerBattle.Learn l) {
                DangerBattle.Shot s = shots.get(l.shot());
                model.learn(s.features(), predictions.get(l.shot()), s.guessFactor());
                learned++;
            } else if (e instanceof DangerBattle.Visit v) {
                model.visit(shots.get(v.shot()).features(), v.guessFactor());
            }
        }
        return new Tally(n, logMass, mass, hits, hitMass, learned);
    }
}
