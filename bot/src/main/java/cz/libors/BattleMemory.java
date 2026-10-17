package cz.libors;

import cz.libors.ogi.gun.BulletPower;
import cz.libors.ogi.gun.MeleeGun;
import cz.libors.ogi.gun.MirrorAim;
import cz.libors.ogi.gun.VirtualGuns;
import cz.libors.ogi.move.EnemyWaves;
import cz.libors.ogi.move.GangWatch;
import cz.libors.ogi.shield.Shield;
import cz.libors.ogi.shield.ShieldTrial;
import cz.libors.ogi.trace.HitRates;
import cz.libors.ogi.world.BattleKind;

/**
 * What one robot remembers for the whole battle. Robocode makes a new robot instance for every round, while statics
 * last for the battle and are per robot (engine-model G-22): {@link Ogi} keeps one of these in its only static and
 * hands each component its part. Everything else is per round. (The overlay's layers, switched by keys, are the
 * overlay's own statics.)
 */
final class BattleMemory {

    /** The battle's kind, from its first round's start; null before. */
    BattleKind kind;
    final VirtualGuns.Memory guns = new VirtualGuns.Memory();
    final MirrorAim.Memory mirror = new MirrorAim.Memory();
    final MeleeGun.Memory meleeGun = new MeleeGun.Memory();
    final EnemyWaves.Memory waves = new EnemyWaves.Memory();
    final Shield.Memory shield = new Shield.Memory();
    /** When we stop shielding against an enemy. */
    final ShieldTrial shieldTrial = new ShieldTrial();
    /** Our bullets' power, with their record against each enemy. */
    final BulletPower power = new BulletPower();
    /** Whether the enemies gang our robots. */
    final GangWatch gangs = new GangWatch();
    /** Our hit rates for the overlay. */
    final HitRates hitRates = new HitRates();
}
