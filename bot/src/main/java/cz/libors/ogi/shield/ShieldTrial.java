package cz.libors.ogi.shield;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The bullet shield's trial: every duel starts with the shield, and against an enemy whose bullets beat it we surf for
 * the rest of the battle. Not the other way round: guns that learn our movement learn a few rounds of surfing, and a
 * shield tried after them fails. The rules' reasons and the evidence behind their numbers: bot.md "Bullet shield".
 *
 * <p>Its account lasts for the battle, per enemy by its full name: the damage the enemy's bullets did to us while we
 * shielded, its shots then, the rounds it won, its bullets we shot down and those that hit us.
 */
public final class ShieldTrial {

    /**
     * The shield fails once the damage the enemy's bullets did to us while we shielded exceeds this much plus
     * {@link #DAMAGE_PER_SHOT} per shot it fired then: two full-power hits to start with, while the aim models learn.
     */
    static final double DAMAGE_BUDGET = 35;
    static final double DAMAGE_PER_SHOT = 0.25;
    /** It fails once we lost this many rounds while shielding: many weak hits win the race within the budget. */
    static final int LOST_ROUNDS = 2;
    /**
     * It fails once this many of the enemy's bullets ended while we shielded and fewer than this share of them were
     * shot down.
     */
    static final int FAIL_BULLETS = 20;
    static final double FAIL_DOWN_SHARE = 0.5;
    /** It fails once this many of the enemy's bullets hit us while we shielded before we shot one down. */
    static final int FAIL_HITS = 6;

    /** One enemy's account while we shielded against it. */
    private static final class Account {
        double damage;
        int shots;
        int lost;
        int down;
        int hits;
    }

    private final Map<String, Account> accounts = new HashMap<>();
    private final Set<String> dropped = new HashSet<>();

    /** Whether the shield failed against this enemy: we surf it for the rest of the battle. */
    public boolean dropped(String enemy) {
        return dropped.contains(enemy);
    }

    /** The enemy fired while we shielded. Returns the trial's verdict if the shield fails now, else null. */
    public String fired(String enemy) {
        Account a = account(enemy);
        a.shots++;
        return check(enemy, a);
    }

    /** One of the enemy's bullets hit us while we shielded, for this damage; returns as {@link #fired}. */
    public String hit(String enemy, double damage) {
        Account a = account(enemy);
        a.damage += damage;
        a.hits++;
        return check(enemy, a);
    }

    /** We shot one of the enemy's bullets down; returns as {@link #fired}. */
    public String shotDown(String enemy) {
        Account a = account(enemy);
        a.down++;
        return check(enemy, a);
    }

    /** The enemy won a round in which we shielded against it; returns as {@link #fired}. */
    public String roundLost(String enemy) {
        Account a = account(enemy);
        a.lost++;
        return check(enemy, a);
    }

    private Account account(String enemy) {
        // No lambda: its first call costs milliseconds, enough to skip a turn.
        Account a = accounts.get(enemy);
        if (a == null) {
            a = new Account();
            accounts.put(enemy, a);
        }
        return a;
    }

    private String check(String enemy, Account a) {
        int ended = a.down + a.hits;
        boolean failing = ended >= FAIL_BULLETS && a.down < FAIL_DOWN_SHARE * ended
                || a.down == 0 && a.hits >= FAIL_HITS;
        if ((a.damage > DAMAGE_BUDGET + DAMAGE_PER_SHOT * a.shots || a.lost >= LOST_ROUNDS || failing)
                && dropped.add(enemy)) {
            return String.format(Locale.ROOT, "dropped damage %.1f shots %d lost %d down %d hit %d", a.damage,
                    a.shots, a.lost, a.down, a.hits);
        }
        return null;
    }
}
