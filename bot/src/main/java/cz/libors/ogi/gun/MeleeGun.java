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
 * <p>The records last for the battle (statics, engine-model G-22); the records still collecting displacements are
 * per round.
 */
public final class MeleeGun {

    /** Displacements are collected for this many turns after a record's scan: longer than any bullet's flight. */
    static final int HORIZON = 70;
    /** Records kept per enemy; the oldest go first. */
    static final int MAX_RECORDS = 1500;
    /** The most similar records each enemy's aim uses. */
    static final int NEIGHBOURS = 12;
    /** Features, each in about [0, 1]. */
    static final int FEATURES = 8;
    /** The wall ahead counts up to this far, and the closest robot's distance up to that far. */
    static final double WALL_RANGE = 400;
    static final double NEIGHBOUR_RANGE = 600;
    /** A gap between scans longer than this tells nothing about acceleration and turning. */
    static final int MAX_SCAN_GAP = 8;
    /** A neighbour's weight: 1 / (its feature distance + this). */
    static final double DISTANCE_SOFTENING = 0.05;
    /** Enemies whose last scan is older than this many turns are not aimed at. */
    static final int MAX_AGE = 8;

    /** One scan of an enemy and where it went from there. */
    static final class Record {
        final double[] features;
        final long time;
        final double x;
        final double y;
        /** The way of travel at the scan, mathematical: the heading, turned round while it drove backwards. */
        final double frame;
        int samples;
        int[] turns = new int[8];
        float[] forward = new float[8];
        float[] left = new float[8];

        Record(double[] features, long time, double x, double y, double frame) {
            this.features = features;
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
        final Deque<Record> open = new ArrayDeque<>();
    }

    private static final Map<String, List<Record>> RECORDS = new HashMap<>();

    private final Field field;
    private final Map<String, Enemy> enemies = new HashMap<>();
    /** The enemy whose positions weighed most in the last aim, or null. */
    private String aimedAt;

    public MeleeGun(Field field) {
        this.field = field;
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
            if (time - r.time > HORIZON) {
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
        f[3] = wallAhead(x, y, frame) / WALL_RANGE;
        double closest = Math.hypot(closestX - x, closestY - y);
        f[4] = clamp(closest / NEIGHBOUR_RANGE);
        double towards = Angles.bearing(x, y, closestX, closestY) - frame;
        f[5] = (Math.cos(towards) + 1) / 2;
        f[6] = (Math.sin(towards) + 1) / 2;
        f[7] = clamp((enemies - 1) / 8.0);
        e.time = time;
        e.x = x;
        e.y = y;
        e.frame = frame;
        e.velocity = velocity;
        e.heading = heading;
        e.features = f;
        Record r = new Record(f, time, x, y, frame);
        e.open.add(r);
        List<Record> list = RECORDS.computeIfAbsent(name, n -> new ArrayList<>());
        list.add(r);
        if (list.size() > MAX_RECORDS) {
            list.remove(0);
        }
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
            List<Record> records = RECORDS.get(entry.getKey());
            if (records == null || e.features == null || fireTime - e.time > MAX_AGE) {
                continue;
            }
            double enemyDistance = Math.max(Math.hypot(e.x - ox, e.y - oy), 1);
            Record[] nearest = new Record[NEIGHBOURS];
            double[] distances = new double[NEIGHBOURS];
            int found = 0;
            for (Record r : records) {
                if (r.samples == 0 || r.time >= e.time) {
                    continue;
                }
                double d = 0;
                for (int i = 0; i < FEATURES; i++) {
                    double diff = r.features[i] - e.features[i];
                    d += diff * diff;
                }
                if (found < NEIGHBOURS || d < distances[found - 1]) {
                    int i = found < NEIGHBOURS ? found++ : found - 1;
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
                double weight = 1 / (Math.sqrt(distances[n]) + DISTANCE_SOFTENING) / enemyDistance;
                candidates.add(new double[] {Angles.bearing(ox, oy, px, py),
                        Math.atan(RobotMotion.HALF_SIZE / distance), weight});
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
                double[] other = candidates.get(j);
                double off = Math.abs(Angles.normalizeRelative(a - other[0]));
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

    /** How far the robot can drive along {@code heading} before its centre is 18 px from a wall, up to the range. */
    private double wallAhead(double x, double y, double heading) {
        double c = Math.cos(heading);
        double s = Math.sin(heading);
        double limit = WALL_RANGE;
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
