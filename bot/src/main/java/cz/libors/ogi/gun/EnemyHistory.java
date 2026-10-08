package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Angles;

/**
 * The latest scans of one enemy, newest last, for features that look back in time. Headings are mathematical.
 */
public final class EnemyHistory {

    private static final int CAPACITY = 32;

    private final long[] time = new long[CAPACITY];
    private final double[] x = new double[CAPACITY];
    private final double[] y = new double[CAPACITY];
    private final double[] heading = new double[CAPACITY];
    private final double[] velocity = new double[CAPACITY];
    private int size;
    private int newest = -1;
    /** Last turn the velocity changed sign, and last turn the speed dropped. */
    private long lastReversal;
    private long lastDeceleration;

    /** Adds a scan; a scan not newer than the last one is ignored. */
    public void add(long t, double px, double py, double h, double v) {
        if (size > 0 && t <= time[newest]) {
            return;
        }
        if (size > 0) {
            double before = velocity[newest];
            if (Math.signum(v) != 0 && Math.signum(before) != 0 && Math.signum(v) != Math.signum(before)) {
                lastReversal = t;
            }
            if (Math.abs(v) < Math.abs(before)) {
                lastDeceleration = t;
            }
        } else {
            lastReversal = t;
            lastDeceleration = t;
        }
        newest = (newest + 1) % CAPACITY;
        time[newest] = t;
        x[newest] = px;
        y[newest] = py;
        heading[newest] = h;
        velocity[newest] = v;
        size = Math.min(size + 1, CAPACITY);
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public long time() {
        return time[newest];
    }

    public double x() {
        return x[newest];
    }

    public double y() {
        return y[newest];
    }

    public double heading() {
        return heading[newest];
    }

    public double velocity() {
        return velocity[newest];
    }

    /** Speed change since the previous scan, per turn: positive when accelerating. */
    public double acceleration() {
        if (size < 2) {
            return 0;
        }
        int previous = (newest - 1 + CAPACITY) % CAPACITY;
        return (Math.abs(velocity[newest]) - Math.abs(velocity[previous])) / (time[newest] - time[previous]);
    }

    /** Heading change since the previous scan, per turn, counter-clockwise positive. */
    public double turnRate() {
        if (size < 2) {
            return 0;
        }
        int previous = (newest - 1 + CAPACITY) % CAPACITY;
        return Angles.normalizeRelative(heading[newest] - heading[previous]) / (time[newest] - time[previous]);
    }

    public long turnsSinceReversal() {
        return time[newest] - lastReversal;
    }

    public long turnsSinceDeceleration() {
        return time[newest] - lastDeceleration;
    }

    /** Straight-line distance moved over the last {@code turns} turns, or over the stored scans if fewer. */
    public double displacement(int turns) {
        int back = newest;
        for (int i = 1; i < size; i++) {
            int candidate = (newest - i + CAPACITY) % CAPACITY;
            if (time[newest] - time[candidate] > turns) {
                break;
            }
            back = candidate;
        }
        return Math.hypot(x[newest] - x[back], y[newest] - y[back]);
    }
}
