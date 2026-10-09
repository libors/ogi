package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Angles;

/**
 * The latest states of one robot, newest last, for features that look back in time: an enemy's scans for the gun, our
 * own states for the surfer. Headings are mathematical.
 */
public final class RobotHistory {

    private static final int CAPACITY = 32;

    private final long[] time = new long[CAPACITY];
    private final double[] x = new double[CAPACITY];
    private final double[] y = new double[CAPACITY];
    private final double[] heading = new double[CAPACITY];
    private final double[] velocity = new double[CAPACITY];
    /** As of each state: the last turn the velocity changed sign, and the last turn the speed dropped. */
    private final long[] lastReversal = new long[CAPACITY];
    private final long[] lastDeceleration = new long[CAPACITY];
    private int size;
    private int newest = -1;

    /** Adds a state; a state not newer than the last one is ignored. */
    public void add(long t, double px, double py, double h, double v) {
        if (size > 0 && t <= time[newest]) {
            return;
        }
        long reversal = t;
        long deceleration = t;
        if (size > 0) {
            double before = velocity[newest];
            reversal = lastReversal[newest];
            deceleration = lastDeceleration[newest];
            if (Math.signum(v) != 0 && Math.signum(before) != 0 && Math.signum(v) != Math.signum(before)) {
                reversal = t;
            }
            if (Math.abs(v) < Math.abs(before)) {
                deceleration = t;
            }
        }
        newest = (newest + 1) % CAPACITY;
        time[newest] = t;
        x[newest] = px;
        y[newest] = py;
        heading[newest] = h;
        velocity[newest] = v;
        lastReversal[newest] = reversal;
        lastDeceleration[newest] = deceleration;
        size = Math.min(size + 1, CAPACITY);
    }

    /**
     * The history as it was at the end of turn {@code t}: a copy without the states after it, empty if none is stored
     * from before.
     */
    public RobotHistory until(long t) {
        RobotHistory copy = new RobotHistory();
        for (int back = size - 1; back >= 0; back--) {
            int i = (newest - back + CAPACITY) % CAPACITY;
            if (time[i] > t) {
                break;
            }
            copy.newest = (copy.newest + 1) % CAPACITY;
            copy.time[copy.newest] = time[i];
            copy.x[copy.newest] = x[i];
            copy.y[copy.newest] = y[i];
            copy.heading[copy.newest] = heading[i];
            copy.velocity[copy.newest] = velocity[i];
            copy.lastReversal[copy.newest] = lastReversal[i];
            copy.lastDeceleration[copy.newest] = lastDeceleration[i];
            copy.size++;
        }
        return copy;
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

    /** Speed change since the previous state, per turn: positive when accelerating. */
    public double acceleration() {
        if (size < 2) {
            return 0;
        }
        int previous = (newest - 1 + CAPACITY) % CAPACITY;
        return (Math.abs(velocity[newest]) - Math.abs(velocity[previous])) / (time[newest] - time[previous]);
    }

    /** Heading change since the previous state, per turn, counter-clockwise positive. */
    public double turnRate() {
        if (size < 2) {
            return 0;
        }
        int previous = (newest - 1 + CAPACITY) % CAPACITY;
        return Angles.normalizeRelative(heading[newest] - heading[previous]) / (time[newest] - time[previous]);
    }

    public long turnsSinceReversal() {
        return time[newest] - lastReversal[newest];
    }

    public long turnsSinceDeceleration() {
        return time[newest] - lastDeceleration[newest];
    }

    /** Straight-line distance moved over the last {@code turns} turns, or over the stored states if fewer. */
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
