package cz.libors.ogi.move;

/**
 * Where one enemy's bullets went, as a smoothed histogram over guess factors in the frame of its waves: the
 * probability that it aims at each guess factor. It starts with all weight at guess factor 0, a gun aiming straight
 * at us, which is what most simple guns do.
 */
final class DangerStats {

    static final int BINS = 47;
    private static final int MIDDLE = BINS / 2;

    private final double[] bins = new double[BINS];
    private double total;

    DangerStats() {
        add(0, 1);
    }

    /** A bullet of this enemy passed us at this guess factor (it hit us or one of our bullets). */
    void hit(double guessFactor) {
        add(guessFactor, 1);
    }

    private void add(double guessFactor, double weight) {
        int at = bin(guessFactor);
        for (int i = 0; i < BINS; i++) {
            double w = weight / ((i - at) * (i - at) + 1);
            bins[i] += w;
            total += w;
        }
    }

    /** The share of the enemy's aim that falls between the two guess factors. */
    double danger(double low, double high) {
        int from = bin(Math.min(low, high));
        int to = bin(Math.max(low, high));
        double sum = 0;
        for (int i = from; i <= to; i++) {
            sum += bins[i];
        }
        return sum / total;
    }

    static int bin(double guessFactor) {
        double clamped = Math.max(-1, Math.min(1, guessFactor));
        return (int) Math.round(clamped * MIDDLE) + MIDDLE;
    }
}
