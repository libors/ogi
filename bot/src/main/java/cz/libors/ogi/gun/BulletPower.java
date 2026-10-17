package cz.libors.ogi.gun;

import cz.libors.ogi.physics.RobotMotion;
import cz.libors.ogi.world.Names;

import java.util.HashMap;
import java.util.Map;

/**
 * The power of our next bullet at a target: 3 at close range or against an enemy we read, else {@link #DEFAULT}; in a
 * duel faster bullets against an enemy we hit rarely, and the energy race's power ({@link RacePower}) once the race is
 * on; never more than kills the target or than we can afford. Why these numbers, and what they gained: bot.md
 * "Bullet power".
 *
 * <p>It keeps our bullets' record against each enemy over the battle ({@link HitRecord}), the copies of one robot in
 * an enemy team together ({@link Names#base}).
 */
public final class BulletPower {

    /**
     * 23/12 and one unit in the last place more: surfers that match a hit to its wave by the speed rounded to tenths
     * often fail to learn it.
     */
    public static final double DEFAULT = 1.916666666666667;
    static final double CLOSE_RANGE = 200;
    /**
     * Full power beyond close range too against an enemy that our bullets hit at least this often over the battle,
     * after this many of them ended: we read its movement, so the slower bullet still hits.
     */
    static final double READ_HIT_RATE = 0.55;
    static final int READ_MIN_BULLETS = 20;
    /** Our record says that we hit an enemy rarely only once this many of our bullets at it ended. */
    static final int RARE_MIN_BULLETS = 30;
    /**
     * In a duel, beyond close range, against an enemy that our bullets hit less often than this: more, faster bullets.
     */
    static final double RARE_HIT_RATE = 0.25;
    static final double RARE_HIT_POWER = 1.45;
    /** Below this energy, in a duel, our power is the energy race's: the round goes to whoever runs out last. */
    static final double RACE_ENERGY = 20;
    /** The race is on too while the enemy is below {@link #RACE_ENERGY} and we are below this: a close race. */
    static final double RACE_CLOSE_ENERGY = 40;
    /** The prior of our hit rate in the race: this many hits in {@link #RACE_PRIOR_BULLETS} bullets. */
    static final double RACE_PRIOR_HITS = 1.5;
    static final double RACE_PRIOR_BULLETS = 10;

    private final Map<String, HitRecord> records = new HashMap<>();

    /** One of our bullets fired at {@code target} ended; {@code hit}: it hit that target. */
    public void ended(String target, boolean hit) {
        String name = Names.base(target);
        HitRecord record = records.get(name);
        if (record == null) {
            record = new HitRecord();
            records.put(name, record);
        }
        record.ended(hit);
    }

    /** Whether our bullets hit this enemy often enough over the battle for full power at any distance. */
    public boolean readsWell(String name) {
        HitRecord record = records.get(Names.base(name));
        return record != null && record.ended() >= READ_MIN_BULLETS && record.rate() >= READ_HIT_RATE;
    }

    /**
     * Whether our bullets hit this enemy less often than {@code rate} over the battle, once
     * {@link #RARE_MIN_BULLETS} of them ended.
     */
    public boolean hitsBelow(String name, double rate) {
        HitRecord record = records.get(Names.base(name));
        return record != null && record.ended() >= RARE_MIN_BULLETS && record.rate() < rate;
    }

    /** Whether a duel at these energies is an energy race. */
    public static boolean racing(double ourEnergy, double targetEnergy) {
        return ourEnergy < RACE_ENERGY || targetEnergy < RACE_ENERGY && ourEnergy < RACE_CLOSE_ENERGY;
    }

    /**
     * The power of our next bullet at {@code target}, 0 if we cannot afford the weakest.
     *
     * @param duel         whether the target is our only enemy and no teammate lives: the rare-hit and race rules
     * @param theirHitRate the target's hits on us per shot over the battle, for the race
     * @param theirPower   the power of its last shot, for the race
     */
    public double power(String target, double distance, double targetEnergy, double ourEnergy, boolean duel,
                        double theirHitRate, double theirPower) {
        double power = distance < CLOSE_RANGE || readsWell(target) ? RobotMotion.MAX_BULLET_POWER
                : duel && hitsBelow(target, RARE_HIT_RATE) ? RARE_HIT_POWER : DEFAULT;
        if (duel && racing(ourEnergy, targetEnergy)) {
            HitRecord record = records.get(Names.base(target));
            double hits = record == null ? 0 : record.hits();
            double ended = record == null ? 0 : record.ended();
            power = RacePower.choose((hits + RACE_PRIOR_HITS) / (ended + RACE_PRIOR_BULLETS), theirHitRate,
                    theirPower, power);
        }
        power = Math.min(power, targetEnergy / 4 + RobotMotion.MIN_BULLET_POWER);
        power = Math.min(power, ourEnergy - RobotMotion.MIN_BULLET_POWER);
        return power >= RobotMotion.MIN_BULLET_POWER ? power : 0;
    }
}
