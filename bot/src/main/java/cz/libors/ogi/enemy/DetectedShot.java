package cz.libors.ogi.enemy;

/**
 * An enemy shot inferred from its energy.
 *
 * <p>Times are engine turns. The fire time is the turn in which the enemy issued the fire command; the bullet leaves
 * from the enemy's position at the end of that turn and has moved {@code (t - fireTime) × speed} by turn t
 * (engine-model G-01, G-03). The fire turn lies between the previous scan and the turn before the revealing one;
 * {@code fireTime} is the earliest turn of that range in which the enemy's modelled gun was cool, the usual choice of
 * a bot that fires as soon as it can.
 *
 * @param earliestFireTime the previous scan's turn
 * @param latestFireTime   the turn before {@code detectedAt}
 * @param x                estimated origin: the enemy's position at the end of {@code fireTime}
 * @param detectedAt       the turn of the scan that revealed the shot
 */
public record DetectedShot(String shooter, long fireTime, long earliestFireTime, long latestFireTime, double power,
                           double x, double y, long detectedAt) {

    /** Whether the fire time is known exactly: the enemy was scanned in the fire turn and the turn after. */
    public boolean exactTime() {
        return earliestFireTime == latestFireTime;
    }
}
