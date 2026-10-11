package cz.libors.ogi.move;

/**
 * Whether an enemy drives at us, to ram us or to fight at point blank: an exponential average, over our scans of it
 * for the whole battle, of its speed towards us while it is closer than {@link #RANGE} (0 while it is further away).
 * It drives at us while the average is above {@link #THRESHOLD}.
 *
 * <p>Scans further away count as 0, so a robot that is seldom close never qualifies, however it approaches; and the
 * average follows a robot that starts or stops ramming in the middle of a battle within about a hundred scans. On
 * recordings against the population (2026-10-11), robots that drive into us stayed above the threshold for most of
 * the battle, and robots that only close to a distance of their own stayed below it nearly all the time.
 */
public final class RamDetector {

    static final double RANGE = 200;
    static final double WEIGHT = 0.01;
    static final double THRESHOLD = 2;

    private double average;

    /** One scan: the enemy's distance and its velocity towards us (positive when it closes in). */
    public void observe(double distance, double approach) {
        average += WEIGHT * ((distance < RANGE ? approach : 0) - average);
    }

    public boolean drivesAtUs() {
        return average > THRESHOLD;
    }

    double average() {
        return average;
    }
}
