package cz.libors.ogi.move;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.EngineRobot;

import java.util.ArrayDeque;
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
 * {@code threat · perpendicularity / (distance² · (1 + robots closer to the enemy than D))} (the exponents are
 * {@link #DISTANCE_EXPONENT} and {@link #CLOSER_EXPONENT}):
 * <ul>
 *   <li><b>threat:</b> the enemy's energy over ours, within limits: a strong enemy fires longer and harder;</li>
 *   <li><b>distance</b> from D to the enemy: the angle we fill in its view, and so its chance to hit, falls with it,
 *       and so does its chance to pick us;</li>
 *   <li><b>robots closer to the enemy than D</b> (enemies and teammates): with each one it is less likely to aim at
 *       us there;</li>
 *   <li><b>perpendicularity:</b> 1 plus a share of |cos| of the angle between our way to D and the line from D to the
 *       enemy: driving straight at or away from a shooter is what its head-on and linear guns hit.</li>
 * </ul>
 * Teammates repel too ({@link #TEAMMATE_WEIGHT}). The sum grows with our recent positions near D, so that we do not
 * keep to one spot that guns learn.
 *
 * <p>The candidates lie on a circle around us at random distances, but never further than a share of the distance
 * to the closest enemy, so that we do not drive past it; points too close to a wall are left out. The destination of
 * the last turn competes with its score lowered, so that a near tie does not turn us back and forth.
 *
 * <p>The enemy waves in the air count too: for the candidates with the least risk, the damage we expect on the way
 * there ({@link WaveSurfer#meleeDanger}), each wave weighted by the chance that its shooter aimed at us. A
 * candidate's score is its risk over the median risk of those candidates plus {@link #WAVE_WEIGHT} times its
 * waves' damage over their median.
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
    /** The enemy's energy over ours counts within these limits. */
    static final double MIN_THREAT = 0.5;
    static final double MAX_THREAT = 2;
    /** An enemy's risk falls with this power of the distance. */
    static final double DISTANCE_EXPONENT = 2;
    /** An enemy's risk is divided by this power of 1 + the robots closer to it than the destination. */
    static final double CLOSER_EXPONENT = 1;
    /** How much moving along the line to an enemy adds: up to this share more risk. */
    static final double PERPENDICULAR_WEIGHT = 1;
    /**
     * A teammate repels with this weight over its distance squared, as an enemy of our energy that nobody stands
     * closer to repels with 1 to 2: in a team battle robots that keep apart are not hit by bullets aimed at each other
     * and draw the enemies' fire apart. Against 0.3 the weight 1 gained about 4 APS on the team rumble and 3 another
     * 2; 10 gained nothing more (2026-10-16).
     */
    static final double TEAMMATE_WEIGHT = 3;
    /** Last turn's destination competes with its risk times this. */
    static final double KEEP = 0.9;
    /** Nearer to the destination than this, it is reached: new candidates only. */
    static final double ARRIVED = 20;
    /** Our recent positions: one every this many turns, this many of them, and how much each repels. */
    static final int HISTORY_EVERY = 5;
    static final int HISTORY_SIZE = 20;
    static final double HISTORY_WEIGHT = 400;
    /** The candidates with the least risk whose way is predicted against the enemy waves. */
    static final int WAVE_CANDIDATES = 8;
    /** The waves' share of a candidate's score against the position's, each over its median. */
    static final double WAVE_WEIGHT = 3;
    /** The waves' median damage counts as at least this much, so that a lone wave does not take over the score. */
    static final double MIN_WAVE_MEDIAN = 0.1;

    /** A robot to keep away from: position, energy, and whether it is an enemy (else a teammate). */
    public record Robot(double x, double y, double energy, boolean enemy) {
    }

    private final Field field;
    private final Random random;
    private final Deque<double[]> history = new ArrayDeque<>();
    private double destinationX = Double.NaN;
    private double destinationY = Double.NaN;

    public MeleeMovement(Field field, Random random) {
        this.field = field;
        this.random = random;
    }

    /**
     * The commands for the next turn, {distance, right turn in radians} as the engine takes them, towards the safest
     * destination, by the position alone.
     *
     * @param heading our body heading, mathematical
     */
    public double[] command(long now, double x, double y, double heading, double energy, List<Robot> robots) {
        return command(now, x, y, heading, energy, robots, null, null);
    }

    /**
     * The same with the enemy waves in the air counted ({@link WaveSurfer#meleeDanger}) if {@code surfer} is not null.
     *
     * @param me our state at the end of this turn, in the engine's convention, without commands
     */
    public double[] command(long now, double x, double y, double heading, double energy, List<Robot> robots,
                            WaveSurfer surfer, EngineRobot me) {
        if (now % HISTORY_EVERY == 0) {
            history.addFirst(new double[] {x, y});
            if (history.size() > HISTORY_SIZE) {
                history.removeLast();
            }
        }
        double closest = Double.POSITIVE_INFINITY;
        for (Robot r : robots) {
            if (r.enemy()) {
                closest = Math.min(closest, Math.hypot(r.x() - x, r.y() - y));
            }
        }
        double[][] distances = distances(robots);
        // Candidates {x, y, risk, kept}: last turn's destination first, if it still counts.
        double[][] candidates = new double[CANDIDATES + 1][];
        int n = 0;
        if (!Double.isNaN(destinationX) && Math.hypot(destinationX - x, destinationY - y) > ARRIVED
                && field.contains(destinationX, destinationY, WALL_MARGIN)) {
            candidates[n++] = new double[] {destinationX, destinationY,
                    risk(x, y, destinationX, destinationY, energy, robots, distances), 1};
        }
        double reach = Math.min(MAX_REACH, CLOSEST_SHARE * closest);
        for (int i = 0; i < CANDIDATES; i++) {
            double angle = Angles.TWO_PI * (i + random.nextDouble()) / CANDIDATES;
            double d = Math.min(reach, MIN_REACH + (MAX_REACH - MIN_REACH) * random.nextDouble());
            double px = x + d * Math.cos(angle);
            double py = y + d * Math.sin(angle);
            if (field.contains(px, py, WALL_MARGIN)) {
                candidates[n++] = new double[] {px, py, risk(x, y, px, py, energy, robots, distances), 0};
            }
        }
        double bestX = Double.NaN;
        double bestY = Double.NaN;
        if (surfer == null) {
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                double score = candidates[i][2] * (candidates[i][3] > 0 ? KEEP : 1);
                if (score < best) {
                    best = score;
                    bestX = candidates[i][0];
                    bestY = candidates[i][1];
                }
            }
        } else if (n > 0) {
            double[] best = withWaves(candidates, n, surfer, me, now);
            bestX = best[0];
            bestY = best[1];
        }
        if (Double.isNaN(bestX)) {
            // Every candidate was too close to a wall: head for the middle.
            bestX = field.width() / 2;
            bestY = field.height() / 2;
        }
        destinationX = bestX;
        destinationY = bestY;
        return driveTo(x, y, heading, bestX, bestY);
    }

    /**
     * The candidate with the least score among the {@link #WAVE_CANDIDATES} with the least risk (last turn's
     * destination always among them): risk over their median plus {@link #WAVE_WEIGHT} times the waves' damage on the
     * way over its median; last turn's destination at {@link #KEEP} times its score.
     */
    private static double[] withWaves(double[][] candidates, int n, WaveSurfer surfer, EngineRobot me, long now) {
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
        int k = Math.min(n, WAVE_CANDIDATES);
        double[] risks = new double[k];
        double[] waves = new double[k];
        for (int i = 0; i < k; i++) {
            risks[i] = sorted[i][2];
            waves[i] = surfer.meleeDanger(me, now, sorted[i][0], sorted[i][1]);
        }
        double riskMedian = median(risks);
        double waveMedian = Math.max(MIN_WAVE_MEDIAN, median(waves));
        double[] best = sorted[0];
        double bestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < k; i++) {
            double score = (risks[i] / riskMedian + WAVE_WEIGHT * waves[i] / waveMedian)
                    * (sorted[i][3] > 0 ? KEEP : 1);
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
            Robot e = robots.get(i);
            double d2 = (e.x() - px) * (e.x() - px) + (e.y() - py) * (e.y() - py);
            if (!e.enemy()) {
                sum += TEAMMATE_WEIGHT / Math.max(d2, 1);
                continue;
            }
            int closer = 0;
            for (int j = 0; j < robots.size(); j++) {
                if (j != i && distances[i][j] * distances[i][j] < d2) {
                    closer++;
                }
            }
            double threat = Math.max(MIN_THREAT, Math.min(MAX_THREAT, e.energy() / Math.max(energy, 0.1)));
            double line = Math.abs(Math.cos(way - Angles.bearing(px, py, e.x(), e.y())));
            sum += threat * (1 + PERPENDICULAR_WEIGHT * line)
                    / (Math.pow(Math.max(d2, 1), DISTANCE_EXPONENT / 2) * Math.pow(1 + closer, CLOSER_EXPONENT));
        }
        double crowd = 1;
        for (double[] p : history) {
            double d2 = (p[0] - px) * (p[0] - px) + (p[1] - py) * (p[1] - py);
            crowd += HISTORY_WEIGHT / Math.max(d2, 1);
        }
        return sum * crowd;
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

    /** Drives towards the point, backwards if that turns us less; {distance, right turn} in the engine's convention. */
    public static double[] driveTo(double x, double y, double heading, double px, double py) {
        double distance = Math.hypot(px - x, py - y);
        double turn = Angles.normalizeRelative(Angles.bearing(x, y, px, py) - heading);
        if (Math.abs(turn) > Math.PI / 2) {
            turn = Angles.normalizeRelative(turn + Math.PI);
            distance = -distance;
        }
        return new double[] {distance, -turn};
    }
}
