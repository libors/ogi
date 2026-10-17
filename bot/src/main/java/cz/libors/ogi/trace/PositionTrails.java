package cz.libors.ogi.trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where we and the enemies we scanned have been in the last {@link #TURNS} turns, for the overlay: our position every
 * turn, an enemy's at every scan of it (ours or a teammate's). Only fed while the robot paints, so it costs nothing in
 * a rumble; a round starts it afresh. A dead enemy's trail fades like the others.
 */
public final class PositionTrails {

    /** How many turns a position stays in the trail, fading. */
    public static final int TURNS = 80;

    private final Deque<double[]> ours = new ArrayDeque<>();
    private final Map<String, Deque<double[]>> enemies = new HashMap<>();

    /** Our position at the end of turn {@code time}. */
    public void ours(long time, double x, double y) {
        add(ours, time, x, y);
    }

    /** An enemy's position as scanned in turn {@code time}; a scan already in the trail is not added again. */
    public void enemy(String name, long time, double x, double y) {
        add(enemies.computeIfAbsent(name, n -> new ArrayDeque<>()), time, x, y);
    }

    private static void add(Deque<double[]> trail, long time, double x, double y) {
        if (trail.isEmpty() || trail.peekLast()[2] < time) {
            trail.addLast(new double[] {x, y, time});
        }
    }

    /** Adds the trails to the trace, each point {x, y, its age in turns}, and drops the points too old. */
    public void describe(long now, Trace trace) {
        List<double[]> all = new ArrayList<>();
        for (Deque<double[]> trail : enemies.values()) {
            all.addAll(points(trail, now));
        }
        trace.positions(new Trace.PositionView(points(ours, now).toArray(new double[0][]),
                all.toArray(new double[0][])));
    }

    private static List<double[]> points(Deque<double[]> trail, long now) {
        while (!trail.isEmpty() && now - trail.peekFirst()[2] >= TURNS) {
            trail.removeFirst();
        }
        List<double[]> points = new ArrayList<>(trail.size());
        for (double[] p : trail) {
            points.add(new double[] {p[0], p[1], now - p[2]});
        }
        return points;
    }
}
