package cz.libors.ogi.move;

import cz.libors.ogi.enemy.DetectedShot;
import cz.libors.ogi.enemy.FireDetector;
import cz.libors.ogi.enemy.Neighbours;
import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.RobotHistory;
import cz.libors.ogi.gun.TargetingFeatures;
import cz.libors.ogi.physics.EngineRobot;
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
 * Wave surfing ("true surfing"): each turn, moves the way that the nearest enemy wave is least likely to hit.
 *
 * <p>Enemy waves come from {@link FireDetector}. A wave's frame is the bearing from its origin to where we were at the
 * end of the turn before the shot, which is what the enemy aimed with, and our lateral direction then. Bullets that hit
 * us, or that one of our bullets hit, teach the enemy's {@link DangerModel}, together with how we moved when it aimed
 * ({@link TargetingFeatures} of our own history). A new wave gets the model's profile for that situation.
 *
 * <p>For the nearest wave the surfer predicts three ways to move until the wave has passed: orbiting its origin either
 * way, or stopping. The prediction runs the engine's own rules ({@link RobotMotion}), and {@link HitRange} gives the
 * guess factors that would hit us on each path. The way with the least danger in the wave's profile wins, and its
 * first turn's commands are returned.
 *
 * <p>Danger models last for the battle (static, per robot, engine-model G-22); waves and detectors are per round.
 */
public final class WaveSurfer {

    static final int ORBIT_LEFT = 1;
    static final int ORBIT_RIGHT = -1;
    static final int STOP = 0;
    private static final int[] OPTIONS = {ORBIT_LEFT, ORBIT_RIGHT, STOP};

    static final double PREFERRED_DISTANCE = 400;
    private static final double MAX_TILT = 0.5;
    private static final double WALL_STICK = 120;
    private static final double WALL_MARGIN = 25;
    private static final double SMOOTH_STEP = 0.05;
    private static final double DRIVE_DISTANCE = 100;
    private static final int MAX_PREDICTED_TURNS = 120;
    /** How closely a bullet's power must match its wave's. */
    private static final double POWER_TOLERANCE = 0.01;

    private static final Map<String, DangerModel> MODELS = new HashMap<>();

    /** An enemy wave, the frame its guess factors are measured in, and where the enemy is likely to have aimed. */
    private static final class EnemyWave {
        final Wave wave;
        final HitRange range;
        final int direction;
        final double escape;
        /** Our movement as the enemy saw it when it aimed ({@link TargetingFeatures}). */
        final double[] features;
        /** Where the enemy is likely to have aimed, as its {@link DangerModel} saw it when the wave appeared. */
        final DangerModel.Prediction prediction;

        EnemyWave(Wave wave, HitRange range, int direction, double[] features, DangerModel.Prediction prediction) {
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
    }

    private final Field field;
    private final double coolingRate;
    private final Map<String, FireDetector> detectors = new HashMap<>();
    private final Neighbours neighbours = new Neighbours();
    private final List<EnemyWave> waves = new ArrayList<>();
    /** Our own states at the end of recent turns: {x, y, heading, velocity}, by turn. */
    private final Map<Long, double[]> ourStates = new HashMap<>();
    private final RobotHistory ours = new RobotHistory();
    /** Each enemy's scanned positions of recent turns, {x, y}, by turn: where it aimed from. */
    private final Map<String, Map<Long, double[]>> enemyPositions = new HashMap<>();
    private int lastDirection = 1;
    private int lastOption = ORBIT_LEFT;

    public WaveSurfer(Field field, double coolingRate) {
        this.field = field;
        this.coolingRate = coolingRate;
    }

    /** Our state at the end of turn {@code time}; call it every turn before {@link #update}. Heading mathematical. */
    public void ourState(long time, double x, double y, double heading, double velocity) {
        ourStates.put(time, new double[] {x, y, heading, velocity});
        ourStates.remove(time - 64);
        ours.add(time, x, y, heading, velocity);
    }

    /** Our scan of an enemy; a shot it reveals becomes a wave. */
    public void scanned(String name, long time, double x, double y, double velocity, double energy) {
        neighbours.seen(name, time, x, y);
        Map<Long, double[]> positions = enemyPositions.computeIfAbsent(name, n -> new HashMap<>());
        positions.put(time, new double[] {x, y});
        positions.remove(time - 64);
        FireDetector detector = detector(name);
        if (neighbours.nearOther(name, time)) {
            detector.nearOtherRobot();
        }
        detector.scanned(time, x, y, velocity, energy).ifPresent(this::addWave);
    }

    /** Our scan of a teammate, which can ram an enemy out of our sight. */
    public void teammateSeen(String name, long time, double x, double y) {
        neighbours.seen(name, time, x, y);
    }

    public void ourBulletHit(String name, double power) {
        detector(name).ourBulletHit(power);
    }

    public void rammed(String name) {
        detector(name).rammed();
    }

    /** An enemy bullet hit us at the end of its move in turn {@code time}; heading mathematical. */
    public void hitByBullet(String shooter, long time, double x, double y, double heading, double power) {
        detector(shooter).hitUs(power);
        learn(shooter, time, x, y, heading, power);
    }

    /** One of our bullets hit an enemy bullet at the end of its move in turn {@code time}; heading mathematical. */
    public void bulletHitBullet(String shooter, long time, double x, double y, double heading, double power) {
        learn(shooter, time, x, y, heading, power);
    }

    /** A dead robot fires no more, but its bullets in the air still fly and hit, so its waves stay. */
    public void robotDied(String name) {
        detectors.remove(name);
    }

    /**
     * Advances the waves to our position at the end of turn {@code now} and drops those that have passed us; where we
     * were in each of them goes to its shooter's flattener.
     */
    public void update(long now) {
        double[] us = ourStates.get(now);
        if (us == null) {
            return;
        }
        for (Iterator<EnemyWave> it = waves.iterator(); it.hasNext(); ) {
            EnemyWave w = it.next();
            w.range.observe(now, us[0], us[1]);
            if (w.range.passed()) {
                if (w.range.hits()) {
                    double middle = (w.range.minOffset() + w.range.maxOffset()) / 2;
                    model(w.wave.shooter()).visit(w.features, w.direction * middle / w.escape);
                }
                it.remove();
            }
        }
    }

    /**
     * The commands for the next turn, {distance, right turn in radians} as the engine takes them, or empty when no
     * wave is in the air.
     *
     * @param me our state at the end of this turn, in the engine's convention
     */
    public Optional<double[]> command(EngineRobot me, long now) {
        EnemyWave nearest = nearest(me, now);
        if (nearest == null) {
            return Optional.empty();
        }
        int best = lastOption;
        double bestDanger = danger(nearest, me, now, best);
        for (int option : OPTIONS) {
            if (option == lastOption) {
                continue;
            }
            double d = danger(nearest, me, now, option);
            if (d < bestDanger) {
                best = option;
                bestDanger = d;
            }
        }
        lastOption = best;
        return Optional.of(orbit(me, nearest.wave, best));
    }

    /** The wave that reaches us first and has not passed us yet. */
    private EnemyWave nearest(EngineRobot me, long now) {
        EnemyWave best = null;
        double bestTime = Double.POSITIVE_INFINITY;
        for (EnemyWave w : waves) {
            double distance = Math.hypot(me.x - w.wave.x(), me.y - w.wave.y()) - w.wave.radius(now);
            double turns = distance / w.wave.speed();
            if (turns > -1 && turns < bestTime) {
                best = w;
                bestTime = turns;
            }
        }
        return best;
    }

    /** The share of the enemy's aim that would hit us if we moved this way until the wave has passed. */
    private double danger(EnemyWave w, EngineRobot start, long now, int option) {
        EngineRobot r = start.copy();
        HitRange range = w.range.copy();
        for (long t = now + 1; t <= now + MAX_PREDICTED_TURNS && !range.passed(); t++) {
            double[] cmd = orbit(r, w.wave, option);
            r.distanceRemaining = cmd[0];
            r.bodyTurnRemaining = cmd[1];
            RobotMotion.move(r, (int) field.width(), (int) field.height(), coolingRate);
            range.observe(t, r.x, r.y);
        }
        if (!range.hits()) {
            return 0;
        }
        double a = w.direction * range.minOffset() / w.escape;
        double b = w.direction * range.maxOffset() / w.escape;
        return DangerModel.mass(w.prediction.profile(), a, b);
    }

    /**
     * One turn of orbiting the wave's origin in {@code option}'s direction, keeping a preferred distance and sliding
     * along walls; {@link #STOP} brakes. Returns {distance, right turn} in the engine's convention.
     */
    double[] orbit(EngineRobot r, Wave origin, int option) {
        double x = r.x;
        double y = r.y;
        double away = Angles.bearing(origin.x(), origin.y(), x, y);
        double distance = Math.hypot(x - origin.x(), y - origin.y());
        int direction = option == STOP ? lastOption == STOP ? 1 : lastOption : option;
        double tilt = Math.max(-MAX_TILT, Math.min(MAX_TILT, (PREFERRED_DISTANCE - distance) / PREFERRED_DISTANCE));
        double heading = away + direction * (Math.PI / 2 - tilt);
        for (int i = 0; i < Angles.TWO_PI / SMOOTH_STEP; i++) {
            if (field.contains(x + WALL_STICK * Math.cos(heading), y + WALL_STICK * Math.sin(heading), WALL_MARGIN)) {
                break;
            }
            heading -= direction * SMOOTH_STEP;
        }
        double turn = Angles.normalizeRelative(heading - Angles.fromRobocode(r.bodyHeading));
        double drive = DRIVE_DISTANCE;
        if (Math.abs(turn) > Math.PI / 2) {
            turn = Angles.normalizeRelative(turn + Math.PI);
            drive = -drive;
        }
        return new double[] {option == STOP ? 0 : drive, -turn};
    }

    private void addWave(DetectedShot shot) {
        // The enemy aimed with what it saw at the end of the turn before the shot.
        long aimed = ourStates.containsKey(shot.fireTime() - 1) ? shot.fireTime() - 1 : shot.fireTime();
        double[] us = ourStates.get(aimed);
        RobotHistory seen = ours.until(aimed);
        if (us == null || seen.isEmpty() || seen.time() != aimed) {
            return;
        }
        Wave wave = new Wave(shot.shooter(), shot.x(), shot.y(), shot.fireTime(), shot.power());
        double reference = Angles.bearing(wave.x(), wave.y(), us[0], us[1]);
        double[] features = new double[TargetingFeatures.COUNT];
        int direction = TargetingFeatures.compute(wave.x(), wave.y(), seen, wave.speed(), field, lastDirection,
                features);
        lastDirection = direction;
        double[] from = enemyPositions.getOrDefault(shot.shooter(), Map.of()).getOrDefault(aimed,
                new double[] {wave.x(), wave.y()});
        double[] aims = SimpleAims.aims(from[0], from[1], seen, wave.speed(), field);
        double[] simple = new double[SimpleAims.COUNT];
        for (int g = 0; g < SimpleAims.COUNT; g++) {
            simple[g] = direction * Angles.normalizeRelative(aims[g] - reference) / wave.maxEscapeAngle();
        }
        HitRange range = new HitRange(wave, reference, (int) field.width(), (int) field.height());
        // Catch up on the turns since the shot.
        for (long t = shot.fireTime(); t < shot.detectedAt(); t++) {
            double[] s = ourStates.get(t);
            if (s != null) {
                range.observe(t, s[0], s[1]);
            }
        }
        waves.add(new EnemyWave(wave, range, direction, features, model(shot.shooter()).predict(features, simple)));
    }

    /** Matches a bullet of {@code shooter} to its wave by power and travelled distance, and learns its guess factor. */
    private void learn(String shooter, long time, double x, double y, double heading, double power) {
        for (Iterator<EnemyWave> it = waves.iterator(); it.hasNext(); ) {
            EnemyWave w = it.next();
            if (!w.wave.shooter().equals(shooter) || Math.abs(w.wave.power() - power) > POWER_TOLERANCE) {
                continue;
            }
            double travelled = Math.hypot(x - w.wave.x(), y - w.wave.y());
            if (Math.abs(travelled - w.wave.radius(time)) <= w.wave.speed()) {
                model(shooter).learn(w.features, w.prediction, w.guessFactor(heading));
                it.remove();
                return;
            }
        }
    }

    /** Adds the enemy waves in flight to the trace. */
    public void describe(long now, Trace trace) {
        for (EnemyWave w : waves) {
            boolean hits = w.range.hits();
            trace.add(new Trace.WaveView(w.wave.x(), w.wave.y(), w.wave.radius(now), true, true,
                    w.range.reference(), hits ? w.range.minOffset() : Double.NaN,
                    hits ? w.range.maxOffset() : Double.NaN));
        }
    }

    private FireDetector detector(String name) {
        return detectors.computeIfAbsent(name,
                n -> new FireDetector(n, coolingRate, (int) field.width(), (int) field.height()));
    }

    private static DangerModel model(String name) {
        return MODELS.computeIfAbsent(name, n -> new DangerModel(DangerParameters.DEFAULT));
    }
}
