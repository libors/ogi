package cz.libors.ogi.move;

import cz.libors.ogi.enemy.FireDetector;
import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.RobotMotion;
import cz.libors.ogi.wave.BulletPath;
import cz.libors.ogi.wave.HitRange;
import cz.libors.ogi.wave.Wave;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Wave surfing in a duel ("true surfing"): each turn, moves the way that the nearest enemy wave ({@link EnemyWaves})
 * is least likely to hit.
 *
 * <p>For the nearest wave the surfer predicts three ways to move until the wave has passed: orbiting its origin either
 * way, or stopping; and two orbits that lean more steeply towards the preferred distance. Paths that change once from
 * one of the first three to another after a few turns (drive past a point and come back, stop short, wait and go)
 * reach the points between and the same points at other times and speeds; on each orbit, the path that stops where we
 * would stand least exposed to the wave reaches its safest point. The path chosen goes on a turn later as a candidate
 * of its own. The prediction runs the engine's own rules
 * ({@link RobotMotion}), and {@link HitRange} gives the guess factors that would hit us on each path. From where each
 * path leaves us, the next wave is surfed in its three ways, and the best of them counts too. The path with the least
 * expected damage (each wave's profile inside its hit range, times its bullet's damage, less what our bullets shadow)
 * wins, and its first turn's commands are returned.
 *
 * <p>Against an enemy that drives at us ({@link RamDetector}), each way also predicts the enemy: from its last scan it
 * turns towards where we are and drives at full speed. Five ways orbit it instead of the wave's origin, two of them
 * tilting more steeply against the preferred distance; a way that ends nearer to it is more dangerous at any distance,
 * and a way on which it touches us costs a point-blank hit. While no wave is in the air (before its first shot of a
 * round, say), {@link #evade} chooses among the same ways by the contact and its next shot alone.
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

    /** How far one smoothing step moves the stick's end. */
    private static final double SMOOTH_CHORD = 2 * WALL_STICK * Math.sin(SMOOTH_STEP / 2);

    private static final double DRIVE_DISTANCE = 100;

    private static final int MAX_PREDICTED_TURNS = 120;

    private static final double MIN_CLOSING_DISTANCE = 36;

    /** Closing in counts only in a close fight, nearer than this; further away the preferred distance rules. */
    private static final double CLOSE_FIGHT = 250;

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
     * Its next bullet, at the end of the horizon, hits with the chance (this / distance)², at most 1, as such robots'
     * bullets did in our recordings (bot.md "Movement").
     */
    private static final double SURE_HIT_DISTANCE = 50;

    /** The key of the bullet {@link #dangerWithShot} imagines. */
    private static final Object PROBE = new Object();

    /**
     * Turns after which a path changes its way (drives past a point and comes back, stops short, or waits and goes),
     * one of them a turn, in rotation.
     */
    private static final int[] SWITCH_TURNS = {3, 6, 10, 15};

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

    private final EnemyWaves waves;
    private final Field field;
    private final double coolingRate;
    private int lastOption = ORBIT_LEFT;

    /** The path chosen last turn, while no robot drives at us; null otherwise. */
    private Path lastPath;

    /** The wave {@link #lastPath} was chosen against: the centre its ways orbit. */
    private Wave lastSurfed;

    /** The distance the orbit leans towards and the wall smoothing turns towards. */
    private double preferredDistance = PREFERRED_DISTANCE;

    public WaveSurfer(EnemyWaves waves) {
        this.waves = waves;
        this.field = waves.field();
        this.coolingRate = waves.coolingRate();
    }

    /** The distance to keep from now on, instead of {@link #PREFERRED_DISTANCE}. */
    public void preferredDistance(double distance) {
        preferredDistance = distance;
    }

    /**
     * The movement for the next turn, or empty when no wave is in the air.
     *
     * @param me our state at the end of this turn, in the engine's convention
     */
    public Optional<Move> command(EngineRobot me, long now) {
        EnemyWave nearest = nearest(me, now, null);
        if (nearest == null) {
            lastPath = null;
            return Optional.empty();
        }
        return Optional.of(surf(nearest, nearest(me, now, nearest), me, now));
    }

    /**
     * The movement for the next turn while no wave is in the air, if {@code name}'s gun is cool by the end of this turn
     * as {@link FireDetector} models its heat: the wave it can fire next turn is surfed as if it were in the air, so
     * that we dodge its bullet a turn or two before its energy drop shows the shot. Empty while its gun is hot.
     */
    public Optional<Move> gunHeatCommand(EngineRobot me, String name, long now) {
        EnemyWave next = waves.nextWave(name, now);
        return next == null ? Optional.empty() : Optional.of(surf(next, null, me, now));
    }

    private Move surf(EnemyWave nearest, EnemyWave second, EngineRobot me, long now) {
        EngineRobot rammer = rammer(nearest.wave.shooter(), now);
        if (rammer == null) {
            Path best = surfPaths(nearest, second, me, now);
            lastOption = best.first();
            lastPath = best;
            lastSurfed = nearest.wave;
            return orbit(me, nearest.wave.x(), nearest.wave.y(), best.first());
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
        return orbit(me, rammer.x, rammer.y, best);
    }

    /**
     * Where the path chosen in this turn takes us at the end of each of the next {@code turns} turns, from the next one
     * on, its last way held once its turns are over; null if this turn chose no path (no wave in the air, or a robot
     * that drives at us). The surfer chooses again every turn as waves come and pass, so the later turns are a guess.
     *
     * @param me our state at the end of this turn, in the engine's convention, without commands
     */
    public double[][] plannedPositions(EngineRobot me, int turns) {
        if (lastPath == null) {
            return null;
        }
        EngineRobot r = me.copy();
        double[][] positions = new double[turns][];
        for (int i = 0; i < turns; i++) {
            step(r, lastSurfed, null, lastPath.at(i));
            positions[i] = new double[] {r.x, r.y};
        }
        return positions;
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
            predicted.add(new Predicted(Path.held(first), w.damage(range) * closing(w, me, r, null, now), r,
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
        return new Predicted(path, w.damage(range) * closing(w, start, r, null, now), r, nextRange, t);
    }

    /**
     * The movement for the next turn while no wave is in the air, if {@code name} drives at us: the ways around it,
     * each weighed by what the robot costs us besides its waves ({@link #ramDanger}) after {@link #RAM_HORIZON} turns;
     * empty if it does not drive at us. Before its first shot of a round it would otherwise find us orbiting at the
     * preferred distance, which closes in on it from further away.
     */
    public Optional<Move> evade(EngineRobot me, String name, long now) {
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
        waves.shadowAll(BulletPath.fired(PROBE, fireTime, x, y, heading, power, field,
                EnemyWaves.MAX_SHADOW_TURNS));
        double least = Double.POSITIVE_INFINITY;
        // The held ways only: the paths would cost every candidate shot as much as a turn's surfing.
        for (int option : rammer(nearest.wave.shooter(), now) == null ? OPTIONS : RAM_OPTIONS) {
            least = Math.min(least, danger(nearest, second, me, now, Path.held(option), least));
        }
        waves.unshadow(PROBE);
        return least;
    }

    /** The wave that reaches us first and has not passed us yet, other than {@code except}. */
    private EnemyWave nearest(EngineRobot me, long now, EnemyWave except) {
        EnemyWave best = null;
        double bestTime = Double.POSITIVE_INFINITY;
        for (EnemyWave w : waves.inAir()) {
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
            return w.damage(range) * closing(w, start, r, rammer, now) + ramDanger(r, rammer, contact);
        }
        double danger = w.damage(range) * closing(w, start, r, rammer, now) + (contact ? CONTACT_DANGER : 0);
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
            least = Math.min(least, next.damage(qr) * closing(next, start, q, qe, now) + ramDanger(q, qe, touched));
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
        double[] enemy = waves.enemyAt(w.wave.shooter(), now);
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
     * Whether the surfer's ways treat this enemy as one that drives at us (the anti-ram ways): it does, by its scans,
     * and one of them is at most 8 turns old.
     */
    public boolean antiRam(String name, long now) {
        return rammer(name, now) != null;
    }

    /**
     * The enemy as a prediction starts it, from its last scan up to 8 turns old, if it drives at us; else null. Its
     * gun and the rest are left at their defaults: the prediction only moves it.
     */
    private EngineRobot rammer(String name, long now) {
        double[] enemy = waves.drivesAtUs(name) ? waves.enemyAt(name, now) : null;
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

    /**
     * One predicted turn of a way. With a robot that drives at us ({@code rammer}, else null) it orbits the robot
     * instead of the wave's origin (which may then be null), and the robot chases us; returns whether it touched us
     * (the robots' boxes overlap), in which case it is pushed back and stopped as the engine does with the robot at
     * fault.
     */
    private boolean step(EngineRobot r, Wave origin, EngineRobot rammer, int option) {
        Move cmd = rammer == null ? orbit(r, origin.x(), origin.y(), option) : orbit(r, rammer.x, rammer.y, option);
        double x = r.x;
        double y = r.y;
        r.distanceRemaining = cmd.distance();
        r.bodyTurnRemaining = cmd.rightTurn();
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

    /**
     * One turn of orbiting ({@code cx}, {@code cy}) in {@code option}'s direction, keeping a preferred distance and
     * sliding along walls towards it; {@link #STOP} brakes. The retreats lean away from the centre more steeply,
     * against a robot that drives at us.
     */
    Move orbit(EngineRobot r, double cx, double cy, int option) {
        double x = r.x;
        double y = r.y;
        double away = Angles.bearing(cx, cy, x, y);
        double distance = Math.hypot(x - cx, y - cy);
        int direction = Integer.signum(option == STOP ? lastOption == STOP ? 1 : lastOption : option);
        boolean retreat = option == RETREAT_LEFT || option == RETREAT_RIGHT;
        double gain = retreat ? RETREAT_GAIN : 1;
        double limit = retreat ? RETREAT_TILT : MAX_TILT;
        double tilt = Math.max(-limit, Math.min(limit, gain * (preferredDistance - distance) / preferredDistance));
        // At a wall the smoothing turns towards the preferred distance: away from the centre when nearer, towards it
        // when further. Turning away from it at any distance let an enemy that backs off set the distance.
        double heading = smoothed(field, x, y, away + direction * (Math.PI / 2 - tilt),
                distance > preferredDistance ? -direction : direction);
        double turn = Angles.normalizeRelative(heading - Angles.fromRobocode(r.bodyHeading));
        double drive = DRIVE_DISTANCE;
        if (Math.abs(turn) > Math.PI / 2) {
            turn = Angles.normalizeRelative(turn + Math.PI);
            drive = -drive;
        }
        return new Move(option == STOP ? 0 : drive, -turn);
    }

    /**
     * Wall smoothing: the heading turned in steps of {@link #SMOOTH_STEP} against {@code direction} until a stick of
     * {@link #WALL_STICK} along it ends {@link #WALL_MARGIN} inside the field, at most one turn round.
     *
     * <p>A step moves the stick's end by {@link #SMOOTH_CHORD} (6 px), so while the end lies further outside, the next
     * headings fail too: they are stepped over without their sine and cosine. The headings tested and the one returned
     * are the same numbers as with a test at every step.
     */
    static double smoothed(Field field, double x, double y, double heading, int direction) {
        double steps = Angles.TWO_PI / SMOOTH_STEP;
        for (int i = 0; i < steps; i++) {
            double ex = x + WALL_STICK * Math.cos(heading);
            double ey = y + WALL_STICK * Math.sin(heading);
            if (field.contains(ex, ey, WALL_MARGIN)) {
                break;
            }
            heading -= direction * SMOOTH_STEP;
            double outside = Math.max(Math.max(WALL_MARGIN - ex, ex - (field.width() - WALL_MARGIN)),
                    Math.max(WALL_MARGIN - ey, ey - (field.height() - WALL_MARGIN)));
            // A pixel to spare against rounding.
            for (int skip = (int) ((outside - 1) / SMOOTH_CHORD); skip > 0 && i + 1 < steps; skip--) {
                heading -= direction * SMOOTH_STEP;
                i++;
            }
        }
        return heading;
    }
}
