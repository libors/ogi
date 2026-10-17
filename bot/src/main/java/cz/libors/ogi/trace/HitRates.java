package cz.libors.ogi.trace;

import java.util.HashMap;
import java.util.Map;

/**
 * Our bullets' hit rates over the battle, for the overlay: on each enemy we aimed at (by its own name), and the melee
 * gun's on any enemy. Bullets shot down by an enemy bullet are left out. The bot keeps them for the battle.
 */
public final class HitRates {

    /** Per enemy aimed at: {bullets ended, hits on it}. */
    private final Map<String, int[]> aimed = new HashMap<>();
    private int meleeHits;
    private int meleeEnded;

    /** One of our bullets aimed at {@code target} ended; {@code hit}: it hit that target. */
    public void aimed(String target, boolean hit) {
        int[] a = aimed.get(target);
        if (a == null) {
            a = new int[2];
            aimed.put(target, a);
        }
        a[0]++;
        a[1] += hit ? 1 : 0;
    }

    /** One of the melee gun's bullets ended; {@code hitEnemy}: it hit an enemy. */
    public void melee(boolean hitEnemy) {
        meleeHits += hitEnemy ? 1 : 0;
        meleeEnded++;
    }

    /** Our hit rate on this enemy, NaN before {@code minBullets} of our bullets aimed at it ended. */
    public double rate(String target, int minBullets) {
        int[] a = aimed.get(target);
        return a != null && a[0] >= minBullets ? (double) a[1] / a[0] : Double.NaN;
    }

    /** The melee gun's hit rate on any enemy, NaN before one of its bullets ended. */
    public double meleeRate() {
        return meleeEnded > 0 ? (double) meleeHits / meleeEnded : Double.NaN;
    }
}
