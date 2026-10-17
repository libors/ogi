package cz.libors.ogi.move;

import java.util.List;

/**
 * Whether the enemies gang our robots, counted over a team battle: the switch of the melee movement's gang defence
 * ({@link MeleeMovement#gangDefence}). A robot of ours is ganged in a turn when {@link MeleeMovement#GANG_SIZE} or
 * more enemies are within {@link MeleeMovement#GANG_RADIUS} of it; each robot counts itself and its teammates as it
 * sees them. The bot keeps the count for the battle; why the share is a switch: bot.md "Team play", Gang defence.
 */
public final class GangWatch {

    /** The gang defence is on once this share of our robot-turns was ganged, over at least so many. */
    static final double SHARE = 0.04;
    static final long MIN_TURNS = 200;

    private long robotTurns;
    private long gangedTurns;

    /**
     * Counts this turn's robots of ours: we at ({@code x}, {@code y}) and the teammates among {@code robots}; returns
     * whether the enemies gang.
     */
    public boolean turn(double x, double y, List<MeleeMovement.Robot> robots) {
        count(MeleeMovement.enemiesNear(x, y, robots));
        for (MeleeMovement.Robot r : robots) {
            if (!r.enemy()) {
                count(MeleeMovement.enemiesNear(r.x(), r.y(), robots));
            }
        }
        return robotTurns >= MIN_TURNS && gangedTurns >= SHARE * robotTurns;
    }

    private void count(int enemiesNear) {
        robotTurns++;
        gangedTurns += enemiesNear >= MeleeMovement.GANG_SIZE ? 1 : 0;
    }
}
