package cz.libors.ogi.move;

import cz.libors.ogi.enemy.DetectedShot;
import cz.libors.ogi.enemy.FireDetector;
import cz.libors.ogi.enemy.Neighbours;
import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.RobotHistory;
import cz.libors.ogi.gun.TargetingFeatures;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.RobotMotion;
import cz.libors.ogi.trace.Trace;
import cz.libors.ogi.wave.HitRange;
import cz.libors.ogi.wave.Wave;

import java.util.ArrayList;
import java.util.Arrays;
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
 * guess factors that would hit us on each path. From where each way leaves us, the next wave is surfed the same way,
 * and its best way counts too. The way with the least expected damage (each wave's profile inside its hit range,
 * times its bullet's damage) wins, and its first turn's commands are returned.
 *
 * <p>Bullet shadows: where one of our bullets in the air will cross an enemy wave, the enemy's bullets at those angles
 * would hit ours (engine-model G-04: segments of the same turn that cross always collide; a segment and the other
 * bullet's segment of the turn before collide if the engine happens to move that bullet first), so that part of a hit
 * range is safe, or half safe, and a way's danger counts only its unshadowed share.
 *
 * <p>Danger models last for the battle (static, per robot, engine-model G-22); waves and detectors are per round.
 */
public final class WaveSurfer {

    static final int ORBIT_LEFT = 1;
    static final int ORBIT_RIGHT = -1;
    static final int STOP = 0;
    private static final int[] OPTIONS = {ORBIT_LEFT, ORBIT_RIGHT, STOP};

    static final double PREFERRED_DISTANCE = 325;
    private static final double MAX_TILT = 0.5;
    private static final double WALL_STICK = 120;
    private static final double WALL_MARGIN = 25;
    private static final double SMOOTH_STEP = 0.05;
    private static final double DRIVE_DISTANCE = 100;
    private static final int MAX_PREDICTED_TURNS = 120;
    private static final double CLOSING_BASE = 2.5;
    private static final double MIN_CLOSING_DISTANCE = 36;
    /** Closing in counts only in a close fight, nearer than this; further away the preferred distance rules. */
    private static final double CLOSE_FIGHT = 250;
    /** Danger every way carries, so that the distance to the enemy decides between ways the profile calls safe. */
    private static final double DANGER_FLOOR = 0.02;
    /** The chance of a collision that needs the engine to move one of the two bullets first. */
    private static final double MIXED_ORDER = 0.5;
    /** How long our bullets are followed for shadows, at most. */
    private static final int MAX_SHADOW_TURNS = 150;
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
        /** Guess-factor intervals {low, high, chance of a collision} that our bullets shadow, by bullet. */
        final Map<Object, List<double[]>> shadows = new HashMap<>();

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

    /**
     * One of our bullets in the air: fired in turn {@code fireTime} from ({@code x}, {@code y}), our position at the
     * end of that turn, it is {@code (t − fireTime) × speed} further along its heading at the end of turn t (as a
     * {@link Wave}), until it leaves the field in turn {@code endTime} at the latest.
     */
    record OurBullet(Object id, long fireTime, double x, double y, double dx, double dy, double speed, long endTime) {

        double x(long t) {
            return x + (t - fireTime) * speed * dx;
        }

        double y(long t) {
            return y + (t - fireTime) * speed * dy;
        }
    }

    private final Field field;
    private final double coolingRate;
    private final Map<String, FireDetector> detectors = new HashMap<>();
    private final Neighbours neighbours = new Neighbours();
    private final List<EnemyWave> waves = new ArrayList<>();
    private final List<OurBullet> ourBullets = new ArrayList<>();
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

    /**
     * We fired a bullet in turn {@code time} from our position at the end of that turn; heading mathematical. It
     * shadows the enemy waves it will cross until {@link #ourBulletEnded} or until it leaves the field.
     */
    public void ourBulletFired(Object id, long time, double x, double y, double heading, double power) {
        double dx = Math.cos(heading);
        double dy = Math.sin(heading);
        double speed = BulletMotion.speed(power);
        double toWall = Double.POSITIVE_INFINITY;
        if (dx > 1e-9) {
            toWall = Math.min(toWall, (field.width() - x) / dx);
        } else if (dx < -1e-9) {
            toWall = Math.min(toWall, -x / dx);
        }
        if (dy > 1e-9) {
            toWall = Math.min(toWall, (field.height() - y) / dy);
        } else if (dy < -1e-9) {
            toWall = Math.min(toWall, -y / dy);
        }
        long end = time + Math.min(MAX_SHADOW_TURNS, (long) Math.ceil(toWall / speed));
        OurBullet b = new OurBullet(id, time, x, y, dx, dy, speed, end);
        ourBullets.add(b);
        for (EnemyWave w : waves) {
            addShadows(w, b);
        }
    }

    /** Our bullet hit a robot or a bullet: it shadows nothing more. */
    public void ourBulletEnded(Object id) {
        ourBullets.removeIf(b -> b.id().equals(id));
        for (EnemyWave w : waves) {
            w.shadows.remove(id);
        }
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
        ourBullets.removeIf(b -> b.endTime() < now);
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
        EnemyWave nearest = nearest(me, now, null);
        if (nearest == null) {
            return Optional.empty();
        }
        EnemyWave second = nearest(me, now, nearest);
        int best = lastOption;
        double bestDanger = danger(nearest, second, me, now, best);
        for (int option : OPTIONS) {
            if (option == lastOption) {
                continue;
            }
            double d = danger(nearest, second, me, now, option);
            if (d < bestDanger) {
                best = option;
                bestDanger = d;
            }
        }
        lastOption = best;
        return Optional.of(orbit(me, nearest.wave, best));
    }

    /** The wave that reaches us first and has not passed us yet, other than {@code except}. */
    private EnemyWave nearest(EngineRobot me, long now, EnemyWave except) {
        EnemyWave best = null;
        double bestTime = Double.POSITIVE_INFINITY;
        for (EnemyWave w : waves) {
            if (w == except) {
                continue;
            }
            double distance = Math.hypot(me.x - w.wave.x(), me.y - w.wave.y()) - w.wave.radius(now);
            double turns = distance / w.wave.speed();
            if (turns > -1 && turns < bestTime) {
                best = w;
                bestTime = turns;
            }
        }
        return best;
    }

    /**
     * The damage we expect from the nearest wave if we moved this way until it has passed, plus the least we expect
     * from the next wave, surfed from there in its best way. Each wave counts the share of the enemy's aim that would
     * hit us times its bullet's damage.
     */
    private double danger(EnemyWave w, EnemyWave next, EngineRobot start, long now, int option) {
        EngineRobot r = start.copy();
        HitRange range = w.range.copy();
        HitRange nextRange = next == null ? null : next.range.copy();
        long t = now + 1;
        for (; t <= now + MAX_PREDICTED_TURNS && !range.passed(); t++) {
            step(r, w.wave, option);
            range.observe(t, r.x, r.y);
            if (nextRange != null) {
                nextRange.observe(t, r.x, r.y);
            }
        }
        double danger = damage(w, range) * closing(w, start, r, now);
        if (next == null) {
            return danger;
        }
        double least = Double.POSITIVE_INFINITY;
        for (int o : OPTIONS) {
            EngineRobot q = r.copy();
            HitRange qr = nextRange.copy();
            for (long u = t; u <= now + MAX_PREDICTED_TURNS && !qr.passed(); u++) {
                step(q, next.wave, o);
                qr.observe(u, q.x, q.y);
            }
            least = Math.min(least, damage(next, qr) * closing(next, start, q, now));
        }
        return danger + least;
    }

    /**
     * How much more dangerous a way is for ending ({@code end}) closer to the wave's shooter than we are now
     * ({@code start}), as Diamond weighs it: {@code 2.5^(now / then) / 2.5}, 1 at the same distance; only in a close
     * fight. A rammer that drives at us makes every close option dangerous, and from close up any gun hits.
     */
    private double closing(EnemyWave w, EngineRobot start, EngineRobot end, long now) {
        double[] enemy = enemyAt(w.wave.shooter(), now);
        if (enemy == null) {
            return 1;
        }
        double before = Math.hypot(start.x - enemy[0], start.y - enemy[1]);
        if (before >= CLOSE_FIGHT) {
            return 1;
        }
        double after = Math.max(MIN_CLOSING_DISTANCE, Math.hypot(end.x - enemy[0], end.y - enemy[1]));
        return Math.pow(CLOSING_BASE, before / after) / CLOSING_BASE;
    }

    /** The enemy's last scanned position up to 8 turns old, or null. */
    private double[] enemyAt(String name, long now) {
        Map<Long, double[]> positions = enemyPositions.get(name);
        if (positions == null) {
            return null;
        }
        for (long t = now; t >= now - 8; t--) {
            double[] p = positions.get(t);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    private void step(EngineRobot r, Wave origin, int option) {
        double[] cmd = orbit(r, origin, option);
        r.distanceRemaining = cmd[0];
        r.bodyTurnRemaining = cmd[1];
        RobotMotion.move(r, (int) field.width(), (int) field.height(), coolingRate);
    }

    /** The share of the wave's predicted aim inside this hit range, times its bullet's damage. */
    private static double damage(EnemyWave w, HitRange range) {
        if (!range.hits()) {
            return DANGER_FLOOR * BulletMotion.damage(w.wave.power());
        }
        double a = w.direction * range.minOffset() / w.escape;
        double b = w.direction * range.maxOffset() / w.escape;
        double unshadowed = 1 - shadowedShare(w, Math.min(a, b), Math.max(a, b));
        return (DangerModel.mass(w.prediction.profile(), a, b) + DANGER_FLOOR) * unshadowed
                * BulletMotion.damage(w.wave.power());
    }

    /**
     * The share of the wave's predicted aim between guess factors {@code low} and {@code high} that is shadowed, each
     * guess factor counted by the likeliest collision there.
     */
    private static double shadowedShare(EnemyWave w, double low, double high) {
        if (w.shadows.isEmpty() || high <= low) {
            return 0;
        }
        List<double[]> parts = new ArrayList<>();
        for (List<double[]> list : w.shadows.values()) {
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
        double[] profile = w.prediction.profile();
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

    /** Adds the guess factors of {@code w} that bullet {@code b} shadows, with the chance of a collision there. */
    private void addShadows(EnemyWave w, OurBullet b) {
        List<double[]> found = new ArrayList<>();
        for (double[] angles : shadowAngles(w.wave, b)) {
            double g1 = w.guessFactor(angles[0]);
            double g2 = w.guessFactor(angles[1]);
            found.add(new double[] {Math.min(g1, g2), Math.max(g1, g2), angles[2]});
        }
        if (!found.isEmpty()) {
            w.shadows.put(b.id(), found);
        }
    }

    /**
     * The bearings from the wave's origin, as {from, to, chance} along the bullet's move, of the wave's bullets that
     * bullet {@code b} would hit (engine-model G-04). In every turn both are in the air: the part of the bullet's move
     * in that turn that lies between the wave's radii at the start and the end of the turn crosses those bullets'
     * moves of the same turn, a collision whatever order the engine moves them in (chance 1). The crossings of one
     * bullet's move with the other's move of the turn before happen only if the engine moves that bullet first, which
     * is random (chance ½).
     */
    static List<double[]> shadowAngles(Wave wave, OurBullet b) {
        List<double[]> found = new ArrayList<>();
        for (long t = Math.max(b.fireTime(), wave.fireTime()) + 1; t <= b.endTime(); t++) {
            crossings(found, wave, b, t, wave.radius(t - 1), wave.radius(t), 1);
            if (t - 1 > wave.fireTime()) {
                // We move first and hit the enemy's bullet through its move of the turn before.
                crossings(found, wave, b, t, wave.radius(t - 2), wave.radius(t - 1), MIXED_ORDER);
            }
            if (t - 1 > b.fireTime()) {
                // The enemy's bullet moves first and hits ours through our move of the turn before.
                crossings(found, wave, b, t - 1, wave.radius(t - 1), wave.radius(t), MIXED_ORDER);
            }
        }
        return found;
    }

    /** Adds the bearings of the part of our bullet's move in turn {@code t} between radii {@code r1} and {@code r2}. */
    private static void crossings(List<double[]> found, Wave wave, OurBullet b, long t, double r1, double r2,
                                  double chance) {
        double ax = b.x(t - 1);
        double ay = b.y(t - 1);
        double fx = ax - wave.x();
        double fy = ay - wave.y();
        double ddx = b.x(t) - ax;
        double ddy = b.y(t) - ay;
        double c2 = ddx * ddx + ddy * ddy;
        double c1 = 2 * (fx * ddx + fy * ddy);
        double c0 = fx * fx + fy * fy;
        double[] outer = within(c2, c1, c0, r2);
        if (outer == null) {
            return;
        }
        double lo = Math.max(0, outer[0]);
        double hi = Math.min(1, outer[1]);
        if (hi < lo) {
            return;
        }
        double[] inner = within(c2, c1, c0, r1);
        if (inner == null || inner[1] <= lo || inner[0] >= hi) {
            found.add(bearings(wave, ax, ay, ddx, ddy, lo, hi, chance));
        } else {
            if (inner[0] > lo) {
                found.add(bearings(wave, ax, ay, ddx, ddy, lo, inner[0], chance));
            }
            if (inner[1] < hi) {
                found.add(bearings(wave, ax, ay, ddx, ddy, inner[1], hi, chance));
            }
        }
    }

    /** The interval of λ where |f + λ·d| ≤ r for the quadratic c2·λ² + c1·λ + c0 = |f + λ·d|², or null. */
    private static double[] within(double c2, double c1, double c0, double r) {
        double disc = c1 * c1 - 4 * c2 * (c0 - r * r);
        if (disc < 0 || c2 == 0) {
            return null;
        }
        double root = Math.sqrt(disc);
        return new double[] {(-c1 - root) / (2 * c2), (-c1 + root) / (2 * c2)};
    }

    /** {bearing from the wave's origin of a + λ·d at λ = {@code from}, the same at {@code to}, {@code chance}}. */
    private static double[] bearings(Wave wave, double ax, double ay, double dx, double dy, double from, double to,
                                     double chance) {
        return new double[] {Angles.bearing(wave.x(), wave.y(), ax + from * dx, ay + from * dy),
                Angles.bearing(wave.x(), wave.y(), ax + to * dx, ay + to * dy), chance};
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
        EnemyWave w = new EnemyWave(wave, range, direction, features, model(shot.shooter()).predict(features, simple));
        for (OurBullet b : ourBullets) {
            addShadows(w, b);
        }
        waves.add(w);
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
