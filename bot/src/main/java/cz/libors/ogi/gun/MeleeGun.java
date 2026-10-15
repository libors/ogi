package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.RobotMotion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The melee gun: where each enemy goes, learned in its own frame, and one aim at all of them.
 *
 * <p>In a melee a robot moves relative to its own closest enemy, not to us, so guess factors measured from our gun
 * miss the shape of its movement. Instead, every scan of an enemy starts a record: its situation then (speed,
 * acceleration, the wall ahead, how it turns, where its closest robot is, how many enemies are alive), and its later
 * scans add where it had got to, as displacements in its frame at the start (forward along its way of travel, and to
 * its left). The record does not depend on who watches.
 *
 * <p>Aiming: for every enemy scanned in the last turns, the records of the most similar situations each say where it
 * will be when our bullet gets there: its last scan plus the record's displacement after that many turns, turned into
 * its frame now; positions outside the field are left out. Each such position is a bearing from the bullet's origin
 * and a robot's width around it. The gun aims at the bearing the most weight covers, over all enemies at once: a
 * neighbour weighs more the nearer its situation is, and an enemy the nearer it is to us. A bullet aimed so hits
 * whoever is there.
 *
 * <p>The records last for the battle in a {@link Memory}, which the bot keeps in a static (engine-model G-22); the
 * records still collecting displacements are per round. The numbers come from {@link MeleeGunParameters}.
 */
public final class MeleeGun {

    /** Features, each in about [0, 1]. */
    public static final int FEATURES = 12;
    /** A gap between scans longer than this tells nothing about acceleration and turning. */
    static final int MAX_SCAN_GAP = 8;
    /** The turns since a reversal or a deceleration count up to this many. */
    static final double SINCE_RANGE = 60;
    /** The distance travelled is measured over the scans of about this many turns. */
    static final int TRAVEL_TURNS = 16;

    /** One scan of an enemy and where it went from there; its features stay in its {@link Store}. */
    static final class Record {
        final long time;
        final double x;
        final double y;
        /** The way of travel at the scan, mathematical: the heading, turned round while it drove backwards. */
        final double frame;
        int samples;
        int[] turns = new int[8];
        float[] forward = new float[8];
        float[] left = new float[8];
        /** Where the record stands in its store, or -1 once the store dropped it. */
        Store store;
        int slot = -1;

        Record(long time, double x, double y, double frame) {
            this.time = time;
            this.x = x;
            this.y = y;
            this.frame = frame;
        }

        void add(long t, double px, double py) {
            if (samples == turns.length) {
                turns = java.util.Arrays.copyOf(turns, 2 * samples);
                forward = java.util.Arrays.copyOf(forward, 2 * samples);
                left = java.util.Arrays.copyOf(left, 2 * samples);
            }
            double dx = px - x;
            double dy = py - y;
            double c = Math.cos(frame);
            double s = Math.sin(frame);
            turns[samples] = (int) (t - time);
            forward[samples] = (float) (dx * c + dy * s);
            left[samples] = (float) (-dx * s + dy * c);
            if (slot >= 0) {
                store.last[slot] = turns[samples];
            }
            samples++;
        }

        /**
         * The displacement {forward, left} after {@code k} turns, interpolated between the samples around it; null
         * past the last sample. Before the first sample, from the start (no displacement at 0 turns).
         */
        double[] at(double k) {
            if (samples == 0 || k > turns[samples - 1]) {
                return null;
            }
            double t0 = 0;
            double f0 = 0;
            double l0 = 0;
            for (int i = 0; i < samples; i++) {
                if (turns[i] >= k) {
                    double share = turns[i] == t0 ? 1 : (k - t0) / (turns[i] - t0);
                    return new double[] {f0 + share * (forward[i] - f0), l0 + share * (left[i] - l0)};
                }
                t0 = turns[i];
                f0 = forward[i];
                l0 = left[i];
            }
            return null;
        }
    }

    /** An enemy's last scan, and its situation then. */
    private static final class Enemy {
        long time = Long.MIN_VALUE;
        double x;
        double y;
        double frame;
        double velocity;
        double heading;
        double[] features;
        /** The sign of its last non-zero velocity, and the turns of its last reversal and deceleration this round. */
        double sign;
        long reversal;
        long deceleration;
        /** Its scans of the last turns: {time, x, y}. */
        final Deque<double[]> trail = new ArrayDeque<>();
        final Deque<Record> open = new ArrayDeque<>();
    }

    /**
     * One enemy's records, the newest {@code capacity}, oldest first in a ring. What the neighbour search reads of each
     * record lies side by side in arrays (its features, its scan's turn, its last sample's turn): the search runs over
     * all of them for every aim, and objects scattered over the heap made it wait for memory.
     */
    static final class Store {
        final int capacity;
        final double[] features;
        final long[] times;
        /** The last sample's turn after the scan, -1 while the record has none. */
        final int[] last;
        final Record[] records;
        int start;
        int size;

        Store(int capacity) {
            this.capacity = capacity;
            features = new double[capacity * FEATURES];
            times = new long[capacity];
            last = new int[capacity];
            records = new Record[capacity];
        }

        /** Adds a record without samples, dropping the oldest if full. */
        void add(Record r, double[] f) {
            if (size == capacity) {
                records[start].slot = -1;
                records[start] = null;
                start = (start + 1) % capacity;
                size--;
            }
            int i = (start + size) % capacity;
            System.arraycopy(f, 0, features, i * FEATURES, FEATURES);
            times[i] = r.time;
            last[i] = r.samples == 0 ? -1 : r.turns[r.samples - 1];
            records[i] = r;
            r.store = this;
            r.slot = i;
            size++;
        }
    }

    /** The records of a battle, per enemy. */
    public static final class Memory {
        final Map<String, Store> records = new HashMap<>();
    }

    private final Field field;
    private final Memory memory;
    private final MeleeGunParameters p;
    /** The features with a weight, in order: the others add nothing to a distance. */
    private final int[] weighted;
    private final Map<String, Enemy> enemies = new HashMap<>();
    /** The enemy whose positions weighed most in the last aim, or null. */
    private String aimedAt;

    public MeleeGun(Field field, Memory memory, MeleeGunParameters parameters) {
        this.field = field;
        this.memory = memory;
        this.p = parameters;
        double[] w = parameters.weights();
        weighted = java.util.stream.IntStream.range(0, FEATURES).filter(i -> w[i] != 0).toArray();
    }

    /**
     * A scan of an enemy at the end of turn {@code time}, heading mathematical.
     *
     * @param closestX the robot closest to it then (any other robot, us included)
     * @param enemies  the enemies alive
     */
    public void scanned(String name, long time, double x, double y, double heading, double velocity,
                        double closestX, double closestY, int enemies) {
        Enemy e = this.enemies.computeIfAbsent(name, n -> new Enemy());
        if (time <= e.time) {
            return;
        }
        for (Iterator<Record> it = e.open.iterator(); it.hasNext(); ) {
            Record r = it.next();
            if (time - r.time > p.horizon()) {
                it.remove();
            } else {
                r.add(time, x, y);
            }
        }
        double frame = velocity < 0 ? heading + Math.PI : heading;
        double[] f = new double[FEATURES];
        f[0] = Math.abs(velocity) / RobotMotion.MAX_VELOCITY;
        boolean recent = time - e.time <= MAX_SCAN_GAP;
        double gap = recent ? time - e.time : 1;
        double acceleration = recent ? (Math.abs(velocity) - Math.abs(e.velocity)) / gap : 0;
        f[1] = clamp((acceleration + RobotMotion.DECELERATION) / (RobotMotion.DECELERATION + RobotMotion.ACCELERATION));
        double turn = recent ? Angles.normalizeRelative(heading - e.heading) / gap * Math.signum(velocity) : 0;
        f[2] = clamp((turn / RobotMotion.MAX_TURN_RATE + 1) / 2);
        f[3] = wallAhead(x, y, frame) / p.wallRange();
        double closest = Math.hypot(closestX - x, closestY - y);
        f[4] = clamp(closest / p.neighbourRange());
        double towards = Angles.bearing(x, y, closestX, closestY) - frame;
        f[5] = (Math.cos(towards) + 1) / 2;
        f[6] = (Math.sin(towards) + 1) / 2;
        f[7] = clamp((enemies - 1) / 8.0);
        if (e.time == Long.MIN_VALUE) {
            e.reversal = time;
            e.deceleration = time;
        }
        double sign = Math.signum(velocity);
        if (sign != 0 && e.sign != 0 && sign != e.sign) {
            e.reversal = time;
        }
        if (sign != 0) {
            e.sign = sign;
        }
        if (recent && Math.abs(velocity) < Math.abs(e.velocity)) {
            e.deceleration = time;
        }
        f[8] = clamp((time - e.reversal) / SINCE_RANGE);
        f[9] = clamp((time - e.deceleration) / SINCE_RANGE);
        e.trail.addLast(new double[] {time, x, y});
        while (e.trail.peekFirst()[0] < time - TRAVEL_TURNS) {
            e.trail.pollFirst();
        }
        double[] first = e.trail.peekFirst();
        double travelTurns = time - first[0];
        // Straight-line distance against the most it could have driven: low for robots that oscillate.
        f[10] = travelTurns <= 0 ? f[0]
                : clamp(Math.hypot(x - first[1], y - first[2]) / (RobotMotion.MAX_VELOCITY * travelTurns));
        f[11] = clamp(Math.hypot(x - field.width() / 2, y - field.height() / 2)
                / Math.hypot(field.width() / 2, field.height() / 2));
        e.time = time;
        e.x = x;
        e.y = y;
        e.frame = frame;
        e.velocity = velocity;
        e.heading = heading;
        e.features = f;
        Record r = new Record(time, x, y, frame);
        e.open.add(r);
        memory.records.computeIfAbsent(name, n -> new Store(p.maxRecords())).add(r, f);
    }

    public void robotDied(String name) {
        enemies.remove(name);
    }

    /** The enemy whose positions weighed most in the last aim, or null. */
    public String aimedAt() {
        return aimedAt;
    }

    /**
     * The heading (mathematical) for a bullet of {@code speed} fired in turn {@code fireTime} from ({@code ox},
     * {@code oy}) at the end of that turn, or NaN without any enemy's records to go by.
     */
    public double aim(long fireTime, double ox, double oy, double speed) {
        List<double[]> candidates = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        for (Map.Entry<String, Enemy> entry : enemies.entrySet()) {
            Enemy e = entry.getValue();
            Store store = memory.records.get(entry.getKey());
            if (store == null || e.features == null || fireTime - e.time > p.maxAge()) {
                continue;
            }
            double enemyDistance = Math.max(Math.hypot(e.x - ox, e.y - oy), 1);
            int neighbours = p.neighbours();
            Record[] nearest = new Record[neighbours];
            double[] distances = new double[neighbours];
            int found = 0;
            // The turns from the enemy's last scan to the bullet's arrival, roughly: the iterations below refine it.
            double reach = fireTime - e.time + enemyDistance / speed;
            double[] w = p.weights();
            double[] ef = e.features;
            int[] weighted = this.weighted;
            for (int n = 0; n < store.size; n++) {
                int slot = (store.start + n) % store.capacity;
                int last = store.last[slot];
                if (last < 0 || !p.everyRound() && store.times[slot] >= e.time || p.reachingOnly() && last < reach) {
                    continue;
                }
                // The sum only grows: once it reaches the farthest neighbour's, the record is out.
                double limit = found < neighbours ? Double.POSITIVE_INFINITY : distances[found - 1];
                double d = 0;
                int base = slot * FEATURES;
                for (int k = 0; k < weighted.length && d < limit; k++) {
                    int i = weighted[k];
                    double diff = store.features[base + i] - ef[i];
                    d += w[i] * diff * diff;
                }
                if (d < limit) {
                    Record r = store.records[slot];
                    int i = found < neighbours ? found++ : found - 1;
                    while (i > 0 && distances[i - 1] > d) {
                        distances[i] = distances[i - 1];
                        nearest[i] = nearest[i - 1];
                        i--;
                    }
                    distances[i] = d;
                    nearest[i] = r;
                }
            }
            double c = Math.cos(e.frame);
            double s = Math.sin(e.frame);
            for (int n = 0; n < found; n++) {
                Record r = nearest[n];
                double elapsed = fireTime - e.time;
                double k = elapsed + enemyDistance / speed;
                double px = Double.NaN;
                double py = Double.NaN;
                for (int iteration = 0; iteration < 3; iteration++) {
                    double[] d = r.at(k);
                    if (d == null) {
                        px = Double.NaN;
                        break;
                    }
                    px = e.x + d[0] * c - d[1] * s;
                    py = e.y + d[0] * s + d[1] * c;
                    k = elapsed + Math.hypot(px - ox, py - oy) / speed;
                }
                if (Double.isNaN(px) || !field.contains(px, py, Field.ROBOT_HALF_SIZE)) {
                    continue;
                }
                double distance = Math.max(Math.hypot(px - ox, py - oy), RobotMotion.HALF_SIZE + 1);
                double weight = 1 / (Math.sqrt(distances[n]) + p.distanceSoftening())
                        / Math.pow(enemyDistance, p.enemyDistancePower());
                candidates.add(new double[] {Angles.bearing(ox, oy, px, py),
                        p.kernelWidth() * Math.atan(RobotMotion.HALF_SIZE / distance), weight});
                owners.add(entry.getKey());
            }
        }
        double best = Double.NaN;
        double bestWeight = 0;
        aimedAt = null;
        for (int i = 0; i < candidates.size(); i++) {
            double a = candidates.get(i)[0];
            double sum = 0;
            double strongest = 0;
            String owner = null;
            for (int j = 0; j < candidates.size(); j++) {
                if (p.singleEnemy() && !owners.get(j).equals(owners.get(i))) {
                    continue;
                }
                double[] other = candidates.get(j);
                double off = Math.abs(relative(a - other[0]));
                if (off < other[1]) {
                    // A triangle over the robot's width: a bullet through its middle counts most.
                    double w = other[2] * (1 - off / other[1]);
                    sum += w;
                    if (w > strongest) {
                        strongest = w;
                        owner = owners.get(j);
                    }
                }
            }
            if (sum > bestWeight) {
                bestWeight = sum;
                best = a;
                aimedAt = owner;
            }
        }
        return best;
    }

    /**
     * {@link Angles#normalizeRelative} of an angle within ±2π, bit for bit, without its remainder, which costs tens of
     * nanoseconds and ran for every pair of predicted positions. Above 2π the subtraction is exact (Sterbenz), as the
     * remainder is.
     */
    static double relative(double angle) {
        double a = angle + Math.PI;
        if (a >= Angles.TWO_PI) {
            a -= Angles.TWO_PI;
        } else if (a < 0) {
            a += Angles.TWO_PI;
        }
        return a - Math.PI;
    }

    /** How far the robot can drive along {@code heading} before its centre is 18 px from a wall, up to the range. */
    private double wallAhead(double x, double y, double heading) {
        double c = Math.cos(heading);
        double s = Math.sin(heading);
        double limit = p.wallRange();
        double margin = Field.ROBOT_HALF_SIZE;
        if (c > 1e-9) {
            limit = Math.min(limit, (field.width() - margin - x) / c);
        } else if (c < -1e-9) {
            limit = Math.min(limit, (margin - x) / c);
        }
        if (s > 1e-9) {
            limit = Math.min(limit, (field.height() - margin - y) / s);
        } else if (s < -1e-9) {
            limit = Math.min(limit, (margin - y) / s);
        }
        return Math.max(0, limit);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
