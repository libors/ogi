package cz.libors.ogi.shield;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.EngineBullet;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.RobotBox;
import cz.libors.ogi.physics.RobotMotion;
import cz.libors.ogi.wave.Wave;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShieldTest {

    private static final Field FIELD = new Field(800, 600);
    private static final double COOLING = 0.1;
    private static final double[] MOVES = ShieldPlanner.STEPS;

    /**
     * Planned shield shots against the engine's rules: the enemy's bullet flies along the expected heading, ours is
     * fired in the next turn from where the plan's move takes us, both move turn by turn with the engine's arithmetic
     * ({@link BulletMotion}), and they must collide whichever the engine moves first (engine-model G-04), also when
     * the enemy's heading is off by most of the margin, before the enemy's bullet touches our box.
     */
    @Test
    void plannedShotsCollideWithTheExpectedBulletInEitherOrder() {
        assertMostPlannedShotsCollide(3, 0.5, 2.5, 3, 150, 600, 8, 0.9);
    }

    /**
     * An enemy close by leaves only crossings near us: its bullet must not touch our box first, from any side, with
     * any seed. The planner takes the expected line as passing through our centre, with {@code CLEARANCE} (2 px) to
     * spare: a line further off enters our box sooner, by up to about 4 px when it comes in 20–30° off an axis, so
     * here the enemy has moved at most 2 px sideways since it aimed.
     */
    @Test
    void shotsAtCloseEnemiesMeetTheBulletBeforeItReachesUs() {
        for (long seed = 1; seed <= 20; seed++) {
            assertMostPlannedShotsCollide(seed, 1.5, 1.5, 3, 75, 110, 2, 0.5);
        }
    }

    /**
     * Against bullets of the weakest power we may fire only as weak a bullet, of the same speed: the two close in by
     * two moves a turn, and a shot aimed only at the middles of the enemy bullet's moves would miss half the turns.
     */
    @Test
    void shotsOfTheEnemysSpeedAreFoundToo() {
        assertMostPlannedShotsCollide(3, 0.1, 0, 0.1, 150, 600, 8, 0.9);
    }

    /**
     * Random cases from {@code seed}: we stand, the enemy {@code minDistance} to {@code maxDistance} away fired a
     * bullet of {@code minPower} plus up to {@code powerRange} at us head-on from where it was a turn before, up to
     * {@code maxDrift} px sideways of where it fired from; a plan in more than {@code share} of them.
     */
    private static void assertMostPlannedShotsCollide(long seed, double minPower, double powerRange, double maxPower,
                                                      double minDistance, double maxDistance, double maxDrift,
                                                      double share) {
        Random random = new Random(seed);
        int planned = 0;
        int cases = 300;
        for (int k = 0; k < cases; k++) {
            EngineRobot me = new EngineRobot();
            me.x = 60 + 680 * random.nextDouble();
            me.y = 60 + 480 * random.nextDouble();
            me.energy = 100;
            me.adjustGunForBodyTurn = true;
            me.adjustRadarForGunTurn = true;
            me.adjustRadarForBodyTurn = true;
            double ex;
            double ey;
            do {
                double d = minDistance + (maxDistance - minDistance) * random.nextDouble();
                double a = Angles.TWO_PI * random.nextDouble();
                ex = me.x + d * Math.cos(a);
                ey = me.y + d * Math.sin(a);
            } while (ex < 40 || ex > 760 || ey < 40 || ey > 560);
            double bearing = Angles.bearing(me.x, me.y, ex, ey);
            me.bodyHeading = Angles.toRobocode(bearing + Math.PI / 2);
            me.gunHeading = Angles.toRobocode(bearing + 0.1 * (random.nextDouble() - 0.5));
            // The enemy aimed head-on from where it was the turn before; it has moved since, sideways.
            double drift = maxDrift * (2 * random.nextDouble() - 1);
            double bx = ex - drift * Math.sin(bearing);
            double by = ey + drift * Math.cos(bearing);
            double expected = Angles.bearing(bx, by, me.x, me.y);
            double power = minPower + powerRange * random.nextDouble();
            long fireTime = 100;
            Wave wave = new Wave("enemy", ex, ey, fireTime, power);
            long now = fireTime + 1;
            Optional<ShieldPlanner.Plan> plan = ShieldPlanner.plan(wave, expected, me, now, MOVES, 0, maxPower,
                    maxPower, 5e-5, FIELD, COOLING);
            if (plan.isEmpty() || !plan.get().ready() || plan.get().margin() <= 0) {
                continue;
            }
            planned++;
            ShieldPlanner.Plan p = plan.get();
            EngineRobot moved = me.copy();
            moved.distanceRemaining = p.distance();
            moved.bodyTurnRemaining = p.bodyTurn();
            moved.gunTurnRemaining = p.gunTurn();
            RobotMotion.move(moved, 800, 600, COOLING);
            for (double off : new double[] {0, 0.9 * p.margin(), -0.9 * p.margin()}) {
                for (boolean enemyFirst : new boolean[] {true, false}) {
                    assertTrue(collides(wave, expected + off, moved, now + 1, p.power(), enemyFirst),
                            "seed " + seed + " case " + k + " " + p + " off " + off
                                    + (enemyFirst ? " enemy first" : " ours first"));
                }
            }
        }
        assertTrue(planned > cases * share, "seed " + seed + ", a plan in most cases: " + planned);
    }

    /** Whether the enemy's bullet along {@code heading} meets ours, fired in turn {@code fireTime} by {@code gun}. */
    private static boolean collides(Wave wave, double heading, EngineRobot gun, long fireTime, double power,
                                    boolean enemyFirst) {
        EngineBullet enemy = new EngineBullet();
        enemy.x = enemy.lastX = wave.x();
        enemy.y = enemy.lastY = wave.y();
        enemy.heading = Angles.toRobocode(heading);
        enemy.power = wave.power();
        EngineBullet ours = null;
        RobotBox box = new RobotBox(gun.x, gun.y);
        for (long t = wave.fireTime() + 1; t < wave.fireTime() + 200; t++) {
            if (t == fireTime + 1) {
                ours = BulletMotion.fired(gun, power);
            }
            EngineBullet first = enemyFirst || ours == null ? enemy : ours;
            EngineBullet second = first == enemy ? ours : enemy;
            BulletMotion.move(first);
            if (first == enemy && BulletMotion.hits(enemy, box)) {
                return false;
            }
            if (second != null && BulletMotion.cross(first, second)) {
                return true;
            }
            if (second != null) {
                BulletMotion.move(second);
                if (second == enemy && BulletMotion.hits(enemy, box)) {
                    return false;
                }
                if (BulletMotion.cross(second, first)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void aimLearnsTheModelAndTheOffsetTheBulletsFollow() {
        ShieldAim aim = new ShieldAim();
        Random random = new Random(5);
        ShieldAim.Bullet b = null;
        for (int i = 0; i < 10; i++) {
            double[] before = {400 + 50 * random.nextDouble(), 300, Angles.TWO_PI * random.nextDouble(), 8};
            double[] origin = {before[0] + 8 * Math.cos(before[2]), before[1] + 8 * Math.sin(before[2]),
                    before[2] + 0.1, 8};
            b = ShieldAim.bullet(before, origin, new double[] {100, 320, 0, 0}, 1, 0.6);
            aim.observe(b, b.bases()[ShieldAim.FROM_ORIGIN] + 0.01 + 1e-9 * random.nextGaussian());
        }
        assertEquals(ShieldAim.FROM_ORIGIN, aim.model() / ShieldAim.OFFSETS % ShieldAim.PROJECTED);
        assertEquals(b.bases()[ShieldAim.FROM_ORIGIN] + 0.01, aim.predict(b), 1e-8);
        assertEquals(1, aim.matchedShare(), 1e-12);
    }

    /** A guess-factor gun one bin off the centre: the offset's sign follows our lateral direction. */
    @Test
    void aimLearnsAnOffsetSignedByOurDirection() {
        ShieldAim aim = new ShieldAim();
        double[] before = {500, 300, 1, 8};
        double[] origin = {508, 300, 1, 8};
        double[] us = {100, 320, 0, 0};
        for (int i = 0; i < 9; i++) {
            double direction = i % 3 == 0 ? -1 : 1;
            ShieldAim.Bullet b = ShieldAim.bullet(before, origin, us, direction, 0.6);
            aim.observe(b, b.bases()[ShieldAim.HEAD_ON] + direction * 0.0264);
        }
        assertEquals(ShieldAim.SIGNED_LAST, aim.model() % ShieldAim.OFFSETS);
        ShieldAim.Bullet left = ShieldAim.bullet(before, origin, us, -1, 0.6);
        ShieldAim.Bullet right = ShieldAim.bullet(before, origin, us, 1, 0.6);
        assertEquals(left.bases()[ShieldAim.HEAD_ON] - 0.0264, aim.predict(left), 1e-12);
        assertEquals(right.bases()[ShieldAim.HEAD_ON] + 0.0264, aim.predict(right), 1e-12);
    }

    /**
     * A gun that leads us by a turn of our velocity, with an offset in units of the escape angle: it tells itself apart
     * from head-on only in the turns we move, and its offset grows with the bullet's escape angle.
     */
    @Test
    void aimLearnsAProjectedTargetAndAGuessFactorOffset() {
        ShieldAim aim = new ShieldAim();
        Random random = new Random(7);
        double[] before = {500, 300, 1, 0};
        double[] origin = {500, 300, 1, 0};
        for (int i = 0; i < 12; i++) {
            double velocity = i % 2 == 0 ? 0 : 0.1 * (random.nextBoolean() ? 1 : -1);
            double[] us = {100, 320 + 0.3 * random.nextDouble(), Math.PI / 2, velocity};
            double escape = 0.4 + 0.5 * random.nextDouble();
            ShieldAim.Bullet b = ShieldAim.bullet(before, origin, us, 1, escape);
            double projected = Angles.bearing(500, 300, us[0], us[1] + velocity);
            // Northwards is clockwise around the enemy in the east; standing counts as clockwise too.
            double direction = velocity < 0 ? 1 : -1;
            aim.observe(b, projected + direction * 0.002 * escape);
        }
        assertEquals(ShieldAim.PROJECTED + ShieldAim.HEAD_ON, aim.model() / ShieldAim.OFFSETS);
        assertEquals(ShieldAim.FACTOR_CURRENT, aim.model() % ShieldAim.OFFSETS);
        ShieldAim.Bullet south = ShieldAim.bullet(before, origin, new double[] {100, 320, Math.PI / 2, -0.1}, 1, 0.5);
        assertEquals(Angles.bearing(500, 300, 100, 319.9) + 0.002 * 0.5, aim.predict(south), 1e-9);
        ShieldAim.Bullet standing = ShieldAim.bullet(before, origin, new double[] {100, 320, Math.PI / 2, 0}, 1, 0.5);
        assertEquals(Angles.bearing(500, 300, 100, 320) - 0.002 * 0.5, aim.predict(standing), 1e-9);
    }

    /** A gun that fires a stray pair now and then: the aim does not follow the pairs. */
    @Test
    void aimDoesNotFollowStrayPairs() {
        ShieldAim aim = new ShieldAim();
        ShieldAim.Bullet b = standingBullet();
        double headOn = b.bases()[ShieldAim.HEAD_ON];
        for (int i = 0; i < 30; i++) {
            aim.observe(b, headOn);
        }
        for (int pair = 1; pair <= 4; pair++) {
            aim.observe(b, headOn + 0.01 * pair);
            aim.observe(b, headOn + 0.01 * pair);
            for (int i = 0; i < 8; i++) {
                aim.observe(b, headOn);
            }
        }
        aim.observe(b, headOn - 0.03);
        aim.observe(b, headOn - 0.03);
        assertEquals(headOn, aim.predict(b), 1e-12);
    }

    private static ShieldAim.Bullet standingBullet() {
        return ShieldAim.bullet(new double[] {500, 300, 1, 0}, new double[] {500, 300, 1, 0},
                new double[] {100, 320, 0, 0}, 1, 0.6);
    }

    @Test
    void marginIsTheDistanceToTheNearerEdgeOfTheSureShadows() {
        java.util.List<double[]> shadows = java.util.List.of(new double[] {0.10, 0.20, 1},
                new double[] {0.20, 0.25, 1}, new double[] {0.05, 0.30, 0.5});
        assertEquals(0.05, ShieldPlanner.margin(shadows, 0.2), 1e-12);
        assertEquals(0.01, ShieldPlanner.margin(shadows, 0.11), 1e-12);
        assertEquals(-0.03, ShieldPlanner.margin(shadows, 0.07), 1e-12);
    }
}
