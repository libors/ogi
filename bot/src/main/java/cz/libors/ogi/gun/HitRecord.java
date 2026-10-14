package cz.libors.ogi.gun;

/**
 * Our real bullets at one enemy over the battle: how many ended, and how many of them hit it. A bullet that an enemy
 * bullet shot down does not count, and neither does one still in the air when the round ends: the aim decided
 * neither.
 */
public final class HitRecord {

    private int ended;
    private int hits;

    /** One of our bullets at this enemy ended: it hit the enemy, or it missed (a wall, another robot). */
    public void ended(boolean hit) {
        ended++;
        if (hit) {
            hits++;
        }
    }

    public int ended() {
        return ended;
    }

    public int hits() {
        return hits;
    }

    /** Hits per bullet ended; NaN before the first one ended. */
    public double rate() {
        return ended == 0 ? Double.NaN : (double) hits / ended;
    }
}
