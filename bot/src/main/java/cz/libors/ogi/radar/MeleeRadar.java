package cz.libors.ogi.radar;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.physics.RobotMotion;

import java.util.List;

/**
 * The radar in a melee: towards the enemy whose last scan is the oldest, instead of spinning. Enemies seldom fill the
 * whole circle around us (from a wall or a corner they lie within a half or a quarter of it), so turning towards the
 * stalest one and back sweeps only the arc that holds them, and each enemy is scanned more often than every eighth
 * turn of a full spin.
 *
 * <ul>
 *   <li>Until every living enemy has been seen this round, the radar spins on.</li>
 *   <li>The radar turns past the stalest enemy's bearing by the angle it may have moved since that scan (at full
 *       speed, plus half a robot), so that the sweep covers it wherever it went.</li>
 *   <li>In the turns before our gun is cool, the gun's target goes first while its scan is older than a turn: the shot
 *       is aimed from a fresh position.</li>
 * </ul>
 */
public final class MeleeRadar {

    /** An enemy's last scan. */
    public record Seen(String name, double x, double y, long time) {
    }

    /** The radar turns at most this far in a turn. */
    static final double MAX_TURN = Math.PI / 4;

    /** The direction of the last turn: 1 counter-clockwise, −1 clockwise. */
    private int direction = 1;

    /**
     * The radar's turn for the next turn, mathematical (counter-clockwise positive), from our position and the radar's
     * heading (mathematical) at the end of this turn.
     *
     * @param others  the enemies alive, as the engine counts them
     * @param target  the gun's target, or null
     * @param gunSoon whether our gun is cool within the next turns
     */
    /** Whether the radar still spins: not every living enemy has been seen this round. */
    public static boolean spinning(List<Seen> enemies, int others) {
        return enemies.size() < others;
    }

    public double turn(long now, double x, double y, double radarHeading, List<Seen> enemies, int others,
                       String target, boolean gunSoon) {
        if (enemies.size() < others) {
            return direction * Double.POSITIVE_INFINITY;
        }
        Seen chosen = null;
        for (Seen e : enemies) {
            if (gunSoon && e.name().equals(target) && now - e.time() > 1) {
                chosen = e;
                break;
            }
            if (chosen == null || e.time() < chosen.time()) {
                chosen = e;
            }
        }
        if (chosen == null) {
            return direction * Double.POSITIVE_INFINITY;
        }
        double distance = Math.max(Math.hypot(chosen.x() - x, chosen.y() - y), 1);
        double moved = RobotMotion.MAX_VELOCITY * (now + 1 - chosen.time()) + RobotMotion.HALF_SIZE;
        double margin = Math.min(MAX_TURN, Math.atan(moved / distance));
        double offset = Angles.normalizeRelative(Angles.bearing(x, y, chosen.x(), chosen.y()) - radarHeading);
        if (offset != 0) {
            direction = offset > 0 ? 1 : -1;
        }
        return offset + direction * margin;
    }
}
