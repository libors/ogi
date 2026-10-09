package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.RobotMotion;
import cz.libors.ogi.trace.Trace;
import cz.libors.ogi.wave.HitRange;
import cz.libors.ogi.wave.Wave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Guess-factor targeting with nearest neighbours ("dynamic clustering"), chosen against circular targeting by how
 * often each would have hit.
 *
 * <p>Each turn has a wave: the shot that could have been fired in it. When the wave has passed the target, its
 * {@link HitRange} says which guess factors would have hit, and the shot's features and that range join the data.
 * A new shot looks up the past shots with the most similar features and aims at the guess factor most of them would
 * have hit with.
 *
 * <p>Virtual guns: a plan keeps each gun's aim, the guess-factor one only when it searched its data; a wave whose plan
 * has every aim tells for each whether it would have hit. Per enemy the gun with the better record fires; until the
 * guess-factor gun has {@value #MIN_TRIES} weighted tries, circular targeting does.
 *
 * <p>Timing (engine-model G-01): the gun fires along the heading it reached in the turn of the fire command, so the
 * aim for a shot in turn t+1 is chosen in turn t, from our predicted position at the end of turn t+1 and the target's
 * scan of turn t. That plan becomes turn t+1's wave, fired or not, with the same features and the same reference.
 *
 * <p>The data and the scores of each enemy live in static maps, so they last for the whole battle; statics are per
 * robot (engine-model G-22). The rest is per round.
 */
public final class GuessFactorGun {

    /** A wave without a bullet teaches as much about a target that ignores bullets, but counts less. */
    static final double VIRTUAL_WEIGHT = 0.5;
    /** Weighted tries the guess-factor gun needs before it may replace circular targeting. */
    static final int MIN_TRIES = 20;

    public static final int GUESS_FACTOR = 0;
    public static final int CIRCULAR = 1;
    private static final int GUNS = 2;
    private static final String[] GUN_NAMES = {"gf", "circular"};

    private static final Map<String, GunData> DATA = new HashMap<>();
    /** Per enemy and gun: {hits, tries}, both weighted like the waves. */
    private static final Map<String, double[][]> SCORES = new HashMap<>();

    /**
     * The shot planned in turn {@code time} for turn {@code time + 1}.
     *
     * @param aims      mathematical aim of each gun, NaN if it had none
     * @param gun       the gun whose aim the turret follows
     * @param originX   our predicted position at the end of turn {@code time + 1}, the bullet's origin
     * @param reference bearing from the planned origin to the target's scan of turn {@code time}
     */
    public record Plan(long time, double[] aims, int gun, double power, double originX, double originY,
                       double reference, int direction, double targetX, double targetY, double[] features) {

        /** The heading the gun should reach by the end of the next turn. */
        public double aim() {
            return aims[gun];
        }
    }

    private static final class GunWave {
        final HitRange range;
        final double escape;
        final int direction;
        final double[] features;
        final double weight;
        /** Each gun's aim relative to the wave's reference; NaN if it had none. */
        final double[] offsets;

        GunWave(HitRange range, double escape, int direction, double[] features, double weight, double[] offsets) {
            this.range = range;
            this.escape = escape;
            this.direction = direction;
            this.features = features;
            this.weight = weight;
            this.offsets = offsets;
        }
    }

    private static final class Target {
        final EnemyHistory history = new EnemyHistory();
        final List<GunWave> waves = new ArrayList<>();
        final GunData data;
        final double[][] scores;
        int direction = 1;
        Plan plan;

        Target(GunData data, double[][] scores) {
            this.data = data;
            this.scores = scores;
        }
    }

    private final Field field;
    private final Map<String, Target> targets = new HashMap<>();

    public GuessFactorGun(Field field) {
        this.field = field;
    }

    /** A scan of an enemy at the end of turn {@code time}, ours or a teammate's; heading mathematical. */
    public void scanned(String name, long time, double x, double y, double heading, double velocity) {
        target(name).history.add(time, x, y, heading, velocity);
    }

    public void robotDied(String name) {
        targets.remove(name);
    }

    /** Past shots stored for this enemy. */
    public int dataSize(String name) {
        GunData data = DATA.get(name);
        return data == null ? 0 : data.size();
    }

    /** A gun's weighted virtual hit rate against this enemy, or NaN before it was scored. */
    public double hitRate(String name, int gun) {
        double[][] s = SCORES.get(name);
        return s == null || s[gun][1] == 0 ? Double.NaN : s[gun][0] / s[gun][1];
    }

    /** The shot planned in the previous turn for this one, if any. */
    public Optional<Plan> planFor(String name, long now) {
        Target t = targets.get(name);
        return t == null || t.plan == null || t.plan.time() != now - 1 ? Optional.empty() : Optional.of(t.plan);
    }

    /**
     * Advances every enemy's waves to the end of turn {@code now} and turns last turn's plans into this turn's waves.
     *
     * @param ourX    our position at the end of this turn: the origin of a bullet fired now
     * @param firedAt the enemy we fire at in this turn, or null
     */
    public void update(long now, double ourX, double ourY, String firedAt) {
        for (Map.Entry<String, Target> e : targets.entrySet()) {
            Target t = e.getValue();
            if (t.plan != null && t.plan.time() == now - 1) {
                t.waves.add(wave(t.plan, now, ourX, ourY, e.getKey().equals(firedAt)));
            }
            if (t.history.isEmpty() || t.history.time() != now) {
                continue;
            }
            for (Iterator<GunWave> it = t.waves.iterator(); it.hasNext(); ) {
                GunWave w = it.next();
                w.range.observe(now, t.history.x(), t.history.y());
                if (w.range.passed()) {
                    it.remove();
                    learn(t, w);
                }
            }
        }
    }

    private GunWave wave(Plan p, long now, double ourX, double ourY, boolean real) {
        Wave wave = new Wave("us", ourX, ourY, now, p.power());
        double reference = Angles.bearing(ourX, ourY, p.targetX(), p.targetY());
        HitRange range = new HitRange(wave, reference, (int) field.width(), (int) field.height());
        double[] offsets = new double[GUNS];
        for (int g = 0; g < GUNS; g++) {
            offsets[g] = Double.isNaN(p.aims()[g]) ? Double.NaN : Angles.normalizeRelative(p.aims()[g] - reference);
        }
        return new GunWave(range, wave.maxEscapeAngle(), p.direction(), p.features(), real ? 1 : VIRTUAL_WEIGHT,
                offsets);
    }

    /** A passed wave scores the guns if all of them aimed, and, if any heading hit, becomes data. */
    private static void learn(Target t, GunWave w) {
        boolean scored = true;
        for (double offset : w.offsets) {
            scored &= !Double.isNaN(offset);
        }
        for (int g = 0; g < GUNS && scored; g++) {
            double offset = w.offsets[g];
            t.scores[g][1] += w.weight;
            if (w.range.hits() && offset >= w.range.minOffset() && offset <= w.range.maxOffset()) {
                t.scores[g][0] += w.weight;
            }
        }
        if (w.range.hits()) {
            double a = w.direction * w.range.minOffset() / w.escape;
            double b = w.direction * w.range.maxOffset() / w.escape;
            t.data.add(w.features, Math.min(a, b), Math.max(a, b), w.weight);
        }
    }

    /**
     * Plans the shot for the next turn and returns the aim the turret should follow. The guess-factor gun searches its
     * data only when {@code searchNeighbours} is set, because the gun cannot fire before; without its aim, the
     * circular one is used, or a straight one if that is NaN too. Returns NaN, and plans nothing, for an enemy never
     * scanned.
     *
     * @param nextX       our predicted position at the end of the next turn
     * @param circularAim the circular gun's aim for the same shot
     */
    public double aim(String name, long now, double nextX, double nextY, double power, boolean searchNeighbours,
                      double circularAim) {
        Target t = target(name);
        if (t.history.isEmpty()) {
            t.plan = null;
            return Double.NaN;
        }
        double speed = BulletMotion.speed(power);
        double[] features = new double[TargetingFeatures.COUNT];
        int direction = TargetingFeatures.compute(nextX, nextY, t.history, speed, field, t.direction, features);
        t.direction = direction;
        double reference = Angles.bearing(nextX, nextY, t.history.x(), t.history.y());
        double gf = searchNeighbours ? t.data.bestGuessFactor(features) : Double.NaN;
        double escape = Math.asin(RobotMotion.MAX_VELOCITY / speed);
        double[] aims = new double[GUNS];
        aims[GUESS_FACTOR] = Double.isNaN(gf) ? Double.NaN : reference + direction * gf * escape;
        aims[CIRCULAR] = circularAim;
        int gun = choose(t.scores, aims);
        if (Double.isNaN(aims[gun])) {
            aims[gun] = reference;
        }
        t.plan = new Plan(now, aims, gun, power, nextX, nextY, reference, direction, t.history.x(), t.history.y(),
                features);
        return aims[gun];
    }

    private static int choose(double[][] scores, double[] aims) {
        double[] gf = scores[GUESS_FACTOR];
        double[] circular = scores[CIRCULAR];
        boolean gfBetter = gf[1] >= MIN_TRIES && gf[0] / gf[1] > circular[0] / Math.max(circular[1], 1e-9);
        if (Double.isNaN(aims[CIRCULAR]) || (gfBetter && !Double.isNaN(aims[GUESS_FACTOR]))) {
            return GUESS_FACTOR;
        }
        return CIRCULAR;
    }

    /** Adds the waves in flight and the latest plans to the trace. */
    public void describe(long now, Trace trace) {
        for (Map.Entry<String, Target> e : targets.entrySet()) {
            Target t = e.getValue();
            for (GunWave w : t.waves) {
                Wave wave = w.range.wave();
                boolean hits = w.range.hits();
                trace.add(new Trace.WaveView(wave.x(), wave.y(), wave.radius(now), w.weight == 1, false,
                        w.range.reference(), hits ? w.range.minOffset() : Double.NaN,
                        hits ? w.range.maxOffset() : Double.NaN));
            }
            // Robocode paints before the robot acts, so the plan on screen is usually last turn's: this turn's shot.
            if (t.plan != null && t.plan.time() >= now - 1) {
                trace.add(view(e.getKey(), t));
            }
        }
    }

    /** The plan made in turn {@code now} for this enemy, as the trace shows it. */
    public Optional<Trace.GunView> planView(String name, long now) {
        Target t = targets.get(name);
        return t == null || t.plan == null || t.plan.time() != now ? Optional.empty() : Optional.of(view(name, t));
    }

    private Trace.GunView view(String name, Target t) {
        Plan p = t.plan;
        double[] rates = {hitRate(name, GUESS_FACTOR), hitRate(name, CIRCULAR)};
        double escape = Math.asin(RobotMotion.MAX_VELOCITY / BulletMotion.speed(p.power()));
        return new Trace.GunView(name, p.time(), p.originX(), p.originY(), p.targetX(), p.targetY(), p.reference(),
                escape, p.power(), p.direction(), p.aims().clone(), p.gun(), GUN_NAMES, rates, t.data.size());
    }

    private Target target(String name) {
        return targets.computeIfAbsent(name, n -> new Target(DATA.computeIfAbsent(n, k -> new GunData()),
                SCORES.computeIfAbsent(n, k -> new double[GUNS][2])));
    }
}
