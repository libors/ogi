package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.BulletMotion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeleeGunTest {

    private static final Field FIELD = new Field(1000, 1000);
    private static final double RADIUS = 300;
    private static final double OMEGA = 8 / RADIUS;

    /** An enemy orbiting the field's middle counter-clockwise at 8 px/turn, scanned every third turn. */
    private static MeleeGun circling(String name, long until) {
        MeleeGun gun = new MeleeGun(FIELD, new MeleeGun.Memory(), MeleeGunParameters.DEFAULT);
        for (long t = 0; t <= until; t += 3) {
            double a = OMEGA * t;
            gun.scanned(name, t, 500 + RADIUS * Math.cos(a), 500 + RADIUS * Math.sin(a), a + Math.PI / 2, 8, 500,
                    500, 2);
        }
        return gun;
    }

    @Test
    void leadsATargetByWhereItsPastSelfWent() {
        MeleeGun gun = circling("circler", 600);
        double speed = BulletMotion.speed(1.92);
        double aim = gun.aim(601, 500, 500, speed);
        // From the middle the bullet flies the radius; the target moves on along the circle meanwhile.
        double expected = OMEGA * (600 + 1 + RADIUS / speed);
        double width = Math.atan(18 / RADIUS);
        assertTrue(Math.abs(Angles.normalizeRelative(aim - expected)) < width,
                "aim " + aim + " against " + Angles.normalizeRelative(expected) + " ± " + width);
        assertEquals("circler", gun.aimedAt());
    }

    @Test
    void aReplayOfTheLogAimsAlike() {
        // The bot's gun logs its inputs; a fresh gun fed the parsed lines must aim bit for bit alike.
        MeleeGun bot = new MeleeGun(FIELD, new MeleeGun.Memory(), MeleeGunParameters.DEFAULT);
        MeleeGun replay = new MeleeGun(FIELD, new MeleeGun.Memory(), MeleeGunParameters.DEFAULT);
        java.util.Random random = new java.util.Random(5);
        double[] xs = {200, 800, 500};
        double[] ys = {300, 700, 150};
        double[] headings = {0, 2, 4};
        for (long t = 0; t < 400; t++) {
            for (int i = 0; i < 3; i++) {
                headings[i] += 0.1 * (random.nextDouble() - 0.5);
                double v = 8 * random.nextDouble() - (i == 2 ? 2 : 0);
                xs[i] = Math.max(30, Math.min(970, xs[i] + v * Math.cos(headings[i])));
                ys[i] = Math.max(30, Math.min(970, ys[i] + v * Math.sin(headings[i])));
                if ((t + i) % 3 != 0) {
                    continue;
                }
                String line = MeleeGunLog.scanLine("e" + i + ",x", t, xs[i], ys[i], headings[i], v,
                        xs[(i + 1) % 3], ys[(i + 1) % 3], 3);
                bot.scanned("e" + i + ",x", t, xs[i], ys[i], headings[i], v, xs[(i + 1) % 3], ys[(i + 1) % 3], 3);
                MeleeGunLog.Scan s = MeleeGunLog.scan(line);
                replay.scanned(s.name(), s.time(), s.x(), s.y(), s.heading(), s.velocity(), s.closestX(),
                        s.closestY(), s.enemies());
            }
            if (t % 7 == 0) {
                double aim = bot.aim(t + 1, 500, 500, 14.3);
                MeleeGunLog.Aim logged = MeleeGunLog.aim(MeleeGunLog.aimLine(t + 1, 500, 500, 14.3, aim,
                        bot.aimedAt()));
                double replayed = replay.aim(logged.fireTime(), logged.x(), logged.y(), logged.speed());
                assertEquals(Double.doubleToLongBits(logged.heading()), Double.doubleToLongBits(replayed), "turn " + t);
                assertEquals(logged.aimedAt(), replay.aimedAt());
            }
        }
        assertTrue(bot.aimedAt() != null);
    }

    @Test
    void theFastRelativeAngleIsNormalizeRelativeBitForBit() {
        java.util.Random random = new java.util.Random(11);
        double[] edges = {0, Math.PI, -Math.PI, 2 * Math.PI, -2 * Math.PI, Math.nextDown(Math.PI), Math.nextUp(-Math.PI),
                Math.nextDown(2 * Math.PI), Math.nextUp(-2 * Math.PI), 1e-300, -1e-300};
        for (double x : edges) {
            assertEquals(Double.doubleToLongBits(Angles.normalizeRelative(x)),
                    Double.doubleToLongBits(MeleeGun.relative(x)), "angle " + x);
        }
        for (int i = 0; i < 1_000_000; i++) {
            // Differences of two bearings from Math.atan2, as the gun takes them.
            double x = Math.atan2(random.nextGaussian(), random.nextGaussian())
                    - Math.atan2(random.nextGaussian(), random.nextGaussian());
            assertEquals(Double.doubleToLongBits(Angles.normalizeRelative(x)),
                    Double.doubleToLongBits(MeleeGun.relative(x)), "angle " + x);
        }
    }

    @Test
    void withoutRecordsThereIsNoAim() {
        MeleeGun gun = new MeleeGun(FIELD, new MeleeGun.Memory(), MeleeGunParameters.DEFAULT);
        gun.scanned("new", 0, 300, 300, 0, 8, 500, 500, 3);
        assertTrue(Double.isNaN(gun.aim(1, 500, 500, 14)));
    }
}
