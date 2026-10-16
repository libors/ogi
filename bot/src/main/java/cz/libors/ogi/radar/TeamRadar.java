package cz.libors.ogi.radar;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.physics.RadarScan;
import cz.libors.ogi.physics.RobotMotion;
import cz.libors.ogi.radar.MeleeRadar.Seen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The team's radars: which of our robots watches which enemies, so that every enemy is scanned in every turn if the
 * robots can manage it.
 *
 * <p>Left to themselves the robots all turn towards the enemy the team saw longest ago, and a teammate's scan reaches
 * the others a turn late, so about half of the scans repeat each other while an enemy goes unseen in two turns of
 * five. Here every robot computes the same assignment from what the whole team knew at the end of the last turn:
 * where each of our robots is and where its radar points (as each reported), and every enemy's last scan taken before
 * this turn. No robot waits for another's decision, and they agree because their inputs and the computation are the
 * same (engine-model G-23: every report of the last turn is in).
 *
 * <ul>
 *   <li>A robot can watch a group of enemies in every turn when their arc, as it sees them, fits in one turn of the
 *       radar (45°): it sweeps from one end of the arc to the other and back. Each enemy's part of the arc is its
 *       bearing widened by the angle it may have moved since its scan, as in {@link MeleeRadar}. A wider arc takes
 *       several turns a sweep, and its enemies are scanned that much less often.</li>
 *   <li>The assignment gives every enemy in some robot's range to exactly one robot and minimises, over all of them,
 *       the turns between an enemy's scans; then the turns a radar needs to reach its arc (a robot does not swap
 *       enemies with a teammate for nothing); then a robot's distance to its enemies; and a robot gets a group only
 *       where one robot each will not do. With at most five robots and five enemies every assignment is searched.</li>
 *   <li>A robot with no enemy of its own keeps the melee radar (the stalest enemy first), which also looks for the
 *       enemies out of everybody's range.</li>
 * </ul>
 */
public final class TeamRadar {

    /**
     * One of our robots as the team knows it: where it is at the end of turn {@code time} and where its radar points
     * then (mathematical), from its own report of the turn before.
     */
    public record Watcher(String name, double x, double y, double radarHeading, long time) {
    }

    /** An arc of the radar: its centre and half its width, mathematical. */
    public record Arc(double centre, double halfWidth) {
    }

    /** The turns between two scans count first; this is the weight of one turn of the radar to reach its arc. */
    static final double REACH_WEIGHT = 0.1;
    /** The weight of a robot's distance to an enemy it watches, per radar range: the nearer robot wins a tie. */
    static final double DISTANCE_WEIGHT = 0.01;
    /** The cost of each enemy beyond the first that one robot watches: one robot each, where it does as well. */
    static final double GROUP_COST = 0.05;
    /** More enemies than this are not planned (a TeamRumble battle has five). */
    static final int MAX_ENEMIES = 8;
    /** A robot's report older than this many turns leaves it out of the plan. */
    static final long MAX_REPORT_AGE = 2;

    /** The latest scan of each living enemy taken before the current turn, the same on every robot. */
    private final Map<String, Seen> known = new HashMap<>();
    /** Scans of the current turn: they join {@link #known} in the next turn, when the teammates have them too. */
    private final List<Seen> pending = new ArrayList<>();
    private final Map<String, Watcher> watchers = new HashMap<>();
    private final Set<String> dead = new HashSet<>();

    /** A scan of an enemy, ours or a teammate's. */
    public void scanned(String name, double x, double y, long time) {
        if (!dead.contains(name)) {
            pending.add(new Seen(name, x, y, time));
        }
    }

    /** A robot's report of its state, ours included: the newest one counts. */
    public void reported(Watcher w) {
        Watcher previous = watchers.get(w.name());
        if (!dead.contains(w.name()) && (previous == null || w.time() >= previous.time())) {
            watchers.put(w.name(), w);
        }
    }

    public void died(String name) {
        dead.add(name);
        known.remove(name);
        watchers.remove(name);
        pending.removeIf(s -> s.name().equals(name));
    }

    /**
     * The enemies robot {@code me} watches in turn {@code now}, by name; empty when it has none of its own, null when
     * there is no plan (it has not reported yet).
     */
    public List<String> watch(long now, String me) {
        for (Iterator<Seen> it = pending.iterator(); it.hasNext(); ) {
            Seen s = it.next();
            if (s.time() < now) {
                it.remove();
                Seen previous = known.get(s.name());
                // Two robots that scan an enemy in one turn compute its position from their own; they agree on the
                // turn but not on the last bits, so one of them is chosen by its value, not by which came first.
                if (previous == null || s.time() > previous.time() || s.time() == previous.time()
                        && (s.x() < previous.x() || s.x() == previous.x() && s.y() < previous.y())) {
                    known.put(s.name(), s);
                }
            }
        }
        List<Watcher> team = new ArrayList<>();
        for (Watcher w : watchers.values()) {
            if (now - w.time() <= MAX_REPORT_AGE) {
                team.add(w);
            }
        }
        if (team.stream().noneMatch(w -> w.name().equals(me))) {
            return null;
        }
        Map<String, List<String>> plan = assign(now, team, new ArrayList<>(known.values()));
        return plan == null ? null : plan.getOrDefault(me, List.of());
    }

    /**
     * Every enemy in some robot's range to exactly one robot, at the least cost (see the class comment); null with too
     * many enemies. Robots and enemies are taken in the order of their names, so every robot gets the same result.
     */
    static Map<String, List<String>> assign(long now, List<Watcher> team, List<Seen> enemies) {
        List<Watcher> ws = new ArrayList<>(team);
        ws.sort(Comparator.comparing(Watcher::name));
        List<Seen> es = new ArrayList<>(enemies);
        es.sort(Comparator.comparing(Seen::name));
        if (es.size() > MAX_ENEMIES) {
            return null;
        }
        int n = es.size();
        int all = (1 << n) - 1;
        double[][] cost = new double[ws.size()][1 << n];
        int coverable = 0;
        for (int i = 0; i < ws.size(); i++) {
            Watcher w = ws.get(i);
            for (int sub = 1; sub <= all; sub++) {
                cost[i][sub] = cost(now, w, es, sub);
            }
            for (int e = 0; e < n; e++) {
                if (cost[i][1 << e] < Double.POSITIVE_INFINITY) {
                    coverable |= 1 << e;
                }
            }
        }
        // best[i][mask]: the least cost of watching the enemies of mask with robots i, i + 1, ...
        double[][] best = new double[ws.size() + 1][1 << n];
        int[][] choice = new int[ws.size()][1 << n];
        for (int mask = 1; mask <= all; mask++) {
            best[ws.size()][mask] = Double.POSITIVE_INFINITY;
        }
        for (int i = ws.size() - 1; i >= 0; i--) {
            for (int mask = 0; mask <= all; mask++) {
                double b = Double.POSITIVE_INFINITY;
                int c = 0;
                // Every subset of mask, the empty one last; a later subset must be strictly cheaper to win.
                for (int sub = mask; ; sub = (sub - 1) & mask) {
                    double v = cost[i][sub] + best[i + 1][mask ^ sub];
                    if (v < b) {
                        b = v;
                        c = sub;
                    }
                    if (sub == 0) {
                        break;
                    }
                }
                best[i][mask] = b;
                choice[i][mask] = c;
            }
        }
        Map<String, List<String>> plan = new HashMap<>();
        int mask = coverable;
        for (int i = 0; i < ws.size(); i++) {
            int sub = choice[i][mask];
            List<String> names = new ArrayList<>();
            for (int e = 0; e < n; e++) {
                if ((sub & 1 << e) != 0) {
                    names.add(es.get(e).name());
                }
            }
            plan.put(ws.get(i).name(), names);
            mask ^= sub;
        }
        return plan;
    }

    /** The cost of robot w watching the enemies of {@code sub}; infinite if one of them may be out of its range. */
    private static double cost(long now, Watcher w, List<Seen> es, int sub) {
        List<Seen> group = new ArrayList<>();
        double distances = 0;
        for (int e = 0; e < es.size(); e++) {
            if ((sub & 1 << e) != 0) {
                Seen s = es.get(e);
                double d = Math.hypot(s.x() - w.x(), s.y() - w.y());
                if (d + RobotMotion.MAX_VELOCITY * (now + 1 - s.time()) > RadarScan.RADIUS) {
                    return Double.POSITIVE_INFINITY;
                }
                distances += d;
                group.add(s);
            }
        }
        Arc arc = arc(now, w.x(), w.y(), group);
        double width = 2 * arc.halfWidth();
        int sweepTurns = Math.max(1, (int) Math.ceil(width / MeleeRadar.MAX_TURN - 1e-9));
        double reach = Math.max(0, Math.abs(Angles.normalizeRelative(arc.centre() - w.radarHeading()))
                - arc.halfWidth()) / MeleeRadar.MAX_TURN;
        int k = group.size();
        return k * (sweepTurns - 1) + REACH_WEIGHT * k * reach + DISTANCE_WEIGHT * distances / RadarScan.RADIUS
                + GROUP_COST * (k - 1);
    }

    /**
     * The narrowest arc around ({@code x}, {@code y}) that holds every enemy of the group wherever it may have moved
     * since its scan, by the end of the next turn: its bearing widened on each side by the margin of
     * {@link MeleeRadar}.
     */
    public static Arc arc(long now, double x, double y, List<Seen> group) {
        int k = group.size();
        double[] low = new double[k];
        double[] width = new double[k];
        for (int i = 0; i < k; i++) {
            Seen s = group.get(i);
            double margin = MeleeRadar.margin(now, x, y, s);
            low[i] = Angles.bearing(x, y, s.x(), s.y()) - margin;
            width[i] = 2 * margin;
        }
        // The narrowest arc starts at one enemy's low edge and runs counter-clockwise past every enemy's high edge.
        double bestStart = 0;
        double bestWidth = Double.POSITIVE_INFINITY;
        for (int i = 0; i < k; i++) {
            double w = 0;
            for (int j = 0; j < k; j++) {
                w = Math.max(w, Angles.normalizeAbsolute(low[j] - low[i]) + width[j]);
            }
            if (w < bestWidth) {
                bestWidth = w;
                bestStart = low[i];
            }
        }
        return new Arc(Angles.normalizeAbsolute(bestStart + bestWidth / 2), bestWidth / 2);
    }

    /**
     * The radar's turn (mathematical) to sweep the arc: to its end farther from where the radar points, across the
     * arc when the radar is inside it or beyond it. From one end the sweep covers the whole arc; the next turn sweeps
     * back.
     */
    public static double sweep(double radarHeading, Arc arc) {
        double offset = Angles.normalizeRelative(arc.centre() - radarHeading);
        return offset >= 0 ? offset + arc.halfWidth() : offset - arc.halfWidth();
    }
}
