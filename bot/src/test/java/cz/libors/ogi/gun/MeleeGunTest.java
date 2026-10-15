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
        MeleeGun gun = new MeleeGun(FIELD);
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
    void withoutRecordsThereIsNoAim() {
        MeleeGun gun = new MeleeGun(FIELD);
        gun.scanned("new", 0, 300, 300, 0, 8, 500, 500, 3);
        assertTrue(Double.isNaN(gun.aim(1, 500, 500, 14)));
    }
}
