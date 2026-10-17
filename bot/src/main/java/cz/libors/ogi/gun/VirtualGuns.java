package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.RobotMotion;
import cz.libors.ogi.trace.Trace;
import cz.libors.ogi.wave.HitRange;
import cz.libors.ogi.wave.Wave;
import cz.libors.ogi.world.Names;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The virtual guns at each target: guess-factor targeting with nearest neighbours ("dynamic clustering"), circular
 * targeting, an anti-surfer gun, a random gun and a mirror gun, chosen by how often each would have hit.
 *
 * <p>Each turn has a wave: the shot that could have been fired in it. When the wave has passed the target, its
 * {@link HitRange} says which guess factors would have hit, and the shot's features and that range join the data.
 * A new shot looks up the past shots with the most similar features and aims at the guess factor most of them would
 * have hit with.
 *
 * <p>Virtual guns: a plan keeps each gun's aim, the guess-factor one only when it searched its data; a passed wave
 * tells for each gun that aimed whether it would have hit. Per enemy the gun with the better record fires; until the
 * guess-factor gun has {@link GunParameters#minTries() enough} weighted tries, circular targeting does. The
 * anti-surfer gun is chosen on its record over our bullets ({@link GunModel}).
 *
 * <p>Timing (engine-model G-01): the gun fires along the heading it reached in the turn of the fire command, so the
 * aim for a shot in turn t+1 is chosen in turn t, from our predicted position at the end of turn t+1 and the target's
 * scan of turn t. That plan becomes turn t+1's wave, fired or not, with the same features and the same reference.
 *
 * <p>What it knows about each enemy ({@link GunModel}: the data, the scores, the choice of gun) lasts for the whole
 * battle in a {@link Memory}, which the bot keeps in a static (statics are per robot, engine-model G-22). The rest is
 * per round. Every plan
 * and every passed wave can be logged ({@link GunLog}), so that the offline gun bench can replay the battle.
 */
public final class VirtualGuns {

    public static final int GUESS_FACTOR = GunModel.GUESS_FACTOR;
    public static final int CIRCULAR = GunModel.CIRCULAR;
    public static final int ANTI_SURFER = GunModel.ANTI_SURFER;
    public static final int RANDOM = GunModel.RANDOM;
    public static final int MIRROR = GunModel.MIRROR;
    private static final String[] GUN_NAMES = {"gf", "circular", "anti-surfer", "random", "mirror"};

    /**
     * What the guns learned of each enemy over the battle, by its base name: the copies of one robot in an enemy team
     * share it ({@link Names#base}).
     */
    public static final class Memory {
        final Map<String, GunModel> models = new HashMap<>();
    }

    /**
     * The shot planned in turn {@code time} for turn {@code time + 1}.
     *
     * @param shot     the inputs; its reference is the bearing from the planned origin to the target's scan of turn
     *                 {@code time}
     * @param decision each gun's mathematical aim and the gun whose aim the turret follows
     * @param originX  our predicted position at the end of turn {@code time + 1}, the bullet's origin
     * @param offset   added to the chosen gun's aim where the turret goes, not where the guns learn from
     */
    public record Plan(GunModel.Shot shot, GunModel.Aim decision, double power, double originX, double originY,
                       double targetX, double targetY, double offset) {

        public long time() {
            return shot.time();
        }

        /** The heading the gun should reach by the end of the next turn. */
        public double aim() {
            return decision.aim() + offset;
        }
    }

    private record GunWave(Plan plan, HitRange range, boolean real) {
    }

    private static final class Target {
        final RobotHistory history = new RobotHistory();
        final List<GunWave> waves = new ArrayList<>();
        final GunModel model;
        int direction = 1;
        Plan plan;

        Target(GunModel model) {
            this.model = model;
        }
    }

    private final Field field;
    private final Memory memory;
    private final Map<String, Target> targets = new HashMap<>();
    /** The waves that passed in the last {@link #update}, in the order they were learned. */
    private final List<GunModel.Pass> passed = new ArrayList<>();

    /** Guns with a memory of their own, as for a battle of one round. */
    public VirtualGuns(Field field) {
        this(field, new Memory());
    }

    public VirtualGuns(Field field, Memory memory) {
        this.field = field;
        this.memory = memory;
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
        GunModel model = memory.models.get(Names.base(name));
        return model == null ? 0 : model.dataSize();
    }

    /** A gun's weighted virtual hit rate against this enemy, or NaN before it was scored. */
    public double hitRate(String name, int gun) {
        GunModel model = memory.models.get(Names.base(name));
        return model == null ? Double.NaN : model.hitRate(gun);
    }

    /** The shot planned in the previous turn for this one, if any. */
    public Optional<Plan> planFor(String name, long now) {
        return plan(name, now - 1);
    }

    /**
     * Our bullet fired from the plan of turn {@code planTime} at this enemy was shot down by one of its bullets: it
     * never reached the target, so its wave counts as a virtual one (not in the bullet records nor the anti-surfer
     * views).
     */
    public void shotDown(String name, long planTime) {
        Target t = targets.get(name);
        if (t != null) {
            t.waves.replaceAll(w -> w.real && w.plan.time() == planTime ? new GunWave(w.plan, w.range, false) : w);
        }
    }

    /** The shot planned in turn {@code time}, if it is the latest plan for this enemy. */
    public Optional<Plan> plan(String name, long time) {
        Target t = targets.get(name);
        return t == null || t.plan == null || t.plan.time() != time ? Optional.empty() : Optional.of(t.plan);
    }

    /** The waves that passed their target in the last {@link #update}, in the order they were learned. */
    public List<GunModel.Pass> passed() {
        return Collections.unmodifiableList(passed);
    }

    /**
     * Advances every enemy's waves to the end of turn {@code now} and turns last turn's plans into this turn's waves.
     *
     * @param ourX    our position at the end of this turn: the origin of a bullet fired now
     * @param firedAt the enemy we fire at in this turn, or null
     */
    public void update(long now, double ourX, double ourY, String firedAt) {
        passed.clear();
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
                    GunModel.Pass pass = new GunModel.Pass(e.getKey(), w.plan.time(), w.real, w.range.reference(),
                            w.range.wave().maxEscapeAngle(), w.range.hits(), w.range.minOffset(),
                            w.range.maxOffset());
                    t.model.learn(w.plan.shot(), w.plan.decision(), pass);
                    passed.add(pass);
                }
            }
        }
    }

    private GunWave wave(Plan p, long now, double ourX, double ourY, boolean real) {
        Wave wave = new Wave("us", ourX, ourY, now, p.power());
        double reference = Angles.bearing(ourX, ourY, p.targetX(), p.targetY());
        return new GunWave(p, new HitRange(wave, reference, (int) field.width(), (int) field.height()), real);
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
        return aim(name, now, nextX, nextY, power, searchNeighbours, circularAim, 0, 0.5, Double.NaN);
    }

    /**
     * As {@link #aim(String, long, double, double, double, boolean, double)}, with {@code offset} radians added to
     * the turret's aim only (see {@link Plan}), {@code randomDraw}, uniform in [0, 1), the place in the target's
     * {@link EscapeRange} where the random gun aims, and the mirror gun's aim ({@link MirrorAim}), NaN if it has none.
     */
    public double aim(String name, long now, double nextX, double nextY, double power, boolean searchNeighbours,
                      double circularAim, double offset, double randomDraw, double mirrorAim) {
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
        double escape = Math.asin(RobotMotion.MAX_VELOCITY / speed);
        // The random gun's range only when the guns search: it costs two predicted runs of the target.
        double[] range = searchNeighbours
                ? EscapeRange.guessFactors(nextX, nextY, now + 1, t.history.time(), t.history.x(), t.history.y(),
                        t.history.heading(), t.history.velocity(), speed, reference, direction, escape, field)
                : new double[] {Double.NaN, Double.NaN};
        GunModel.Shot shot = new GunModel.Shot(name, now, features, direction, reference, escape, searchNeighbours,
                circularAim, range[0], range[1], randomDraw, mirrorAim);
        t.plan = new Plan(shot, t.model.aim(shot), power, nextX, nextY, t.history.x(), t.history.y(), offset);
        return t.plan.aim();
    }

    /**
     * Headings the turret could take instead of the plan of turn {@code time}, as {heading, share of the best}, when the
     * anti-surfer gun chose it: its peaks ({@link GunModel#antiSurferPeaks}), its own aim first. Empty otherwise.
     */
    public double[][] alternatives(String name, long time, double minShare, int max, double spacing) {
        Optional<Plan> plan = plan(name, time);
        if (plan.isEmpty() || plan.get().decision().gun() != ANTI_SURFER) {
            return new double[0][];
        }
        GunModel.Shot shot = plan.get().shot();
        double[][] found = targets.get(name).model.antiSurferPeaks(shot, minShare, max, spacing);
        for (double[] c : found) {
            c[0] = shot.reference() + shot.direction() * c[0] * shot.escape();
        }
        return found;
    }

    /** Replaces the offset of the plan of turn {@code time}, so that the turret takes another heading. */
    public void offsetPlan(String name, long time, double offset) {
        Target t = targets.get(name);
        if (t != null && t.plan != null && t.plan.time() == time) {
            Plan p = t.plan;
            t.plan = new Plan(p.shot(), p.decision(), p.power(), p.originX(), p.originY(), p.targetX(), p.targetY(),
                    offset);
        }
    }

    /** The name of gun {@code gun} ({@link GunModel}'s index), as the trace shows it. */
    public static String gunName(int gun) {
        return GUN_NAMES[gun];
    }

    /**
     * Adds the latest plans to the trace. The waves of our bullets are the overlay's own ({@link
     * cz.libors.ogi.trace.ShotTrails}): in a melee the melee gun aims them.
     */
    public void describe(long now, Trace trace) {
        for (Map.Entry<String, Target> e : targets.entrySet()) {
            Target t = e.getValue();
            // Robocode paints before the robot acts, so the plan on screen is usually last turn's: this turn's shot.
            if (t.plan != null && t.plan.time() >= now - 1) {
                trace.add(view(e.getKey(), t));
            }
        }
    }

    /**
     * The guess-factor gun's weight along the escape angle of this enemy's latest plan, as {bearings, weights} over
     * {@code bins} guess factors from −1 to 1, for the overlay; null without a plan or data.
     */
    public double[][] planCoverage(String name, int bins) {
        Target t = targets.get(name);
        if (t == null || t.plan == null) {
            return null;
        }
        GunModel.Shot shot = t.plan.shot();
        double[] weights = t.model.coverage(shot.features(), bins);
        if (weights == null) {
            return null;
        }
        double[] bearings = new double[bins];
        for (int b = 0; b < bins; b++) {
            double gf = -1 + 2.0 * b / (bins - 1);
            bearings[b] = shot.reference() + shot.direction() * gf * shot.escape();
        }
        return new double[][] {bearings, weights};
    }

    /** The plan made in turn {@code now} for this enemy, as the trace shows it. */
    public Optional<Trace.GunView> planView(String name, long now) {
        Target t = targets.get(name);
        return t == null || t.plan == null || t.plan.time() != now ? Optional.empty() : Optional.of(view(name, t));
    }

    private Trace.GunView view(String name, Target t) {
        Plan p = t.plan;
        double[] rates = {hitRate(name, GUESS_FACTOR), hitRate(name, CIRCULAR), hitRate(name, ANTI_SURFER),
                hitRate(name, RANDOM), hitRate(name, MIRROR)};
        GunModel model = memory.models.get(Names.base(name));
        double[] bulletRates = new double[rates.length];
        int[] bulletTries = new int[rates.length];
        for (int g = 0; g < rates.length; g++) {
            bulletRates[g] = model == null ? Double.NaN : model.bulletHitRate(g);
            bulletTries[g] = model == null ? 0 : model.bulletTries(g);
        }
        return new Trace.GunView(name, p.time(), p.originX(), p.originY(), p.targetX(), p.targetY(),
                p.shot().reference(), p.shot().escape(), p.power(), p.shot().direction(), p.decision().aims().clone(),
                p.decision().gun(), GUN_NAMES, rates, t.model.dataSize(), bulletRates, bulletTries,
                model != null && model.hardToHit());
    }

    private Target target(String name) {
        return targets.computeIfAbsent(name, n -> new Target(
                memory.models.computeIfAbsent(Names.base(n), k -> new GunModel(GunParameters.DEFAULT))));
    }
}
