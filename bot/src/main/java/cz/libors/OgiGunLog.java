package cz.libors;

import cz.libors.ogi.gun.GuessFactorGun;
import cz.libors.ogi.gun.GunLog;
import robocode.Bullet;
import robocode.BulletHitBulletEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Ogi that also logs its gun's inputs and decisions for the offline gun bench (see {@link Ogi#logsGun()}). It plays
 * exactly like Ogi. Its jar is deployed only locally and to the harness's workers, never to a rumble.
 *
 * <p>Besides the gun's log it records which of its bullets an enemy bullet destroyed ({@link GunLog#SHIELD}): the
 * bench would otherwise score them by the target's movement alone, and against robots that shoot bullets down
 * (bullet shielding) that scores misses as hits.
 */
public class OgiGunLog extends Ogi {

    /** The plan's turn of each bullet fired this round, until one is shot down; a new robot every round. */
    private final Map<Bullet, Long> planOf = new HashMap<>();

    @Override
    protected boolean logsGun() {
        return true;
    }

    @Override
    protected void fired(Bullet bullet, GuessFactorGun.Plan plan) {
        if (bullet != null) {
            planOf.put(bullet, plan.time());
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
