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
import cz.libors.ogi.wave.BulletPath;
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
 * way, or stopping; and two orbits that lean more steeply towards the preferred distance. Paths that change once from
 * one of the first three to another after a few turns (drive past a point and come back, stop short, wait and go)
 * reach the points between and the same points at other times and speeds; on each orbit, the path that stops where we
 * would stand least exposed to the wave reaches its safest point. The path chosen goes on a turn later as a candidate
 * of its own. The prediction runs the engine's own rules
 * ({@link RobotMotion}), and {@link HitRange} gives the guess factors that would hit us on each path. From where each
 * path leaves us, the next wave is surfed in its three ways, and the best of them counts too. The path with the least
 * expected damage (each wave's profile inside its hit range, times its bullet's damage) wins, and its first turn's
 * commands are returned.
 *
 * <p>Bullet shadows: where one of our bullets in the air will cross an enemy wave, the enemy's bullets at those angles
 * would hit ours (engine-model G-04: segments of the same turn that cross always collide; a segment and the other
 * bullet's segment of the turn before collide if the engine happens to move that bullet first), so that part of a hit
 * range is safe, or half safe, and a way's danger counts only its unshadowed share.
 *
 * <p>Against an enemy that drives at us ({@link RamDetector}), each way also predicts the enemy: from its last scan it
 * turns towards where we are and drives at full speed. Five ways orbit it instead of the wave's origin, two of them
 * tilting more steeply against the preferred distance; a way that ends nearer to it is more dangerous at any distance,
 * and a way on which it touches us costs a point-blank hit. While no wave is in the air (before its first shot of a
 * round, say), {@link #evade} chooses among the same ways by the contact and its next shot alone.
 *
 * <p>Danger models, ram detectors and our lateral histories last for the battle (static, per robot, engine-model
 * G-22); waves and fire detectors are per round.
 */
public final class WaveSurfer {

    static final int ORBIT_LEFT = 1;
    static final int ORBIT_RIGHT = -1;
    static final int STOP = 0;
    /** Orbits that lean away from a robot that drives at us more steeply ({@link #RETREAT_TILT}). */
    static final int RETREAT_LEFT = 2;
    static final int RETREAT_RIGHT = -2;
    private static final int[] OPTIONS = {ORBIT_LEFT, ORBIT_RIGHT, STOP};
    /** Against a robot that drives at us: the usual ways around it, and the ways that retreat from it. */
    private static final int[] RAM_OPTIONS = {ORBIT_LEFT, ORBIT_RIGHT, RETREAT_LEFT, RETREAT_RIGHT, STOP};
    /** The ways path surfing holds: the usual three, and two that lean more steeply towards the preferred distance. */
    private static final int[] HELD_OPTIONS = {ORBIT_LEFT, ORBIT_RIGHT, STOP, RETREAT_LEFT, RETREAT_RIGHT};

    static final double PREFERRED_DISTANCE = 325;
    private static final double MAX_TILT = 0.5;
    private static final double WALL_STICK = 120;
    private static final double WALL_MARGIN = 25;
    private static final double SMOOTH_STEP = 0.05;
    private static final double DRIVE_DISTANCE = 100;
    private static final int MAX_PREDICTED_TURNS = 120;
    private static final double MIN_CLOSING_DISTANCE = 36;
    /** Closing in counts only in a close fight, nearer than this; further away the preferred distance rules. */
    private static final double CLOSE_FIGHT = 250;
    /** Danger every way carries, so that the distance to the enemy decides between ways the profile calls safe. */
    private static final double DANGER_FLOOR = 0.02;
    /** Against an enemy that drives at us: how much more steeply the ways lean away from it, and how far at most. */
    private static final double RETREAT_GAIN = 2;
    private static final double RETREAT_TILT = 1.2;
    /** How far past us a simulated rammer aims its move, so that it keeps its speed. */
    private static final double RAM_OVERSHOOT = 100;
    /** A way on which a robot that drives at us touches us costs its next bullet, fired at point blank. */
    private static final double CONTACT_DANGER = BulletMotion.damage(RobotMotion.MAX_BULLET_POWER);
    /**
     * Against a robot that drives at us, every way is predicted at least this many turns, after its waves have passed
     * if need be: about the time it takes to reload a full-power shot, so that the way's end shows where its next
     * bullet finds us.
     */
    private static final int RAM_HORIZON = 20;
    /**
     * Its next bullet, at the end of the horizon, hits with the chance (this / distance)², at most 1: in our
     * recordings such robots hit us with nearly every bullet fired from within about this distance, and further out
     * with a share that falls about with the square of the distance (about 40 % at 80 px, 15 % at 125 px).
     */
    private static final double SURE_HIT_DISTANCE = 50;
    /** How long our bullets are followed for shadows, at most. */
    private static final int MAX_SHADOW_TURNS = 150;
    /** How closely a bullet's power must match its wave's. */
    private static final double POWER_TOLERANCE = 0.01;
    /** The key of the bullet {@link #dangerWithShot} imagines. */
    private static final Object PROBE = new Object();

    /**
     * Turns after which a path changes its way (drives past a point and comes back, stops short, or waits and goes),
     * one of them a turn, in rotation.
     */
    private static final int[] SWITCH_TURNS = {3, 6, 10, 15};

    private static final Map<String, DangerModel> MODELS = new HashMap<>();
    private static final Map<String, RamDetector> RAM_DETECTORS = new HashMap<>();
    /** Our lateral velocity as each enemy saw it, for the battle: what averaged and pattern guns aim with. */
    private static final Map<String, LateralHistory> LATERAL = new HashMap<>();

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
     * A way to move until the nearest wave has passed: {@code first} for {@code turns} turns, then {@code then}.
     * Besides the three ways held throughout, paths that change their way once reach the same points at other times and
     * speeds, and points the held ways miss.
     */
    record Path(int first, int turns, int then) {

        static Path held(int option) {
            return new Path(option, Integer.MAX_VALUE, option);
        }

        /** The way in the {@code turn}-th predicted turn, from 0. */
        int at(int turn) {
            return turn < turns ? first : then;
        }

        /** The same path a turn later. */
        Path next() {
            return first == then ? this : turns > 1 ? new Path(first, turns - 1, then) : held(then);
        }

        /** As {@code equals}, which a record links on its first call at a cost that can skip a turn. */
        boolean sameAs(Path o) {
            return first == o.first && turns == o.turns && then == o.then;
        }
    }

    private final Field field;
    private final double coolingRate;
    private final Map<String, FireDetector> detectors = new HashMap<>();
    private final Neighbours neighbours = new Neighbours();
    private final List<EnemyWave> waves = new ArrayList<>();
    /** Waves whose bullet we learned: no longer surfed, but followed until they pass us, for the flattener. */
    private final List<EnemyWave> spent = new ArrayList<>();
    private final List<BulletPath> ourBullets = new ArrayList<>();
    /** Our own states at the end of recent turns: {x, y, heading, velocity}, by turn. */
    private final Map<Long, double[]> ourStates = new HashMap<>();
    private final RobotHistory ours = new RobotHistory();
    /**
     * Each enemy's scanned states of recent turns, {x, y, heading (mathematical), velocity}, by turn: where it aimed
     * from, and where a robot that drives at us starts in a prediction.
     */
    private final Map<String, Map<Long, double[]>> enemyPositions = new HashMap<>();
    /** Per shooter, the direction of its last wave: the fallback when our lateral velocity is 0 when it aims. */
    private final Map<String, Integer> lastDirection = new HashMap<>();
    private int lastOption = ORBIT_LEFT;
    /** The path chosen last turn, while no robot drives at us; null otherwise. */
    private Path lastPath;

    public WaveSurfer(Field field, double coolingRate) {
        this.field = field;
        this.coolingRate = coolingRate;
    }

    /** Our state at the end of turn {@code time}; call it every turn before {@link #update}. Heading mathematical. */
    public void ourState(long time, double x, double y, double heading, double velocity) {
        ourStates.put(time, new double[] {x, y, heading, velocity});
        ourStates.remove(time - 64);
        ours.add(time, x, y, heading, velocity);
        // This turn's scans came first, so each enemy seen now has its position of this turn.
        enemyPositions.forEach((name, positions) -> {
            double[] e = positions.get(time);
            if (e != null) {
                LATERAL.computeIfAbsent(name, n -> new LateralHistory())
                        .add(time, velocity * Math.sin(heading - Angles.bearing(e[0], e[1], x, y)));
            }
        });
    }

    /** Our scan of an enemy, heading mathematical; a shot it reveals becomes a wave. */
    public void scanned(String name, long time, double x, double y, double heading, double velocity, double energy) {
        neighbours.seen(name, time, x, y);
        Map<Long, double[]> positions = enemyPositions.computeIfAbsent(name, n -> new HashMap<>());
        positions.put(time, new double[] {x, y, heading, velocity});
        positions.remove(time - 64);
        // Scans come before this turn's own state (onScannedRobot runs before act()).
        double[] us = ourStates.containsKey(time) ? ourStates.get(time) : ourStates.get(time - 1);
        if (us != null) {
            double distance = Math.hypot(us[0] - x, us[1] - y);
            double approach = velocity * Math.cos(heading - Angles.bearing(x, y, us[0], us[1]));
            ramDetector(name).observe(distance, approach);
        }
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
        learn(shooter, time, x, y, heading, power, 0);
    }

    /**
     * One of our bullets hit an enemy bullet in turn {@code time}; heading mathematical. The engine reports both
     * bullets where they were before their last move: one move back for a bullet that had moved in that turn, two for
     * one that had not moved yet (engine-model G-04).
     */
    public void bulletHitBullet(String shooter, long time, double x, double y, double heading, double power) {
        learn(shooter, time, x, y, heading, power, 1.5);
    }

    /**
     * We fired a bullet in turn {@code time} from our position at the end of that turn; heading mathematical. It
     * shadows the enemy waves it will cross until {@link #ourBulletEnded} or until it leaves the field.
     */
    public void ourBulletFired(Object id, long time, double x, double y, double heading, double power) {
        BulletPath b = BulletPath.fired(id, time, x, y, heading, power, field, MAX_SHADOW_TURNS);
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

    /** Whether this enemy drives at us, to ram us or to fight at point blank ({@link RamDetector}). */
    public boolean drivesAtUs(String name) {
        RamDetector detector = RAM_DETECTORS.get(name);
        return detector != null && detector.drivesAtUs();
    }

    /** A dead robot fires no more, but its bullets in the air still fly and hit, so its waves stay. */
    public void robotDied(String name) {
        detectors.remove(name);
        // Its waves keep their own origins; a dead robot neither rams nor marks a close fight any more.
        enemyPositions.remove(name);
    }

    /**
     * Advances the waves to our position at the end of turn {@code now} and drops those that have passed us; where we
     * were in each of them goes to its shooter's flattener, also in those whose bullet hit us or one of our bullets.
     */
    public void update(long now) {
        ourBullets.removeIf(b -> b.endTime() < now);
        double[] us = ourStates.get(now);
        if (us == null) {
            return;
        }
        advance(waves, now, us);
        // A gun that learns our movement learns these waves too, whatever became of their bullets.
        advance(spent, now, us);
    }

    private static void advance(List<EnemyWave> list, long now, double[] us) {
        for (Iterator<EnemyWave> it = list.iterator(); it.hasNext(); ) {
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
            lastPath = null;
            return Optional.empty();
        }
        EnemyWave second = nearest(me, now, nearest);
        EngineRobot rammer = rammer(nearest.wave.shooter(), now);
        if (rammer == null) {
            Path best = surfPaths(nearest, second, me, now);
            lastOption = best.first();
            lastPath = best;
            return Optional.of(orbit(me, nearest.wave.x(), nearest.wave.y(), best.first()));
        }
        // Ties keep the last way; a retreat left over from a robot that no longer drives at us becomes an orbit.
        int best = Arrays.stream(RAM_OPTIONS).anyMatch(o -> o == lastOption) ? lastOption : Integer.signum(lastOption);
        double bestDanger = danger(nearest, second, me, now, Path.held(best), Double.POSITIVE_INFINITY);
        for (int option : RAM_OPTIONS) {
            if (option == best) {
                continue;
            }
            double d = danger(nearest, second, me, now, Path.held(option), bestDanger);
            if (d < bestDanger) {
                best = option;
                bestDanger = d;
            }
        }
        lastOption = best;
        lastPath = null;
        return Optional.of(orbit(me, rammer.x, rammer.y, best));
    }

    /**
     * A path predicted until the nearest wave has passed: that wave's danger, and where and when it leaves us. Ordered
     * by the danger without a lambda, whose first call can skip a turn.
     */
    private record Predicted(Path path, double danger, EngineRobot end, HitRange nextRange, long time)
            implements Comparable<Predicted> {

        @Override
        public int compareTo(Predicted o) {
            return Double.compare(danger, o.danger);
        }
    }

    /**
     * The path with the least danger, while no robot drives at us: the ways held until the nearest wave has passed
     * ({@link #HELD_OPTIONS}), the first three each changing once to another of them after this turn's one of
     * {@link #SWITCH_TURNS} if that wave is still on its way then, each orbit stopping where braking would leave us
     * least exposed ({@link #stopExposure}), and last turn's path a turn on, which wins ties. A path found goes on as
     * last turn's path, so the switch turns of a few turns are all tried while one costs no more than a few more ways.
     * Paths that share their first turns are predicted together; the next wave is surfed from the paths in the order
     * of the nearest wave's danger, until that alone reaches the least total.
     */
    private Path surfPaths(EnemyWave w, EnemyWave next, EngineRobot me, long now) {
        Path best = lastPath != null ? lastPath.next() : Path.held(Integer.signum(lastOption));
        double bestDanger = danger(w, next, me, now, best, Double.POSITIVE_INFINITY);
        int switchTurn = SWITCH_TURNS[(int) (now % SWITCH_TURNS.length)];
        List<Predicted> predicted = new ArrayList<>();
        double[] exposed = exposed(w);
        for (int first : HELD_OPTIONS) {
            EngineRobot r = me.copy();
            HitRange range = w.range.copy();
            HitRange nextRange = next == null ? null : next.range.copy();
            long t = now + 1;
            int stopTurn = 0;
            double stopExposure = Double.POSITIVE_INFINITY;
            for (int turn = 0; t <= now + MAX_PREDICTED_TURNS && !range.passed(); t++, turn++) {
                if (turn == switchTurn && Math.abs(first) <= 1) {
                    for (int then : OPTIONS) {
                        if (then != first) {
                            predicted.add(predict(w, me, r.copy(), range.copy(),
                                    nextRange == null ? null : nextRange.copy(), t, now, new Path(first, turn, then)));
                        }
                    }
                }
                step(r, w.wave, null, first);
                range.observe(t, r.x, r.y);
                if (nextRange != null) {
                    nextRange.observe(t, r.x, r.y);
                }
                if (Math.abs(first) == 1 && !range.passed()) {
                    double e = stopExposure(w, r, exposed);
                    if (e < stopExposure) {
                        stopExposure = e;
                        stopTurn = turn + 1;
                    }
                }
            }
            predicted.add(new Predicted(Path.held(first), damage(w, range) * closing(w, me, r, null, now), r,
                    nextRange, t));
            if (stopTurn > 0 && stopTurn != switchTurn) {
                predicted.add(predict(w, me, me.copy(), w.range.copy(), next == null ? null : next.range.copy(),
                        now + 1, now, new Path(first, stopTurn, STOP)));
            }
        }
        predicted.sort(null);
        for (Predicted p : predicted) {
            if (p.danger() >= bestDanger) {
                break;
            }
            if (p.path().sameAs(best)) {
                continue;
            }
            double d = next == null ? p.danger()
                    : p.danger() + following(next, me, p.end(), p.nextRange(), p.time(), now, null);
            if (d < bestDanger) {
                best = p.path();
                bestDanger = d;
            }
        }
        return best;
    }

    /**
     * The wave's predicted aim per guess-factor bin, each bin less the likeliest collision at its middle with one of
     * our bullets: how exposed a robot standing there would be.
     */
    private static double[] exposed(EnemyWave w) {
        double[] profile = w.prediction.profile();
        double[] exposed = Arrays.copyOf(profile, profile.length);
        int middle = DangerModel.BINS / 2;
        for (List<double[]> list : w.shadows.values()) {
            for (double[] s : list) {
                for (int i = DangerModel.bin(s[0]); i <= DangerModel.bin(s[1]); i++) {
                    double gf = (double) (i - middle) / middle;
                    if (s[0] <= gf && gf <= s[1]) {
                        exposed[i] = Math.min(exposed[i], profile[i] * (1 - s[2]));
                    }
                }
            }
        }
        return exposed;
    }

    /**
     * About how exposed to the wave we would be if we braked now and stood where we stop: the bins our box covers
     * there, seen from the wave's origin.
     */
    private double stopExposure(EnemyWave w, EngineRobot r, double[] exposed) {
        double brake = 0;
        for (double v = Math.abs(r.velocity) - RobotMotion.DECELERATION; v > 0; v -= RobotMotion.DECELERATION) {
            brake += v;
        }
        double heading = Angles.fromRobocode(r.bodyHeading) + (r.velocity < 0 ? Math.PI : 0);
        double x = field.clampX(r.x + brake * Math.cos(heading));
        double y = field.clampY(r.y + brake * Math.sin(heading));
        double distance = Math.hypot(x - w.wave.x(), y - w.wave.y());
        double gf = w.direction * Angles.normalizeRelative(Angles.bearing(w.wave.x(), w.wave.y(), x, y)
                - w.range.reference()) / w.escape;
        double half = Math.atan(RobotMotion.HALF_SIZE / distance) / w.escape;
        double sum = 0;
        for (int i = DangerModel.bin(gf - half); i <= DangerModel.bin(gf + half); i++) {
            sum += exposed[i];
        }
        return sum;
    }

    /** Goes on along {@code path} from turn {@code t} until the nearest wave has passed. */
    private Predicted predict(EnemyWave w, EngineRobot start, EngineRobot r, HitRange range, HitRange nextRange, long t,
                              long now, Path path) {
        for (; t <= now + MAX_PREDICTED_TURNS && !range.passed(); t++) {
            step(r, w.wave, null, path.at((int) (t - now - 1)));
            range.observe(t, r.x, r.y);
            if (nextRange != null) {
                nextRange.observe(t, r.x, r.y);
            }
        }
        return new Predicted(path, damage(w, range) * closing(w, start, r, null, now), r, nextRange, t);
    }

    /**
     * The commands for the next turn while no wave is in the air, if {@code name} drives at us: the ways around it, each
     * weighed by what the robot costs us besides its waves ({@link #ramDanger}) after {@link #RAM_HORIZON} turns;
     * empty if it does not drive at us. Before its first shot of a round it would otherwise find us orbiting at the
     * preferred distance, which closes in on it from further away.
     */
    public Optional<double[]> evade(EngineRobot me, String name, long now) {
        EngineRobot rammer = rammer(name, now);
        if (rammer == null) {
            return Optional.empty();
        }
        int best = Arrays.stream(RAM_OPTIONS).anyMatch(o -> o == lastOption) ? lastOption : Integer.signum(lastOption);
        double bestDanger = evasion(me, rammer, best);
        for (int option : RAM_OPTIONS) {
            if (option == best) {
                continue;
            }
            double d = evasion(me, rammer, option);
            if (d < bestDanger) {
                best = option;
                bestDanger = d;
            }
        }
        lastOption = best;
        lastPath = null;
        return Optional.of(orbit(me, rammer.x, rammer.y, best));
    }

    /** What a robot that drives at us costs a way without waves, {@link #RAM_HORIZON} turns long. */
    private double evasion(EngineRobot start, EngineRobot rammer, int option) {
        EngineRobot r = start.copy();
        EngineRobot e = rammer.copy();
        boolean contact = false;
        for (int t = 0; t < RAM_HORIZON; t++) {
            contact |= step(r, null, e, option);
        }
        return ramDanger(r, e, contact);
    }

    /**
     * The least danger of the ways we can surf now, as {@link #command} weighs them, if we also fired a bullet in turn
     * {@code fireTime} from ({@code x}, {@code y}) along {@code heading} (mathematical) at {@code power}; NaN when no
     * wave is in the air. Nothing is kept.
     */
    public double dangerWithShot(EngineRobot me, long now, long fireTime, double x, double y, double heading,
                                 double power) {
        EnemyWave nearest = nearest(me, now, null);
        if (nearest == null) {
            return Double.NaN;
        }
        EnemyWave second = nearest(me, now, nearest);
        BulletPath probe = BulletPath.fired(PROBE, fireTime, x, y, heading, power, field, MAX_SHADOW_TURNS);
        for (EnemyWave w : waves) {
            addShadows(w, probe);
        }
        double least = Double.POSITIVE_INFINITY;
        // The held ways only: the paths would cost every candidate shot as much as a turn's surfing.
        for (int option : rammer(nearest.wave.shooter(), now) == null ? OPTIONS : RAM_OPTIONS) {
            least = Math.min(least, danger(nearest, second, me, now, Path.held(option), least));
        }
        for (EnemyWave w : waves) {
            w.shadows.remove(PROBE);
        }
        return least;
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
     * The damage we expect from the nearest wave if we moved along this path until it has passed, plus the least we
     * expect from the next wave, surfed from there in its best way held. Each wave counts the share of the enemy's aim
     * that would hit us times its bullet's damage. Once the nearest wave alone costs {@code bound} or more, the next
     * wave, which can only add to it, is left out.
     */
    private double danger(EnemyWave w, EnemyWave next, EngineRobot start, long now, Path path, double bound) {
        EngineRobot r = start.copy();
        HitRange range = w.range.copy();
        HitRange nextRange = next == null ? null : next.range.copy();
        EngineRobot rammer = rammer(w.wave.shooter(), now);
        boolean contact = false;
        long t = now + 1;
        for (; t <= now + MAX_PREDICTED_TURNS && !range.passed(); t++) {
            contact |= step(r, w.wave, rammer, path.at((int) (t - now - 1)));
            range.observe(t, r.x, r.y);
            if (nextRange != null) {
                nextRange.observe(t, r.x, r.y);
            }
        }
        if (next == null) {
            if (rammer != null) {
                for (; t <= now + RAM_HORIZON; t++) {
                    contact |= step(r, w.wave, rammer, path.at((int) (t - now - 1)));
                }
            }
            return damage(w, range) * closing(w, start, r, rammer, now) + ramDanger(r, rammer, contact);
        }
        double danger = damage(w, range) * closing(w, start, r, rammer, now) + (contact ? CONTACT_DANGER : 0);
        if (danger >= bound) {
            return danger;
        }
        // The rammer as predicted so far drives the next wave's ways only if it fired that wave too (in melee another
        // robot may have); the next wave of another shooter is surfed around its own origin.
        EngineRobot nextRammer = next.wave.shooter().equals(w.wave.shooter()) ? rammer : null;
        return danger + following(next, start, r, nextRange, t, now, nextRammer);
    }

    /**
     * The least danger of the next wave, surfed in its ways held from where the nearest wave left us ({@code r} in
     * turn {@code t}, the next wave's hit range observed so far); {@code rammer} is the robot that drives at us as
     * predicted so far if it fired that wave, else null.
     */
    private double following(EnemyWave next, EngineRobot start, EngineRobot r, HitRange nextRange, long t, long now,
                             EngineRobot rammer) {
        double least = Double.POSITIVE_INFINITY;
        for (int o : rammer == null ? OPTIONS : RAM_OPTIONS) {
            EngineRobot q = r.copy();
            HitRange qr = nextRange.copy();
            EngineRobot qe = rammer == null ? null : rammer.copy();
            boolean touched = false;
            long u = t;
            for (; u <= now + MAX_PREDICTED_TURNS && !qr.passed(); u++) {
                touched |= step(q, next.wave, qe, o);
                qr.observe(u, q.x, q.y);
            }
            if (qe != null) {
                for (; u <= now + RAM_HORIZON; u++) {
                    touched |= step(q, next.wave, qe, o);
                }
            }
            least = Math.min(least, damage(next, qr) * closing(next, start, q, qe, now) + ramDanger(q, qe, touched));
        }
        return least;
    }

    /**
     * How much more dangerous a way is for ending ({@code end}) closer to the wave's shooter than we are now
     * ({@code start}): the distance now over the distance then, as the angle we fill in the shooter's view grows with
     * nearness; 1 at the same distance; only in a close fight. A rammer that drives at us makes every close option
     * dangerous, and from close up any gun hits. Against an enemy that drives at us ({@code rammer}, where the
     * prediction left it) the distance then is to the predicted enemy, at any distance.
     */
    private double closing(EnemyWave w, EngineRobot start, EngineRobot end, EngineRobot rammer, long now) {
        double[] enemy = enemyAt(w.wave.shooter(), now);
        if (enemy == null) {
            return 1;
        }
        double before = Math.hypot(start.x - enemy[0], start.y - enemy[1]);
        if (rammer == null && before >= CLOSE_FIGHT) {
            return 1;
        }
        double ex = rammer == null ? enemy[0] : rammer.x;
        double ey = rammer == null ? enemy[1] : rammer.y;
        double after = Math.max(MIN_CLOSING_DISTANCE, Math.hypot(end.x - ex, end.y - ey));
        return before / after;
    }

    /**
     * What a robot that drives at us ({@code rammer}, where the prediction left it; null for any other) costs a way
     * besides its waves: a point-blank bullet if it touched us on the way, and its next bullet from where it is at the
     * way's end ({@link #SURE_HIT_DISTANCE}).
     */
    private static double ramDanger(EngineRobot end, EngineRobot rammer, boolean contact) {
        if (rammer == null) {
            return 0;
        }
        double d = Math.max(MIN_CLOSING_DISTANCE, Math.hypot(end.x - rammer.x, end.y - rammer.y));
        double chance = Math.min(1, (SURE_HIT_DISTANCE / d) * (SURE_HIT_DISTANCE / d));
        return (contact ? CONTACT_DANGER : 0) + chance * BulletMotion.damage(RobotMotion.MAX_BULLET_POWER);
    }

    /**
     * The enemy as a prediction starts it, from its last scan up to 8 turns old, if it drives at us; else null. Its
     * gun and the rest are left at their defaults: the prediction only moves it.
     */
    private EngineRobot rammer(String name, long now) {
        double[] enemy = drivesAtUs(name) ? enemyAt(name, now) : null;
        if (enemy == null) {
            return null;
        }
        EngineRobot e = new EngineRobot();
        e.x = enemy[0];
        e.y = enemy[1];
        e.bodyHeading = Angles.toRobocode(enemy[2]);
        e.velocity = enemy[3];
        e.energy = 100;
        return e;
    }

    /**
     * One turn of a robot that drives at us: it turns the shorter way towards where we are (forwards or backwards)
     * and drives past us at full speed.
     */
    private void chase(EngineRobot e, double x, double y) {
        double turn = Angles.normalizeRelative(Angles.toRobocode(Angles.bearing(e.x, e.y, x, y)) - e.bodyHeading);
        double distance = Math.hypot(x - e.x, y - e.y) + RAM_OVERSHOOT;
        if (Math.abs(turn) > Math.PI / 2) {
            turn = Angles.normalizeRelative(turn + Math.PI);
            distance = -distance;
        }
        e.bodyTurnRemaining = turn;
        e.distanceRemaining = distance;
        RobotMotion.move(e, (int) field.width(), (int) field.height(), coolingRate);
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

    /**
     * One predicted turn of a way. With a robot that drives at us ({@code rammer}, else null) it orbits the robot
     * instead of the wave's origin (which may then be null), and the robot chases us; returns whether it touched us
     * (the robots' boxes overlap), in which case it is pushed back and stopped as the engine does with the robot at
     * fault.
     */
    private boolean step(EngineRobot r, Wave origin, EngineRobot rammer, int option) {
        double[] cmd = rammer == null ? orbit(r, origin.x(), origin.y(), option) : orbit(r, rammer.x, rammer.y, option);
        double x = r.x;
        double y = r.y;
        r.distanceRemaining = cmd[0];
        r.bodyTurnRemaining = cmd[1];
        RobotMotion.move(r, (int) field.width(), (int) field.height(), coolingRate);
        if (rammer == null) {
            return false;
        }
        // It acts on where we were at the end of the last turn.
        chase(rammer, x, y);
        double size = 2 * RobotMotion.HALF_SIZE;
        if (Math.abs(rammer.x - r.x) < size && Math.abs(rammer.y - r.y) < size) {
            RobotMotion.ram(rammer);
            return true;
        }
        return false;
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
    /**
     * A shadow's bearings as an interval of guess factors. The second end is taken relative to the first, so a
     * shadow across the bearing opposite the reference stays as narrow as it is instead of wrapping around to cover
     * the whole wave.
     */
    static double[] shadowRange(double reference, int direction, double escape, double from, double to) {
        double g1 = direction * Angles.normalizeRelative(from - reference) / escape;
        double g2 = g1 + direction * Angles.normalizeRelative(to - from) / escape;
        return new double[] {Math.min(g1, g2), Math.max(g1, g2)};
    }

    private void addShadows(EnemyWave w, BulletPath b) {
        List<double[]> found = new ArrayList<>();
        for (double[] angles : b.shadows(w.wave)) {
            double[] range = shadowRange(w.range.reference(), w.direction, w.escape, angles[0], angles[1]);
            found.add(new double[] {range[0], range[1], angles[2]});
        }
        if (!found.isEmpty()) {
            w.shadows.put(b.id(), found);
        }
    }

    /**
     * One turn of orbiting ({@code cx}, {@code cy}) in {@code option}'s direction, keeping a preferred distance and
     * sliding along walls; {@link #STOP} brakes. The retreats lean away from the centre more steeply, against a robot
     * that drives at us. Returns {distance, right turn} in the engine's convention.
     */
    double[] orbit(EngineRobot r, double cx, double cy, int option) {
        double x = r.x;
        double y = r.y;
        double away = Angles.bearing(cx, cy, x, y);
        double distance = Math.hypot(x - cx, y - cy);
        int direction = Integer.signum(option == STOP ? lastOption == STOP ? 1 : lastOption : option);
        boolean retreat = option == RETREAT_LEFT || option == RETREAT_RIGHT;
        double gain = retreat ? RETREAT_GAIN : 1;
        double limit = retreat ? RETREAT_TILT : MAX_TILT;
        double tilt = Math.max(-limit, Math.min(limit, gain * (PREFERRED_DISTANCE - distance) / PREFERRED_DISTANCE));
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
        int direction = TargetingFeatures.compute(wave.x(), wave.y(), seen, wave.speed(), field,
                lastDirection.getOrDefault(shot.shooter(), 1), features);
        lastDirection.put(shot.shooter(), direction);
        double[] from = enemyPositions.getOrDefault(shot.shooter(), Map.of()).getOrDefault(aimed,
                new double[] {wave.x(), wave.y()});
        double[] aims = SimpleAims.aims(from[0], from[1], seen, wave.speed(), field, LATERAL.get(shot.shooter()));
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
        for (BulletPath b : ourBullets) {
            addShadows(w, b);
        }
        waves.add(w);
    }

    /** Matches a bullet of {@code shooter} to its wave by power and travelled distance, and learns its guess factor. */
    /**
     * Learns the wave of a bullet that ended in turn {@code time} at this position, which the engine reported
     * {@code movesBack} bullet moves short of the wave's radius (within one move).
     */
    private void learn(String shooter, long time, double x, double y, double heading, double power,
                       double movesBack) {
        for (Iterator<EnemyWave> it = waves.iterator(); it.hasNext(); ) {
            EnemyWave w = it.next();
            if (!w.wave.shooter().equals(shooter) || Math.abs(w.wave.power() - power) > POWER_TOLERANCE) {
                continue;
            }
            double travelled = Math.hypot(x - w.wave.x(), y - w.wave.y());
            double expected = w.wave.radius(time) - movesBack * w.wave.speed();
            if (Math.abs(travelled - expected) <= w.wave.speed()) {
                model(shooter).learn(w.features, w.prediction, w.guessFactor(heading));
                it.remove();
                spent.add(w);
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

    static DangerModel model(String name) {
        return MODELS.computeIfAbsent(name, n -> new DangerModel(DangerParameters.DEFAULT));
    }

    private static RamDetector ramDetector(String name) {
        return RAM_DETECTORS.computeIfAbsent(name, n -> new RamDetector());
    }
}
