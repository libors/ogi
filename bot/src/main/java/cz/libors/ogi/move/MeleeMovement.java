package cz.libors.ogi.move;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.trace.Trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Minimum-risk movement for a melee: every turn, points around us are rated by how much the enemies threaten each of
 * them, and we drive towards the safest. In a melee most points come from surviving, and most robots fire at the
 * enemy closest to them, so the safest place is far from everybody and nobody's closest robot.
 *
 * <p>The risk of a destination D is the sum over the living enemies of
 * {@code threat · perpendicularity / (distance² · (1 + robots closer to the enemy than D))} (the weights and
 * exponents are {@link MeleeMovementParameters}):
 * <ul>
 *   <li><b>threat:</b> the enemy's energy over ours, within limits: a strong enemy fires longer and harder;</li>
 *   <li><b>distance</b> from D to the enemy: the angle we fill in its view, and so its chance to hit, falls with it,
 *       and so does its chance to pick us;</li>
 *   <li><b>robots closer to the enemy than D</b> (enemies and teammates): with each one it is less likely to aim at
 *       us there;</li>
 *   <li><b>perpendicularity:</b> 1 plus a share of |cos| of the angle between our way to D and the line from D to the
 *       enemy: driving straight at or away from a shooter is what its head-on and linear guns hit.</li>
 * </ul>
 * Teammates repel too. The sum grows with our recent positions near D, so that we do not keep to one spot that guns
 * learn.
 *
 * <p>The candidates lie on a circle around us at random distances, but never further than a share of the distance
 * to the closest enemy, so that we do not drive past it; points too close to a wall are left out. The destination of
 * the last turn competes with its score lowered, so that a near tie does not turn us back and forth.
 *
 * <p>The enemy waves in the air count too: for the candidates with the least risk, the damage we expect on the way
 * there ({@link EnemyWaves#meleeDanger}), each wave weighted by the chance that its shooter aimed at us. A
 * candidate's score is its risk over the median risk of those candidates plus a weight times its waves' damage over
 * their median.
 *
 * <p>The choice among the candidates ({@link #choose}) is what the offline position bench replays with other
 * parameters, from the decisions {@code OgiGunLog} logs ({@link #logging}).
 */
public final class MeleeMovement {

    /** Candidate destinations rated per turn. */
    static final int CANDIDATES = 40;
    /** How far the candidates lie from us, at random between these. */
    static final double MIN_REACH = 80;
    static final double MAX_REACH = 220;
    /** The candidates lie at most this share of the closest enemy's distance away. */
    static final double CLOSEST_SHARE = 0.6;
    /** Candidates keep this far from every wall. */
    static final double WALL_MARGIN = 45;
    /** Nearer to the destination than this, it is reached: new candidates only. */
    static final double ARRIVED = 20;
    /** Our recent positions: one every this many turns, this many of them (how much each repels: the parameters). */
    public static final int HISTORY_EVERY = 5;
    static final int HISTORY_SIZE = 20;
    /** A robot with this many enemies or more within {@link #GANG_RADIUS} is ganged. */
    public static final int GANG_SIZE = 2;
    public static final double GANG_RADIUS = 250;
    /** The debug property of a logged decision ({@link #logging}). */
    public static final String LOG = "meleemove";
    /** What the gang defence makes of us in this turn ({@link #pullRole}). */
    public static final int NO_PULL = 0;
    /** Ganged: drawn to our nearest teammate. */
    public static final int GANGED = 1;
    /** A helper: drawn to the nearest ganged teammate. */
    public static final int HELPER = 2;
    /** With one enemy left and teammates alive: drawn to the enemy ({@link MeleeMovementParameters#finishDistance}). */
    public static final int FINISHER = 3;

    /** A robot to keep away from: position, energy, and whether it is an enemy (else a teammate). */
    public record Robot(double x, double y, double energy, boolean enemy) {
    }

    /** The enemy waves' damage on the way to a point, for {@link #choose}. */
    public interface WaveSource {
        double damage(double x, double y);
    }

    private final Field field;
    private final Random random;
    private final MeleeMovementParameters parameters;
    private final Deque<double[]> history = new ArrayDeque<>();
    private double destinationX = Double.NaN;
    private double destinationY = Double.NaN;
    /** The robot this turn's destinations are drawn to (the team pull) and the distance it stops at; NaN: none. */
    private double pullX = Double.NaN;
    private double pullY = Double.NaN;
    private double pullFloor;
    /** How hard the pull draws: the gang defence's or the finisher's. */
    private double pullStrength;
    private int pullRole;
    /** Whether the enemies gang our robots, so that the pull is on ({@link #gangDefence}). */
    private boolean gangDefence;
    /** Whether {@link #command} keeps its choice for the overlay ({@link #traced}). */
    private boolean tracing;
    private Trace.MoveView traced;
    /** Whether {@link #command} logs its decisions ({@link #logLine}). */
    private boolean logging;
    private String logLine;

    public MeleeMovement(Field field, Random random) {
        this(field, random, MeleeMovementParameters.DEFAULT);
    }

    public MeleeMovement(Field field, Random random, MeleeMovementParameters parameters) {
        this.field = field;
        this.random = random;
        this.parameters = parameters;
    }

    /**
     * Whether {@link #command} logs every {@link #HISTORY_EVERY}th turn's decision for the position bench: our state,
     * the robots it kept away from, every candidate with its waves' damage (also those the choice did not predict) and
     * the destination chosen. Predicting every candidate costs about five times the waves' time.
     */
    public void logging(boolean on) {
        logging = on;
    }

    /** The decision {@link #command} logged in this turn, or null. */
    public String logLine() {
        return logLine;
    }

    /** What the gang defence made of us in the last turn: {@link #NO_PULL}, {@link #GANGED} or {@link #HELPER}. */
    public int pullRole() {
        return pullRole;
    }

    /** The robot the gang defence draws us to, {x, y}, or NaN. */
    public double[] pullTarget() {
        return new double[] {pullX, pullY};
    }

    /** Whether {@link #command} keeps its choice for the overlay; it costs a few arrays a turn. */
    public void tracing(boolean on) {
        tracing = on;
    }

    /** The last traced choice ({@link #tracing}), null if none. */
    public Trace.MoveView traced() {
        return traced;
    }

    /** Turns the team pull on or off: on against enemies that gang our robots. */
    public void gangDefence(boolean on) {
        gangDefence = on;
    }

    /**
     * The movement for the next turn towards the safest destination, by the position alone.
     *
     * @param heading our body heading, mathematical
     */
    public Move command(long now, double x, double y, double heading, double energy, List<Robot> robots) {
        return command(now, x, y, heading, energy, robots, null, null);
    }

    /**
     * The same with the enemy waves in the air counted ({@link EnemyWaves#meleeDanger}) unless {@code inAir} is null.
     *
     * @param me our state at the end of this turn, in the engine's convention, without commands
     */
    public Move command(long now, double x, double y, double heading, double energy, List<Robot> robots,
                        EnemyWaves inAir, EngineRobot me) {
        remember(now, x, y);
        double closest = Double.POSITIVE_INFINITY;
        for (Robot r : robots) {
            if (r.enemy()) {
                closest = Math.min(closest, Math.hypot(r.x() - x, r.y() - y));
            }
        }
        // Candidates {x, y, kept}: last turn's destination first, if it still counts.
        double[][] points = new double[CANDIDATES + 1][];
        int n = 0;
        if (!Double.isNaN(destinationX) && Math.hypot(destinationX - x, destinationY - y) > ARRIVED
                && field.contains(destinationX, destinationY, WALL_MARGIN)) {
            points[n++] = new double[] {destinationX, destinationY, 1};
        }
        double reach = Math.min(MAX_REACH, CLOSEST_SHARE * closest);
        for (int i = 0; i < CANDIDATES; i++) {
            double angle = Angles.TWO_PI * (i + random.nextDouble()) / CANDIDATES;
            double d = Math.min(reach, MIN_REACH + (MAX_REACH - MIN_REACH) * random.nextDouble());
            double px = x + d * Math.cos(angle);
            double py = y + d * Math.sin(angle);
            if (field.contains(px, py, WALL_MARGIN)) {
                points[n++] = new double[] {px, py, 0};
            }
        }
        double[][] candidates = Arrays.copyOf(points, n);
        WaveSource waves = inAir == null ? null : new InAir(inAir, me, now);
        List<double[]> waved = tracing ? new ArrayList<>() : null;
        double[] best = choose(x, y, energy, robots, candidates, waves, waved);
        double bestX = best[0];
        double bestY = best[1];
        if (Double.isNaN(bestX)) {
            // Every candidate was too close to a wall: head for the middle.
            bestX = field.width() / 2;
            bestY = field.height() / 2;
        }
        destinationX = bestX;
        destinationY = bestY;
        logLine = logging && now % HISTORY_EVERY == 0
                ? logLine(now, x, y, heading, energy, robots, candidates, waves) : null;
        traced = tracing ? trace(x, y, energy, robots, waved) : null;
        return Move.to(x, y, heading, bestX, bestY);
    }

    /** Our position goes to the recent ones every {@link #HISTORY_EVERY} turns (the bench replays it so too). */
    public void remember(long now, double x, double y) {
        if (now % HISTORY_EVERY == 0) {
            history.addFirst(new double[] {x, y});
            if (history.size() > HISTORY_SIZE) {
                history.removeLast();
            }
        }
    }

    /**
     * The destination among {@code candidates} ({x, y, kept}, kept being 1 for last turn's destination): the least
     * risk, last turn's destination at {@code keep} times its own, or with {@code waves} the least score among the
     * candidates with the least risk ({@link #withWaves}); {x, y}, NaN without candidates. The candidates scored
     * against the waves go to {@code waved} unless it is null.
     */
    public double[] choose(double x, double y, double energy, List<Robot> robots, double[][] candidates,
                           WaveSource waves, List<double[]> waved) {
        double[][] distances = distances(robots);
        pull(x, y, energy, robots);
        int n = candidates.length;
        // {x, y, risk, kept}
        double[][] scored = new double[n][];
        for (int i = 0; i < n; i++) {
            double[] c = candidates[i];
            scored[i] = new double[] {c[0], c[1], risk(x, y, c[0], c[1], energy, robots, distances), c[2]};
        }
        if (waves == null) {
            double best = Double.POSITIVE_INFINITY;
            double[] chosen = {Double.NaN, Double.NaN};
            for (int i = 0; i < n; i++) {
                double score = scored[i][2] * (scored[i][3] > 0 ? parameters.keep() : 1);
                if (score < best) {
                    best = score;
                    chosen = scored[i];
                }
            }
            return new double[] {chosen[0], chosen[1]};
        }
        if (n == 0) {
            return new double[] {Double.NaN, Double.NaN};
        }
        double[] best = withWaves(scored, n, waves, waved);
        return new double[] {best[0], best[1]};
    }

    /** The enemy waves' damage on the way to a point, as {@link EnemyWaves#meleeDanger} predicts it. */
    private static final class InAir implements WaveSource {
        private final EnemyWaves waves;
        private final EngineRobot me;
        private final long now;

        InAir(EnemyWaves waves, EngineRobot me, long now) {
            this.waves = waves;
            this.me = me;
            this.now = now;
        }

        @Override
        public double damage(double x, double y) {
            return waves.meleeDanger(me, now, x, y);
        }
    }

    /**
     * {@code now;x;y;heading;energy;gangDefence;robots;candidates;destination}: robots as {@code x,y,energy,E} (an
     * enemy) or {@code ...,T} (a teammate), candidates as {@code x,y,kept,waves} (NaN without waves), separated by
     * spaces; numbers as {@link Double#toString}, which reads back exactly. A string builder, not concatenation: its
     * first call would link an {@code invokedynamic} call site (bot.md).
     */
    private String logLine(long now, double x, double y, double heading, double energy, List<Robot> robots,
                           double[][] candidates, WaveSource waves) {
        StringBuilder sb = new StringBuilder(64 * candidates.length + 64 * robots.size() + 128);
        sb.append(now).append(';').append(x).append(';').append(y).append(';').append(heading).append(';')
                .append(energy).append(';').append(gangDefence ? 1 : 0).append(';');
        for (int i = 0; i < robots.size(); i++) {
            Robot r = robots.get(i);
            sb.append(i == 0 ? "" : " ").append(r.x()).append(',').append(r.y()).append(',').append(r.energy())
                    .append(',').append(r.enemy() ? 'E' : 'T');
        }
        sb.append(';');
        for (int i = 0; i < candidates.length; i++) {
            double[] c = candidates[i];
            sb.append(i == 0 ? "" : " ").append(c[0]).append(',').append(c[1]).append(',').append((int) c[2])
                    .append(',').append(waves == null ? Double.NaN : waves.damage(c[0], c[1]));
        }
        sb.append(';').append(destinationX).append(',').append(destinationY);
        return sb.toString();
    }

    /**
     * The choice for the overlay: the candidates also scored against the waves, and the chosen destination's risk by
     * robot (whom we fear there), with what the crowding and the pull multiply it by.
     */
    private Trace.MoveView trace(double x, double y, double energy, List<Robot> robots, List<double[]> waved) {
        double[][] shown = new double[waved.size()][];
        for (int i = 0; i < shown.length; i++) {
            shown[i] = new double[] {waved.get(i)[0], waved.get(i)[1], waved.get(i)[2]};
        }
        double[][] distances = distances(robots);
        double way = Angles.bearing(x, y, destinationX, destinationY);
        double[] parts = new double[robots.size()];
        double sum = 0;
        int enemies = 0;
        for (int i = 0; i < robots.size(); i++) {
            parts[i] = part(i, way, destinationX, destinationY, energy, robots, distances);
            sum += parts[i];
            enemies += robots.get(i).enemy() ? 1 : 0;
        }
        double[][] fears = new double[enemies][];
        double mates = enemies < robots.size() ? 0 : Double.NaN;
        for (int i = 0, k = 0; i < robots.size(); i++) {
            double share = sum > 0 ? parts[i] / sum : 0;
            Robot r = robots.get(i);
            if (r.enemy()) {
                fears[k++] = new double[] {r.x(), r.y(), share};
            } else {
                mates += share;
            }
        }
        return new Trace.MoveView(x, y, shown, destinationX, destinationY, fears, mates,
                crowding(destinationX, destinationY),
                Double.isNaN(pullX) ? Double.NaN : pulled(x, y, destinationX, destinationY), pullX, pullY);
    }

    /**
     * The candidate with the least score among the {@code waveCandidates} with the least risk (last turn's
     * destination always among them): risk over their median plus {@code waveWeight} times the waves' damage on the
     * way over its median; last turn's destination at {@code keep} times its score. The candidates scored so go to
     * {@code waved} unless it is null.
     */
    private double[] withWaves(double[][] candidates, int n, WaveSource waves, List<double[]> waved) {
        double[][] sorted = Arrays.copyOf(candidates, n);
        // A plain sort by risk, the kept destination first: no lambda, whose first call can skip a turn.
        for (int i = 1; i < n; i++) {
            double[] c = sorted[i];
            int j = i - 1;
            while (j >= 0 && sorted[j][3] == 0 && (c[3] > 0 || sorted[j][2] > c[2])) {
                sorted[j + 1] = sorted[j];
                j--;
            }
            sorted[j + 1] = c;
        }
        int k = Math.min(n, parameters.waveCandidates());
        double[] risks = new double[k];
        double[] damage = new double[k];
        for (int i = 0; i < k; i++) {
            risks[i] = sorted[i][2];
            damage[i] = waves.damage(sorted[i][0], sorted[i][1]);
            if (waved != null) {
                waved.add(sorted[i]);
            }
        }
        double riskMedian = median(risks);
        double waveMedian = Math.max(parameters.minWaveMedian(), median(damage));
        double[] best = sorted[0];
        double bestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < k; i++) {
            double score = (risks[i] / riskMedian + parameters.waveWeight() * damage[i] / waveMedian)
                    * (sorted[i][3] > 0 ? parameters.keep() : 1);
            if (score < bestScore) {
                bestScore = score;
                best = sorted[i];
            }
        }
        return best;
    }

    private static double median(double[] values) {
        double[] v = values.clone();
        Arrays.sort(v);
        int m = v.length / 2;
        return v.length % 2 == 1 ? v[m] : (v[m - 1] + v[m]) / 2;
    }

    /** The destination chosen in the last turn, {x, y}, or NaN before the first. */
    public double[] destination() {
        return new double[] {destinationX, destinationY};
    }

    /** The risk of driving from ({@code x}, {@code y}) to ({@code px}, {@code py}). */
    double risk(double x, double y, double px, double py, double energy, List<Robot> robots, double[][] distances) {
        double way = Angles.bearing(x, y, px, py);
        double sum = 0;
        for (int i = 0; i < robots.size(); i++) {
            sum += part(i, way, px, py, energy, robots, distances);
        }
        return sum * (crowding(px, py) * pulled(x, y, px, py));
    }

    /**
     * Robot {@code i}'s part of the risk of driving along {@code way} to ({@code px}, {@code py}), before the
     * crowding and the pull: an enemy's threat there, or a teammate's repulsion.
     */
    private double part(int i, double way, double px, double py, double energy, List<Robot> robots,
                        double[][] distances) {
        Robot e = robots.get(i);
        double d2 = (e.x() - px) * (e.x() - px) + (e.y() - py) * (e.y() - py);
        if (!e.enemy()) {
            return parameters.teammateWeight() / Math.max(d2, 1);
        }
        int closer = 0;
        for (int j = 0; j < robots.size(); j++) {
            if (j != i && (parameters.closerCountsEnemies() || !robots.get(j).enemy())
                    && distances[i][j] * distances[i][j] < d2) {
                closer++;
            }
        }
        double threat = Math.max(parameters.minThreat(),
                Math.min(parameters.maxThreat(), e.energy() / Math.max(energy, 0.1)));
        double line = Math.abs(Math.cos(way - Angles.bearing(px, py, e.x(), e.y())));
        return threat * (1 + parameters.perpendicularWeight() * line)
                / (Math.pow(Math.max(d2, 1), parameters.distanceExponent() / 2)
                * Math.pow(1 + closer, parameters.closerExponent()));
    }

    /** What our recent positions near ({@code px}, {@code py}) multiply its risk by: 1 and more. */
    private double crowding(double px, double py) {
        double crowd = 1;
        for (double[] p : history) {
            double d2 = (p[0] - px) * (p[0] - px) + (p[1] - py) * (p[1] - py);
            crowd += parameters.historyWeight() / Math.max(d2, 1);
        }
        return crowd;
    }

    /** What the pull multiplies the risk of driving from ({@code x}, {@code y}) to ({@code px}, {@code py}) by. */
    private double pulled(double x, double y, double px, double py) {
        if (Double.isNaN(pullX)) {
            return 1;
        }
        double from = Math.max(pullFloor, Math.hypot(pullX - x, pullY - y));
        double to = Math.max(pullFloor, Math.hypot(pullX - px, pullY - py));
        return Math.exp(pullStrength * (to - from) / 100);
    }

    /**
     * The robot this turn's destinations are drawn to: with one enemy left and teammates alive, that enemy, down to
     * the finish distance, if we have the energy for it; otherwise, if we are ganged, our nearest teammate; otherwise
     * the nearest ganged teammate, down to the help distance. None outside a team, and no gang defence without
     * {@link #gangDefence} or with a pull of 0.
     */
    private void pull(double x, double y, double energy, List<Robot> robots) {
        pullX = Double.NaN;
        pullY = Double.NaN;
        pullRole = NO_PULL;
        if (parameters.finishDistance() > 0 && energy >= parameters.finishEnergy()) {
            Robot last = null;
            int enemies = 0;
            boolean mates = false;
            for (Robot r : robots) {
                if (r.enemy()) {
                    enemies++;
                    last = r;
                } else {
                    mates = true;
                }
            }
            if (enemies == 1 && mates) {
                pullX = last.x();
                pullY = last.y();
                pullFloor = parameters.finishDistance();
                pullStrength = parameters.finishPull();
                pullRole = FINISHER;
                return;
            }
        }
        if (parameters.teamPull() == 0 || !gangDefence) {
            return;
        }
        pullStrength = parameters.teamPull();
        Robot nearestMate = null;
        Robot nearestGanged = null;
        for (Robot r : robots) {
            if (r.enemy()) {
                continue;
            }
            if (nearestMate == null || dist(r, x, y) < dist(nearestMate, x, y)) {
                nearestMate = r;
            }
            if (enemiesNear(r.x(), r.y(), robots) >= GANG_SIZE
                    && (nearestGanged == null || dist(r, x, y) < dist(nearestGanged, x, y))) {
                nearestGanged = r;
            }
        }
        if (nearestMate != null && enemiesNear(x, y, robots) >= GANG_SIZE) {
            pullX = nearestMate.x();
            pullY = nearestMate.y();
            pullFloor = 0;
            pullRole = GANGED;
        } else if (nearestGanged != null) {
            pullX = nearestGanged.x();
            pullY = nearestGanged.y();
            pullFloor = parameters.helpDistance();
            pullRole = HELPER;
        }
    }

    static int enemiesNear(double x, double y, List<Robot> robots) {
        int n = 0;
        for (Robot r : robots) {
            if (r.enemy() && dist(r, x, y) <= GANG_RADIUS) {
                n++;
            }
        }
        return n;
    }

    private static double dist(Robot r, double x, double y) {
        return Math.hypot(r.x() - x, r.y() - y);
    }

    private static double[][] distances(List<Robot> robots) {
        double[][] d = new double[robots.size()][robots.size()];
        for (int i = 0; i < robots.size(); i++) {
            for (int j = i + 1; j < robots.size(); j++) {
                d[i][j] = d[j][i] = Math.hypot(robots.get(i).x() - robots.get(j).x(),
                        robots.get(i).y() - robots.get(j).y());
            }
        }
        return d;
    }

}
