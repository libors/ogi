package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Angles;

/**
 * What the guess-factor gun knows about one enemy for the whole battle, and the arithmetic of its decisions: the
 * stored shots, each gun's virtual score, which gun aims, and how a passed wave is learned.
 *
 * <p>Five guns: the guess-factor gun over every wave (a wave without a bullet counts
 * {@link GunParameters#virtualWeight()}), circular targeting, the anti-surfer gun over the waves of our bullets
 * only, a random gun, and a mirror gun ({@link MirrorAim}). The anti-surfer gun takes a few nearest neighbours from
 * each of several views, the latest 30 and 150 bullets and all of them, their kernels summed. A surfer dodges our
 * bullets, not our waves, and keeps going where our bullets missed it until one hits it there, so the latest bullets say where it goes now (most of what
 * a bullet says about that is gone after about a round's worth of bullets); the longer views keep what does not
 * change. The random gun aims uniformly inside the target's {@link EscapeRange}, which a surfer cannot learn; it is
 * scored by the share of that range that would have hit, not by its one aim. The guess-factor gun is chosen over
 * circular targeting by the whole battle's record; against an enemy that is hard to hit the anti-surfer gun replaces
 * the chosen gun when it has the better record over the battle's bullets, and the random gun replaces either when its
 * record over them is better still (DrussGT's idea: a surfer that learned our aim is hit less often than at random).
 * The mirror gun aims only at a robot whose position follows ours ({@link MirrorAim}); it replaces the guess-factor
 * or circular gun once it has {@link #MIRROR_MIN_TRIES} weighted tries and the better record.
 *
 * <p>The bot runs it inside {@link VirtualGuns}; the offline gun bench runs it on the {@link GunLog} lines of a
 * recorded battle. With the bot's parameters the bench therefore decides exactly as the bot did.
 */
public final class GunModel {

    public static final int GUESS_FACTOR = 0;
    public static final int CIRCULAR = 1;
    public static final int ANTI_SURFER = 2;
    public static final int RANDOM = 3;
    public static final int MIRROR = 4;
    public static final int GUNS = 5;

    /** The anti-surfer gun's views: {the latest bullets it keeps (0: all), the neighbours it takes}. */
    private static final int[][] ANTI_SURFER_VIEWS = {{30, 4}, {150, 4}, {0, 4}};
    /** The bullets the anti-surfer or the random gun needs to have been scored on before it may replace another. */
    static final double MIN_BULLETS = 30;
    /**
     * The anti-surfer gun is for enemies that are hard to hit: the guess-factor gun's record over our bullets must
     * not be clearly above this (the lower bound of its interval, one standard error below the record, is below it).
     */
    static final double HARD_TO_HIT = 0.2;
    private static final double HARD_TO_HIT_Z = 1;
    /**
     * The weighted tries the mirror gun needs before it may replace the guess-factor or circular gun: about fifteen
     * bullets' worth, so that a robot whose position follows ours only by chance for a while is not taken for a mirror.
     */
    static final double MIRROR_MIN_TRIES = 20;

    /**
     * The inputs of the shot planned in turn {@code time} for the turn after.
     *
     * @param features    the shot's {@link TargetingFeatures}
     * @param direction   the target's lateral direction, +1 or −1: the sign of positive guess factors
     * @param reference   bearing from the planned origin to the target's latest scan
     * @param escape      the maximum escape angle at the shot's power
     * @param search      whether the guess-factor gun searches its data for this shot
     * @param circularAim the circular gun's aim, NaN if it has none
     * @param randomLow   the lowest guess factor of the target's {@link EscapeRange}, NaN without one
     * @param randomHigh  its highest guess factor
     * @param randomDraw  a number drawn uniformly from [0, 1): where in that range the random gun aims
     * @param mirrorAim   the mirror gun's aim, NaN if it has none
     * @param alone       whether the shot was planned while one enemy lived (always in a duel battle); only logged,
     *                    for the gun bench
     */
    public record Shot(String target, long time, double[] features, int direction, double reference, double escape,
                       boolean search, double circularAim, double randomLow, double randomHigh, double randomDraw,
                       double mirrorAim, boolean alone) {

        /** A shot planned while one enemy lived. */
        public Shot(String target, long time, double[] features, int direction, double reference, double escape,
                    boolean search, double circularAim, double randomLow, double randomHigh, double randomDraw,
                    double mirrorAim) {
            this(target, time, features, direction, reference, escape, search, circularAim, randomLow, randomHigh,
                    randomDraw, mirrorAim, true);
        }

        /** A shot without a random gun's range. */
        public Shot(String target, long time, double[] features, int direction, double reference, double escape,
                    boolean search, double circularAim) {
            this(target, time, features, direction, reference, escape, search, circularAim, Double.NaN, Double.NaN,
                    Double.NaN);
        }

        /** A shot without a mirror gun's aim. */
        public Shot(String target, long time, double[] features, int direction, double reference, double escape,
                    boolean search, double circularAim, double randomLow, double randomHigh, double randomDraw) {
            this(target, time, features, direction, reference, escape, search, circularAim, randomLow, randomHigh,
                    randomDraw, Double.NaN);
        }
    }

    /**
     * The decision for a shot.
     *
     * @param guessFactor the guess-factor gun's answer, NaN if it did not search or had no data
     * @param aims        each gun's aim, NaN if it had none (the random gun aims only when the guess-factor gun
     *                    searched); the chosen gun's falls back to the reference
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
     * @param minOffset the lowest hitting offset from the reference, +∞ if none hits
     * @param maxOffset the highest hitting offset from the reference, −∞ if none hits
     */
    public record Pass(String target, long time, boolean real, double reference, double escape, boolean hits,
                       double minOffset, double maxOffset) {
    }

    private final GunParameters parameters;
    private final GunData data;
    private final GunData[] antiSurfer = new GunData[ANTI_SURFER_VIEWS.length];
    private final double[] eventAt;
    private final double[] eventWeight;
    /** Per gun: {hits, tries}, both weighted like the waves. */
    private final double[][] scores = new double[GUNS][2];
    /** Per gun: {hits, tries} over the waves with a bullet. */
    private final double[][] bullets = new double[GUNS][2];

    public GunModel(GunParameters parameters) {
        this.parameters = parameters;
        this.data = new GunData(parameters);
        int events = 0;
        for (int v = 0; v < antiSurfer.length; v++) {
            antiSurfer[v] = new GunData(GunParameters.ANTI_SURFER, ANTI_SURFER_VIEWS[v][0]);
            events += 2 * ANTI_SURFER_VIEWS[v][1];
        }
        eventAt = new double[events];
        eventWeight = new double[events];
    }

    /** Past shots stored. */
    public int dataSize() {
        return data.size();
    }

    /** A gun's weighted virtual hit rate, or NaN before it was scored. */
    public double hitRate(int gun) {
        return scores[gun][1] == 0 ? Double.NaN : scores[gun][0] / scores[gun][1];
    }

    /** A gun's hit rate over the waves with a bullet, by which the anti-surfer and random guns replace; NaN before. */
    public double bulletHitRate(int gun) {
        return bullets[gun][1] == 0 ? Double.NaN : bullets[gun][0] / bullets[gun][1];
    }

    /** The waves with a bullet a gun was scored on. */
    public int bulletTries(int gun) {
        return (int) bullets[gun][1];
    }

    /**
     * Each gun's aim for a shot and the gun to follow: the guess-factor gun once it has
     * {@link GunParameters#minTries()} weighted tries and the better record, or when the circular gun has no aim;
     * the anti-surfer gun instead against an enemy that is hard to hit, once it has {@link #MIN_BULLETS} bullets and
     * the better record over them, and the random gun instead of either on the same terms. The mirror gun replaces
     * the guess-factor or circular gun before that, once it has {@link #MIRROR_MIN_TRIES} weighted tries and the
     * better weighted record. A chosen gun without an aim aims at the reference.
     */
    public Aim aim(Shot shot) {
        double gf = shot.search() ? data.bestGuessFactor(shot.features()) : Double.NaN;
        double as = shot.search() ? antiSurferGuessFactor(shot.features()) : Double.NaN;
        double[] aims = new double[GUNS];
        aims[GUESS_FACTOR] = Double.isNaN(gf) ? Double.NaN : shot.reference() + shot.direction() * gf * shot.escape();
        aims[CIRCULAR] = shot.circularAim();
        aims[ANTI_SURFER] = Double.isNaN(as) ? Double.NaN : shot.reference() + shot.direction() * as * shot.escape();
        double random = shot.randomLow() + shot.randomDraw() * (shot.randomHigh() - shot.randomLow());
        aims[RANDOM] = shot.search() ? shot.reference() + shot.direction() * random * shot.escape() : Double.NaN;
        aims[MIRROR] = shot.mirrorAim();
        int gun = choose(aims);
        if (Double.isNaN(aims[gun])) {
            aims[gun] = shot.reference();
        }
        return new Aim(gf, aims, gun);
    }

    /**
     * The guess-factor gun's chance to hit at each of {@code bins} guess factors from −1 to 1 for a shot in the
     * situation {@code features} ({@link GunData#coverage}), for the overlay; null without data.
     */
    public double[] coverage(double[] features, int bins) {
        return data.coverage(features, bins);
    }

    /** The guess factor most kernel weight of all the anti-surfer views covers, NaN without bullets. */
    private double antiSurferGuessFactor(double[] features) {
        int n = 0;
        for (int v = 0; v < antiSurfer.length; v++) {
            n = antiSurfer[v].events(features, ANTI_SURFER_VIEWS[v][1], eventAt, eventWeight, n);
        }
        return n == 0 ? Double.NaN : GunData.densest(eventAt, eventWeight, n);
    }

    /**
     * The anti-surfer gun's peaks for this shot ({@link GunData#peaks}): other guess factors it would nearly as gladly
     * aim at. Empty unless the shot searched and the gun has bullets.
     */
    public double[][] antiSurferPeaks(Shot shot, double minShare, int max, double spacing) {
        if (!shot.search()) {
            return new double[0][];
        }
        int n = 0;
        for (int v = 0; v < antiSurfer.length; v++) {
            n = antiSurfer[v].events(shot.features(), ANTI_SURFER_VIEWS[v][1], eventAt, eventWeight, n);
        }
        return GunData.peaks(eventAt, eventWeight, n, minShare, max, spacing);
    }

    private int choose(double[] aims) {
        double[] gf = scores[GUESS_FACTOR];
        double[] circular = scores[CIRCULAR];
        boolean gfBetter = gf[1] >= parameters.minTries()
                && gf[0] / gf[1] > circular[0] / Math.max(circular[1], 1e-9);
        int gun = Double.isNaN(aims[CIRCULAR]) || (gfBetter && !Double.isNaN(aims[GUESS_FACTOR])) ? GUESS_FACTOR
                : CIRCULAR;
        double[] mirror = scores[MIRROR];
        if (!Double.isNaN(aims[MIRROR]) && mirror[1] >= MIRROR_MIN_TRIES
                && mirror[0] / mirror[1] > scores[gun][0] / Math.max(scores[gun][1], 1e-9)) {
            gun = MIRROR;
        }
        if (replaces(ANTI_SURFER, gun, aims)) {
            gun = ANTI_SURFER;
        }
        return replaces(RANDOM, gun, aims) ? RANDOM : gun;
    }

    /** Whether {@code gun} replaces {@code chosen} against an enemy that is hard to hit, by the bullet records. */
    private boolean replaces(int gun, int chosen, double[] aims) {
        double[] own = bullets[gun];
        return !Double.isNaN(aims[gun]) && own[1] >= MIN_BULLETS && hardToHit()
                && own[0] / own[1] > bullets[chosen][0] / Math.max(bullets[chosen][1], 1e-9);
    }

    /** Whether the guess-factor gun's record over our bullets is not clearly above {@link #HARD_TO_HIT}. */
    public boolean hardToHit() {
        double n = bullets[GUESS_FACTOR][1];
        if (n == 0) {
            return true;
        }
        // The lower bound of the Wilson score interval.
        double p = bullets[GUESS_FACTOR][0] / n;
        double z2 = HARD_TO_HIT_Z * HARD_TO_HIT_Z;
        double spread = HARD_TO_HIT_Z * Math.sqrt(p * (1 - p) / n + z2 / (4 * n * n));
        return (p + z2 / (2 * n) - spread) / (1 + z2 / n) < HARD_TO_HIT;
    }

    /**
     * Learns the passed wave of a shot: it scores the guess-factor and circular guns if both aimed and the
     * anti-surfer, the random and the mirror gun each if it aimed, a bullet's wave also scores the bullet records if
     * the guess-factor and the anti-surfer gun both aimed, and the wave becomes data if any heading would have hit (the
     * anti-surfer gun's only with a bullet). The random gun scores the share of its range that would have hit. Returns
     * the guns whose aim would have hit, as bits {@code 1 << gun}.
     */
    public int learn(Shot shot, Aim aim, Pass pass) {
        double weight = pass.real() ? 1 : parameters.virtualWeight();
        double[] aims = aim.aims();
        int hit = 0;
        for (int g = 0; g < GUNS; g++) {
            if (Double.isNaN(aims[g])) {
                continue;
            }
            double offset = Angles.normalizeRelative(aims[g] - pass.reference());
            if (pass.hits() && offset >= pass.minOffset() && offset <= pass.maxOffset()) {
                hit |= 1 << g;
            }
        }
        double[] credit = new double[GUNS];
        for (int g = 0; g < GUNS; g++) {
            credit[g] = g == RANDOM ? randomCredit(shot, pass) : (hit & 1 << g) != 0 ? 1 : 0;
        }
        boolean mainScored = !Double.isNaN(aims[GUESS_FACTOR]) && !Double.isNaN(aims[CIRCULAR]);
        for (int g = 0; g < GUNS; g++) {
            if (g == ANTI_SURFER || g == RANDOM || g == MIRROR ? !Double.isNaN(aims[g]) : mainScored) {
                scores[g][1] += weight;
                scores[g][0] += weight * credit[g];
            }
        }
        if (pass.real() && !Double.isNaN(aims[GUESS_FACTOR]) && !Double.isNaN(aims[ANTI_SURFER])) {
            for (int g = 0; g < GUNS; g++) {
                if (!Double.isNaN(aims[g])) {
                    bullets[g][1]++;
                    bullets[g][0] += credit[g];
                }
            }
        }
        if (pass.hits()) {
            double a = shot.direction() * pass.minOffset() / pass.escape();
            double b = shot.direction() * pass.maxOffset() / pass.escape();
            data.add(shot.features(), Math.min(a, b), Math.max(a, b), weight);
            if (pass.real()) {
                for (GunData view : antiSurfer) {
                    view.add(shot.features(), Math.min(a, b), Math.max(a, b), 1);
                }
            }
        }
        return hit;
    }

    /**
     * The share of the random gun's range whose headings would have hit: the chance that its aim, uniform over the
     * range, hits.
     */
    private static double randomCredit(Shot shot, Pass pass) {
        if (!pass.hits()) {
            return 0;
        }
        double a = Angles.normalizeRelative(
                shot.reference() + shot.direction() * shot.randomLow() * shot.escape() - pass.reference());
        double b = Angles.normalizeRelative(
                shot.reference() + shot.direction() * shot.randomHigh() * shot.escape() - pass.reference());
        double low = Math.min(a, b);
        double high = Math.max(a, b);
        if (high <= low) {
            return low >= pass.minOffset() && low <= pass.maxOffset() ? 1 : 0;
        }
        return Math.max(0, Math.min(high, pass.maxOffset()) - Math.max(low, pass.minOffset())) / (high - low);
    }
}
