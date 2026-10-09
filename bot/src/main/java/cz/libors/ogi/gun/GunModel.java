package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Angles;

/**
 * What the guess-factor gun knows about one enemy for the whole battle, and the arithmetic of its decisions: the
 * stored shots, each gun's virtual score, which gun aims, and how a passed wave is learned.
 *
 * <p>The bot runs it inside {@link GuessFactorGun}; the offline gun bench runs it on the {@link GunLog} lines of a
 * recorded battle. With the bot's parameters the bench therefore decides exactly as the bot did.
 */
public final class GunModel {

    public static final int GUESS_FACTOR = 0;
    public static final int CIRCULAR = 1;
    public static final int GUNS = 2;

    /**
     * The inputs of the shot planned in turn {@code time} for the turn after.
     *
     * @param features    the shot's {@link TargetingFeatures}
     * @param direction   the target's lateral direction, +1 or −1: the sign of positive guess factors
     * @param reference   bearing from the planned origin to the target's latest scan
     * @param escape      the maximum escape angle at the shot's power
     * @param search      whether the guess-factor gun searches its data for this shot
     * @param circularAim the circular gun's aim, NaN if it has none
     */
    public record Shot(String target, long time, double[] features, int direction, double reference, double escape,
                       boolean search, double circularAim) {
    }

    /**
     * The decision for a shot.
     *
     * @param guessFactor the guess-factor gun's answer, NaN if it did not search or had no data
     * @param aims        each gun's aim, NaN if it had none; the chosen gun's falls back to the reference
     * @param gun         the gun whose aim the turret follows
     */
    public record Aim(double guessFactor, double[] aims, int gun) {

        public double aim() {
            return aims[gun];
        }
    }

    /**
     * A wave that has passed its target.
     *
     * @param time      the turn of the shot's plan
     * @param real      whether a bullet was fired with it
     * @param reference bearing from the wave's origin to the target's scan the plan used; offsets are relative to it
     * @param escape    the wave's maximum escape angle
     * @param hits      whether any heading would have hit
     * @param minOffset the lowest hitting heading, +∞ if none hits
     * @param maxOffset the highest hitting heading, −∞ if none hits
     */
    public record Pass(String target, long time, boolean real, double reference, double escape, boolean hits,
                       double minOffset, double maxOffset) {
    }

    private final GunParameters parameters;
    private final GunData data;
    /** Per gun: {hits, tries}, both weighted like the waves. */
    private final double[][] scores = new double[GUNS][2];

    public GunModel(GunParameters parameters) {
        this.parameters = parameters;
        this.data = new GunData(parameters);
    }

    /** Past shots stored. */
    public int dataSize() {
        return data.size();
    }

    /** A gun's weighted virtual hit rate, or NaN before it was scored. */
    public double hitRate(int gun) {
        return scores[gun][1] == 0 ? Double.NaN : scores[gun][0] / scores[gun][1];
    }

    /**
     * Each gun's aim for a shot and the gun to follow: the guess-factor gun once it has
     * {@link GunParameters#minTries()} weighted tries and the better record, or when the circular gun has no aim.
     * A chosen gun without an aim aims at the reference.
     */
    public Aim aim(Shot shot) {
        double gf = shot.search() ? data.bestGuessFactor(shot.features()) : Double.NaN;
        double[] aims = new double[GUNS];
        aims[GUESS_FACTOR] = Double.isNaN(gf) ? Double.NaN : shot.reference() + shot.direction() * gf * shot.escape();
        aims[CIRCULAR] = shot.circularAim();
        int gun = choose(aims);
        if (Double.isNaN(aims[gun])) {
            aims[gun] = shot.reference();
        }
        return new Aim(gf, aims, gun);
    }

    private int choose(double[] aims) {
        double[] gf = scores[GUESS_FACTOR];
        double[] circular = scores[CIRCULAR];
        boolean gfBetter = gf[1] >= parameters.minTries()
                && gf[0] / gf[1] > circular[0] / Math.max(circular[1], 1e-9);
        if (Double.isNaN(aims[CIRCULAR]) || (gfBetter && !Double.isNaN(aims[GUESS_FACTOR]))) {
            return GUESS_FACTOR;
        }
        return CIRCULAR;
    }

    /**
     * Learns the passed wave of a shot: it scores the guns if all of them aimed, and becomes data if any heading
     * would have hit. Returns the guns whose aim would have hit, as bits {@code 1 << gun}.
     */
    public int learn(Shot shot, Aim aim, Pass pass) {
        double weight = pass.real() ? 1 : parameters.virtualWeight();
        boolean scored = true;
        int hit = 0;
        for (int g = 0; g < GUNS; g++) {
            double a = aim.aims()[g];
            if (Double.isNaN(a)) {
                scored = false;
                continue;
            }
            double offset = Angles.normalizeRelative(a - pass.reference());
            if (pass.hits() && offset >= pass.minOffset() && offset <= pass.maxOffset()) {
                hit |= 1 << g;
            }
        }
        for (int g = 0; g < GUNS && scored; g++) {
            scores[g][1] += weight;
            if ((hit & 1 << g) != 0) {
                scores[g][0] += weight;
            }
        }
        if (pass.hits()) {
            double a = shot.direction() * pass.minOffset() / pass.escape();
            double b = shot.direction() * pass.maxOffset() / pass.escape();
            data.add(shot.features(), Math.min(a, b), Math.max(a, b), weight);
        }
        return hit;
    }
}
