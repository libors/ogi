package cz.libors.ogi.shield;

import cz.libors.ogi.geom.Angles;

import java.util.Arrays;

/**
 * Where one enemy aims at a robot that (almost) stands still, learned from its bullets.
 *
 * <p>Against a target that stands, head-on, linear and circular targeting aim at the same point, and guns that learn
 * converge to it. What is left to know is where the enemy aims from, at which of our positions, and with which
 * offset. A model is a base heading and an offset. The base headings, for a bullet fired in turn f:
 * <ul>
 *   <li>{@link #HEAD_ON}: from its position at the end of f − 1 at ours then. A gun turned in one turn fires along
 *   that heading in the next, from where the robot has moved to (engine-model G-01).</li>
 *   <li>{@link #FROM_ORIGIN}: from where the bullet leaves (its position at the end of f): a gun that predicts its own
 *   next position exactly.</li>
 *   <li>{@link #FROM_NEXT}: from its position at the end of f − 1 advanced by its velocity along its heading: a gun
 *   that predicts its next position roughly.</li>
 *   <li>{@link #BODY_TURN}: head-on plus its body's turn in f: a gun that turns with the body.</li>
 * </ul>
 * Each also at our position at the end of f − 1 advanced by our velocity then ({@link #PROJECTED}): a gun that leads
 * a target by one turn, which matters only in the turns we move. And {@link #BODY}: its body's heading at the end of
 * f, for a gun that never turns on its own, so that the offset is where it points on the body.
 *
 * <p>The offsets: constant; signed by our lateral direction as the enemy saw it, either the last one we moved in or,
 * with zero lateral velocity counted as clockwise, the current one; and the same two in units of the maximum escape
 * angle (a guess-factor gun that aims one bin off the centre). Robocode's angles turn clockwise, so a gun that counts
 * a target standing still as moving "forwards" sees a clockwise orbit.
 *
 * <p>Each observed bullet (one that hit us or one of ours) gives every model an error, the observed heading minus the
 * base heading, signed and scaled as the model's offset is. A model's offset is the error that most of its recent
 * errors match within {@link #MATCH}; the model whose offset matches the most bullets predicts, the smaller offset on
 * a tie. Without bullets, head-on with no offset.
 *
 * <p>Some guns switch to an aim a bin off for runs of shots. Following such a run after two of its shots catches
 * more of them against some bots and provokes more of them against others (a guess-factor gun hit by the bullet
 * we did not shoot down), so the aim does not follow runs.
 *
 * <p>It also keeps how far its predictions missed the recent bullets, before each was learned: a gun that aims with a
 * little noise needs a wider margin than one that aims to the last bit ({@link #neededMargin}).
 */
public final class ShieldAim {

    public static final int HEAD_ON = 0;
    public static final int FROM_ORIGIN = 1;
    public static final int FROM_NEXT = 2;
    public static final int BODY_TURN = 3;
    /** Added to a base: the same aimed at our position advanced by one turn of our velocity. */
    public static final int PROJECTED = 4;
    public static final int BODY = 8;
    public static final int BASES = 9;

    public static final int CONSTANT = 0;
    public static final int SIGNED_LAST = 1;
    public static final int SIGNED_CURRENT = 2;
    public static final int FACTOR_LAST = 3;
    public static final int FACTOR_CURRENT = 4;
    public static final int OFFSETS = 5;
    public static final int COUNT = BASES * OFFSETS;

    /** Errors within this many radians count as the same offset: the engine's rounding, not a different aim. */
    static final double MATCH = 2e-5;
    /** How many of the latest bullets each model remembers. */
    private static final int MEMORY = 60;
    /** How many of the latest prediction errors the needed margin is taken from, and which share of them it covers. */
    private static final int MISSES = 30;
    private static final double COVERED = 0.9;
    /** The needed margin covers the errors by this factor. */
    private static final double MARGIN_FACTOR = 1.25;

    /** A bullet's base headings and what its offsets are signed and scaled with. */
    public record Bullet(double[] bases, double lastDirection, double currentDirection, double escapeAngle) {
    }

    private final double[][] errors = new double[COUNT][MEMORY];
    private final double[] tolerances = new double[MEMORY];
    /** How far the prediction missed each of the latest bullets, in radians, before it was learned. */
    private final double[] misses = new double[MISSES];
    private int observed;
    private int best;
    private double offset;
    private int matches;

    /**
     * A bullet fired in turn f.
     *
     * @param before         the enemy at the end of turn f − 1: {x, y, heading, velocity}, heading mathematical
     * @param origin         the enemy at the end of turn f, where the bullet leaves: {x, y, heading, velocity}
     * @param us             our state at the end of turn f − 1: {x, y, heading, velocity}, heading mathematical
     * @param lastDirection  the last lateral direction we moved in as the enemy saw it, 1 or −1 (mathematical:
     *                       counter-clockwise positive)
     * @param escapeAngle    the bullet's maximum escape angle
     */
    public static Bullet bullet(double[] before, double[] origin, double[] us, double lastDirection,
                                double escapeAngle) {
        double[] bases = new double[BASES];
        double nx = before[0] + before[3] * Math.cos(before[2]);
        double ny = before[1] + before[3] * Math.sin(before[2]);
        double turn = Angles.normalizeRelative(origin[2] - before[2]);
        for (int projected = 0; projected <= PROJECTED; projected += PROJECTED) {
            double ux = projected == 0 ? us[0] : us[0] + us[3] * Math.cos(us[2]);
            double uy = projected == 0 ? us[1] : us[1] + us[3] * Math.sin(us[2]);
            bases[projected + HEAD_ON] = Angles.bearing(before[0], before[1], ux, uy);
            bases[projected + FROM_ORIGIN] = Angles.bearing(origin[0], origin[1], ux, uy);
            bases[projected + FROM_NEXT] = Angles.bearing(nx, ny, ux, uy);
            bases[projected + BODY_TURN] = bases[projected + HEAD_ON] + turn;
        }
        bases[BODY] = origin[2];
        double lateral = us[3] * Math.sin(us[2] - Angles.bearing(before[0], before[1], us[0], us[1]));
        // Clockwise, Robocode's positive sense, when we stand.
        double current = lateral > 0 ? 1 : -1;
        return new Bullet(bases, lastDirection, current, escapeAngle);
    }

    /** A bullet whose models were {@code bullet} flew along {@code actual} (mathematical). */
    public void observe(Bullet bullet, double actual) {
        misses[observed % MISSES] = Math.abs(Angles.normalizeRelative(actual - predict(bullet)));
        int slot = observed % MEMORY;
        for (int m = 0; m < COUNT; m++) {
            errors[m][slot] = Angles.normalizeRelative(actual - bullet.bases()[m / OFFSETS]) / scale(m, bullet);
        }
        tolerances[slot] = bullet.escapeAngle();
        observed++;
        int n = Math.min(observed, MEMORY);
        best = 0;
        offset = 0;
        matches = 0;
        double bestSize = Double.POSITIVE_INFINITY;
        for (int m = 0; m < COUNT; m++) {
            boolean factor = isFactor(m);
            for (int i = 0; i < n; i++) {
                double tolerance = factor ? MATCH / tolerances[i] : MATCH;
                int count = 0;
                double sum = 0;
                for (int j = 0; j < n; j++) {
                    if (Math.abs(errors[m][j] - errors[m][i]) <= tolerance) {
                        count++;
                        sum += errors[m][j];
                    }
                }
                // On a tie the smaller offset, in radians: a gun that aims nearer the base is the likelier.
                double size = Math.abs(sum / count) * (factor ? tolerances[i] : 1);
                if (count > matches || count == matches && size < bestSize - MATCH) {
                    matches = count;
                    best = m;
                    offset = sum / count;
                    bestSize = size;
                }
            }
        }
    }

    /** The heading the enemy most likely fires {@code bullet} along. */
    public double predict(Bullet bullet) {
        return bullet.bases()[best / OFFSETS] + offset * scale(best, bullet);
    }

    /** What a model's error is divided by: its sign and, for guess-factor offsets, the escape angle. */
    private static double scale(int model, Bullet bullet) {
        return switch (model % OFFSETS) {
            case SIGNED_LAST -> bullet.lastDirection();
            case SIGNED_CURRENT -> bullet.currentDirection();
            case FACTOR_LAST -> bullet.lastDirection() * bullet.escapeAngle();
            case FACTOR_CURRENT -> bullet.currentDirection() * bullet.escapeAngle();
            default -> 1;
        };
    }

    private static boolean isFactor(int model) {
        return model % OFFSETS >= FACTOR_LAST;
    }

    /**
     * The margin a shield shot needs against this enemy: the prediction error {@link #COVERED} of the recent bullets
     * stayed within, times {@link #MARGIN_FACTOR}, at least {@code least}; {@code unknown} before enough bullets.
     */
    public double neededMargin(double least, double unknown) {
        int n = Math.min(observed, MISSES);
        if (n < MISSES / 3) {
            return unknown;
        }
        double[] sorted = Arrays.copyOf(misses, n);
        Arrays.sort(sorted);
        return Math.max(least, MARGIN_FACTOR * sorted[Math.min(n - 1, (int) Math.ceil(COVERED * n) - 1)]);
    }

    /** The predicting model: {@code base * OFFSETS + offset kind}. */
    public int model() {
        return best;
    }

    /** The predicting model's offset, in its own units. */
    public double offset() {
        return offset;
    }

    /** The share of the remembered bullets the predicting model matched, 0 without bullets. */
    public double matchedShare() {
        return observed == 0 ? 0 : (double) matches / Math.min(observed, MEMORY);
    }

    public int observed() {
        return observed;
    }
}
