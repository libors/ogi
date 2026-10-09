package cz.libors.ogi.enemy;

import java.util.HashMap;
import java.util.Map;

/**
 * Where we last saw each robot other than us, to tell whether another robot came close enough to ram an enemy. A ram
 * among other robots is invisible to us and costs the enemy 0.6 energy, a drop that would pass for a shot
 * ({@link FireDetector#nearOtherRobot}).
 */
public final class Neighbours {

    /** Turns a sighting stays recent. */
    public static final int MEMORY = 16;
    /** Distance between centres below which two robots may have rammed since we saw them. */
    public static final double DISTANCE = 150;

    /** By name: turn, x, y of the latest sighting. */
    private final Map<String, double[]> seen = new HashMap<>();

    /** A sighting of a robot, enemy or teammate, at the end of turn {@code time}. */
    public void seen(String name, long time, double x, double y) {
        seen.put(name, new double[] {time, x, y});
    }

    /** Whether a robot other than {@code name} was seen recently close to where {@code name} was seen last. */
    public boolean nearOther(String name, long time) {
        double[] e = seen.get(name);
        if (e == null) {
            return false;
        }
        for (Map.Entry<String, double[]> other : seen.entrySet()) {
            double[] o = other.getValue();
            if (!other.getKey().equals(name) && time - o[0] <= MEMORY
                    && Math.hypot(o[1] - e[1], o[2] - e[2]) < DISTANCE) {
                return true;
            }
        }
        return false;
    }
}
