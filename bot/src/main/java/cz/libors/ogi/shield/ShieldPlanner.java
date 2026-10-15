package cz.libors.ogi.shield;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.RobotMotion;
import cz.libors.ogi.wave.BulletPath;
import cz.libors.ogi.wave.Wave;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * How to shoot down one enemy bullet whose heading we expect: the commands of this turn and the shot of the next.
 *
 * <p>Two bullets collide when their moves of a turn cross (engine-model G-04). The enemy's bullet flies along the
 * expected heading from the wave's origin; ours leaves in the next turn from where this turn's move takes us. For
 * each turn t of the flight, the planner aims at points of the enemy bullet's move in t (its middle first) and picks
 * the speed at which our bullet's move of t is centred on that point too, which leaves the most room on both
 * segments, or the nearest speed within the allowed powers at which our move of t still reaches it. Points other than
 * the middle matter when the allowed speeds are few: two bullets of the same speed close in by two moves a turn, so
 * our move of t meets the middle of the enemy's in only half of the turns. Our position decides the angle
 * between the two paths: a robot that stands on the enemy's line can hit its bullet only at its own centre, so a
 * standing robot may also step a tenth of a pixel aside, but only if standing gives less margin than the enemy's aim
 * needs: every move teaches guns that learn our movement (pattern matchers) to expect another.
 *
 * <p>A shot's margin is how far the enemy's heading may be off and the bullets still collide for sure, from the
 * shadows our bullet would cast on the wave ({@link BulletPath#shadows}).
 */
public final class ShieldPlanner {

    /**
     * How far a standing robot may step aside for a shot, in pixels. A pixel gives ten times the margin, but guns
     * that learn our movement learn a pixel's steps too and then miss us by them: against such bots steps of a pixel
     * at the start of each round cost more hits than the margin saved. Of 0.05, 0.1 and 0.25 px, 0.1 lost the fewest
     * points: half of it let more bullets of the slightly noisy guns through, and a quarter pixel taught the learning
     * ones too.
     */
    public static final double WIGGLE = 0.1;
    /** The moves offered to a standing robot, relative to where it stands, and how large each counts. */
    public static final double[] STEPS = {0, WIGGLE, -WIGGLE};
    private static final int[] RANKS = {0, 1, 1};
    /** A margin at which the bullets collide for sure, in radians: guns that aim exactly are met to the last bits. */
    public static final double SURE_MARGIN = 2e-5;
    /**
     * Margins beyond this many radians are not worth more power: about a pixel at 300 px. Some guns aim with a little
     * noise (a few 1e-4 rad), and a wider margin still meets their bullets.
     */
    private static final double ENOUGH_MARGIN = 3e-3;
    /**
     * The enemy's bullet must still be this far outside our box, along its path, when it is shot down. The box is
     * square and axis-aligned (engine-model G-03), so a bullet that comes in along a diagonal touches it 25.5 px from
     * our centre, one along an axis 18 px.
     */
    private static final double CLEARANCE = 2;
    /**
     * How many turns before and past the crossing the margin looks at: two straight paths cross once, so the shadows
     * that can hold the expected heading lie in the turns around it.
     */
    private static final int MARGIN_TURNS = 3;
    /** Where along the enemy bullet's move of a turn we aim: the middle, then towards its ends. */
    private static final double[] POINTS = {0.5, 0.25, 0.75, 0.05, 0.95};

    /**
     * A shield shot: this turn's commands (engine convention: distance, right turns in radians), then the fire
     * command in the next turn with {@code power} along {@code heading} (mathematical). {@code move} is the index of
     * the distance among those offered; {@code margin} is in radians of the enemy's heading; {@code cool} whether the
     * gun will be cool in the next turn, {@code ready} whether it will also be on the heading.
     */
    public record Plan(int move, double distance, double bodyTurn, double gunTurn, double power, double heading,
                       double margin, long crossTurn, boolean cool, boolean ready) {
    }

    /** Intervals {from, to} by their start. */
    private static final Comparator<double[]> BY_START = new Comparator<>() {
        @Override
        public int compare(double[] a, double[] b) {
            return Double.compare(a[0], b[0]);
        }
    };

    private ShieldPlanner() {
    }

    /**
     * The best shot at the bullet of {@code wave} expected along {@code expected} (mathematical), fired in the next
     * turn. Among the shots the gun is ready for with a sure margin ({@link #SURE_MARGIN}): from the smallest move
     * ({@link #STEPS}, standing first) with a shot that reaches {@code neededMargin}, else from any; a power within
     * {@code targetPower} if there is one; then the widest margin up to {@link #ENOUGH_MARGIN}, then the lowest power.
     * Without a sure margin the widest; a shot the gun is ready for first. Empty if no shot can reach the bullet any
     * more.
     *
     * @param me          our state at the end of this turn (engine convention), with the gun heat after a shot fired
     *                    this turn, the adjust flags set and no commands
     * @param moves       the distances we may set this turn: a robot that stands, {@link #STEPS} from where it
     *                    stands; any other, one distance
     * @param bodyTurn    the body turn of this turn (engine convention)
     * @param maxPower    the strongest shot we would fire
     * @param targetPower  the power we would rather stay within: the energy race's
     * @param neededMargin the margin the enemy's aim needs, as far as its recent bullets tell
     */
    public static Optional<Plan> plan(Wave wave, double expected, EngineRobot me, long now, double[] moves,
                                      double bodyTurn, double maxPower, double targetPower, double neededMargin,
                                      Field field, double coolingRate) {
        long fireTime = now + 1;
        double ux = Math.cos(expected);
        double uy = Math.sin(expected);
        double clearance = RobotMotion.HALF_SIZE / Math.max(Math.abs(ux), Math.abs(uy)) + CLEARANCE;
        double enemySpeed = wave.speed();
        double maxSpeed = BulletMotion.speed(RobotMotion.MIN_BULLET_POWER);
        double minSpeed = BulletMotion.speed(Math.max(RobotMotion.MIN_BULLET_POWER,
                Math.min(maxPower, RobotMotion.MAX_BULLET_POWER)));
        List<Plan> plans = new ArrayList<>();
        for (int move = 0; move < moves.length; move++) {
            double step = moves[move];
            EngineRobot moved = me.copy();
            moved.distanceRemaining = step;
            moved.bodyTurnRemaining = bodyTurn;
            RobotMotion.move(moved, (int) field.width(), (int) field.height(), coolingRate);
            boolean cool = moved.gunHeat == 0;
            double along = (moved.x - wave.x()) * ux + (moved.y - wave.y()) * uy;
            for (long t = fireTime + 1; wave.radius(t) <= along - clearance; t++) {
                boolean beyond = false;
                for (double point : POINTS) {
                    double s = (t - wave.fireTime() - 1 + point) * enemySpeed;
                    double cx = wave.x() + s * ux;
                    double cy = wave.y() + s * uy;
                    // Our bullet reaches the point during its move of turn t at speeds between these.
                    double distance = Math.hypot(cx - moved.x, cy - moved.y);
                    double slowest = Math.max(distance / (t - fireTime), minSpeed);
                    double fastest = t - fireTime > 1 ? Math.min(distance / (t - fireTime - 1), maxSpeed) : maxSpeed;
                    if (slowest > fastest) {
                        beyond |= distance / (t - fireTime - 1) < minSpeed;
                        continue;
                    }
                    double speed = Math.max(slowest, Math.min(fastest, distance / (t - fireTime - 0.5)));
                    double power = (20 - speed) / 3;
                    double heading = Angles.bearing(moved.x, moved.y, cx, cy);
                    double gunTurn = Angles.robocodeTurn(Angles.fromRobocode(me.gunHeading), heading);
                    EngineRobot aimed = me.copy();
                    aimed.distanceRemaining = step;
                    aimed.bodyTurnRemaining = bodyTurn;
                    aimed.gunTurnRemaining = gunTurn;
                    RobotMotion.move(aimed, (int) field.width(), (int) field.height(), coolingRate);
                    boolean onHeading = Math.abs(Angles.normalizeRelative(
                            Angles.fromRobocode(aimed.gunHeading) - heading)) < 1e-9;
                    BulletPath path = new BulletPath(null, fireTime, moved.x, moved.y, Math.cos(heading),
                            Math.sin(heading), BulletMotion.speed(power), t + MARGIN_TURNS);
                    plans.add(new Plan(move, step, bodyTurn, gunTurn, power, heading,
                            margin(path.shadows(wave, t - MARGIN_TURNS), expected), t, cool, cool && onHeading));
                }
                if (beyond) {
                    break;
                }
            }
        }
        // Plain loops rather than streams: a lambda's first call costs milliseconds, enough to skip a turn.
        Plan best = null;
        for (int rank = 0; rank <= RANKS[RANKS.length - 1] && best == null; rank++) {
            for (Plan p : plans) {
                if (p.ready() && p.margin() >= SURE_MARGIN && p.margin() >= neededMargin && rank(p) == rank
                        && better(p, best, targetPower)) {
                    best = p;
                }
            }
        }
        if (best == null) {
            for (Plan p : plans) {
                if (p.ready() && p.margin() >= SURE_MARGIN && better(p, best, targetPower)) {
                    best = p;
                }
            }
        }
        if (best == null) {
            for (Plan p : plans) {
                if (p.ready() && p.margin() > 0 && (best == null || p.margin() > best.margin())) {
                    best = p;
                }
            }
        }
        if (best == null) {
            for (Plan p : plans) {
                if (best == null || p.margin() > best.margin()) {
                    best = p;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Whether {@code p} beats {@code q} (none if null): a power within {@code targetPower}, then the wider margin up to
     * {@link #ENOUGH_MARGIN}, then the lower power.
     */
    private static boolean better(Plan p, Plan q, double targetPower) {
        if (q == null) {
            return true;
        }
        boolean pWithin = p.power() <= targetPower;
        boolean qWithin = q.power() <= targetPower;
        if (pWithin != qWithin) {
            return pWithin;
        }
        double pm = Math.min(p.margin(), ENOUGH_MARGIN);
        double qm = Math.min(q.margin(), ENOUGH_MARGIN);
        if (pm != qm) {
            return pm > qm;
        }
        return p.power() < q.power();
    }

    private static int rank(Plan p) {
        return p.move() < RANKS.length ? RANKS[p.move()] : 0;
    }

    /**
     * How far {@code heading} lies inside the sure shadows (chance 1) that contain it: the distance to the nearer edge
     * of their union, in radians; negative, the distance to the nearest sure shadow, if none contains it.
     */
    static double margin(List<double[]> shadows, double heading) {
        List<double[]> sure = new ArrayList<>();
        for (double[] s : shadows) {
            if (s[2] < 1) {
                continue;
            }
            double a = Angles.normalizeRelative(s[0] - heading);
            double b = Angles.normalizeRelative(s[1] - heading);
            if (Math.abs(b - a) < Math.PI) {
                sure.add(new double[] {Math.min(a, b), Math.max(a, b)});
            }
        }
        if (sure.isEmpty()) {
            return Double.NEGATIVE_INFINITY;
        }
        sure.sort(BY_START);
        double nearest = Double.NEGATIVE_INFINITY;
        double lo = sure.get(0)[0];
        double hi = sure.get(0)[1];
        for (int i = 1; i <= sure.size(); i++) {
            if (i < sure.size() && sure.get(i)[0] <= hi) {
                hi = Math.max(hi, sure.get(i)[1]);
                continue;
            }
            if (lo <= 0 && hi >= 0) {
                return Math.min(-lo, hi);
            }
            nearest = Math.max(nearest, -Math.min(Math.abs(lo), Math.abs(hi)));
            if (i < sure.size()) {
                lo = sure.get(i)[0];
                hi = sure.get(i)[1];
            }
        }
        return nearest;
    }
}
