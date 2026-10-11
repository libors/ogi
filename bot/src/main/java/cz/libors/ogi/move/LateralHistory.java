package cz.libors.ogi.move;

import java.util.Arrays;

/**
 * Our lateral velocity as one enemy saw it, one value per turn for the whole battle: what guns that average it or
 * match its pattern aim with. Lateral is our velocity across the line from the enemy, positive when we move
 * counter-clockwise around it.
 *
 * <p>The pattern is a string of our lateral velocity rounded to whole pixels per turn, one symbol per turn, over all
 * rounds. A match is the latest earlier occurrence of the newest symbols, the longest one found from
 * {@code maxLength} symbols down; its replay is the sum of the symbols that followed it, the angle a gun that replays
 * our movement leads by, times the distance.
 */
public final class LateralHistory {

    private static final int MIN_MATCH = 3;

    private double[] values = new double[1024];
    private byte[] symbols = new byte[1024];
    private int size;
    private int roundStart;
    private long lastTime = Long.MIN_VALUE;

    /** Our lateral velocity at the end of turn {@code time}; a time earlier than the last starts a new round. */
    public void add(long time, double lateral) {
        if (time <= lastTime) {
            roundStart = size;
        }
        lastTime = time;
        if (size == values.length) {
            values = Arrays.copyOf(values, 2 * size);
            symbols = Arrays.copyOf(symbols, 2 * size);
        }
        values[size] = lateral;
        symbols[size] = (byte) Math.max(-8, Math.min(8, Math.round(lateral)));
        size++;
    }

    /** The index of turn {@code time}'s value in this round, or -1 if there is none yet. */
    private int indexAt(long time) {
        if (size == 0) {
            return -1;
        }
        long back = lastTime - time;
        int index = (int) (size - 1 - Math.max(0, back));
        return index >= roundStart ? index : -1;
    }

    /** The mean of our lateral velocity over the last {@code window} turns of this round up to {@code time}. */
    public double average(long time, int window) {
        int end = indexAt(time);
        if (end < 0) {
            return 0;
        }
        int from = Math.max(roundStart, end - window + 1);
        double sum = 0;
        for (int i = from; i <= end; i++) {
            sum += values[i];
        }
        return sum / (end - from + 1);
    }

    /**
     * The replay of the longest earlier match, at most {@code maxLength} symbols, of the symbols up to {@code time}:
     * the sum of the {@code turns} symbols after it; NaN without a match of at least three symbols. One pass back
     * through the history, which compares a few symbols at most places; the latest of equally long matches wins.
     */
    public double replay(long time, int maxLength, int turns) {
        int end = indexAt(time);
        if (end < 0) {
            return Double.NaN;
        }
        int best = -1;
        int bestLength = MIN_MATCH - 1;
        // A match ends at j, and its replay of `turns` symbols must lie within what we know.
        for (int j = end - turns; j >= 0 && bestLength < maxLength; j--) {
            int n = 0;
            while (n < maxLength && j - n >= 0 && symbols[j - n] == symbols[end - n]) {
                n++;
            }
            if (n > bestLength) {
                bestLength = n;
                best = j;
            }
        }
        if (best < 0) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = best + 1; i <= best + turns; i++) {
            sum += symbols[i];
        }
        return sum;
    }
}
