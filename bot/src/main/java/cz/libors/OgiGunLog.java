package cz.libors;

import cz.libors.ogi.gun.GunLog;
import cz.libors.ogi.gun.VirtualGuns;
import robocode.Bullet;
import robocode.BulletHitBulletEvent;
import robocode.HitByBulletEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Ogi that also logs its gun's inputs and decisions for the offline gun bench (see {@link Ogi#logsGun()}). It plays
 * exactly like Ogi. Its jar is deployed only locally and to the harness's workers, never to a rumble.
 *
 * <p>Besides the gun's log it records which of its bullets an enemy bullet destroyed ({@link GunLog#SHIELD}): the
 * bench would otherwise score them by the target's movement alone, and against robots that shoot bullets down
 * (bullet shielding) that scores misses as hits.
 *
 * <p>For diagnosing melee battles it also logs every bullet that hits us ({@value #HIT}: the turn, the bullet's power
 * and position, our energy after it and the shooter) and, when a melee comes down to a duel, both sides' energy
 * ({@value #DUEL}: the turn, our energy, the enemy's at its last scan and its name).
 */
public class OgiGunLog extends Ogi {

    public static final String HIT = "hitbybullet";
    public static final String DUEL = "duelstart";

    /** Each enemy's energy at its last scan this round. */
    private final Map<String, Double> energies = new HashMap<>();
    private boolean melee;

    /** The plan's turn of each bullet fired this round, until one is shot down; a new robot every round. */
    private final Map<Bullet, Long> planOf = new HashMap<>();

    @Override
    protected boolean logsGun() {
        return true;
    }

    @Override
    protected void fired(Bullet bullet, VirtualGuns.Plan plan) {
        if (bullet != null) {
            planOf.put(bullet, plan.time());
        }
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        super.onScannedRobot(e);
        energies.put(e.getName(), e.getEnergy());
        melee |= getOthers() > 1;
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        super.onHitByBullet(e);
        setDebugProperty(HIT, String.format(Locale.ROOT, "%d,%s,%s,%s,%s,%s", e.getTime(), e.getPower(),
                e.getBullet().getX(), e.getBullet().getY(), getEnergy(), e.getName()));
    }

    @Override
    public void onRobotDeath(RobotDeathEvent e) {
        super.onRobotDeath(e);
        energies.remove(e.getName());
        if (melee && getOthers() == 1 && energies.size() == 1) {
            Map.Entry<String, Double> last = energies.entrySet().iterator().next();
            setDebugProperty(DUEL, String.format(Locale.ROOT, "%d,%s,%s,%s", e.getTime(), getEnergy(),
                    last.getValue(), last.getKey()));
        }
    }

    @Override
    public void onBulletHitBullet(BulletHitBulletEvent e) {
        super.onBulletHitBullet(e);
        Long time = planOf.remove(e.getBullet());
        if (time != null) {
            setDebugProperty(GunLog.SHIELD, GunLog.shieldLine(time, e.getHitBullet().getName()));
        }
    }
}
