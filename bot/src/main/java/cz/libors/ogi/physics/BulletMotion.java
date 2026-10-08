package cz.libors.ogi.physics;

/**
 * The engine's rules for bullets, repeated from {@code BulletPeer} of Robocode 1.11.1 operation by operation (see
 * docs/engine-model.md, tick order step 3). A bullet first moves, then is tested against the walls, then against the
 * robots' boxes from the end of the previous turn, then against other bullets' segments.
 */
public final class BulletMotion {

    /** The bullet counts as having hit a wall within this distance of the edge. */
    public static final int RADIUS = 3;

    private BulletMotion() {
    }

    /** {@code Rules.getBulletSpeed}. */
    public static double speed(double power) {
        double p = Math.min(Math.max(power, RobotMotion.MIN_BULLET_POWER), RobotMotion.MAX_BULLET_POWER);
        return 20 - 3 * p;
    }

    /**
     * A bullet just fired ({@code RobotPeer.fireBullets}): it starts at the robot's center along the gun heading the
     * robot had before this turn. It moves in the same turn.
     */
    public static EngineBullet fired(EngineRobot shooter, double power) {
        EngineBullet b = new EngineBullet();
        b.x = b.lastX = shooter.x;
        b.y = b.lastY = shooter.y;
        b.heading = shooter.gunHeading;
        b.power = power;
        return b;
    }

    /** {@code BulletPeer.updateMovement}. */
    public static void move(EngineBullet b) {
        b.lastX = b.x;
        b.lastY = b.y;
        double v = speed(b.power);
        b.x += v * Math.sin(b.heading);
        b.y += v * Math.cos(b.heading);
    }

    /** {@code BulletPeer.checkWallCollision}, after the move. */
    public static boolean hitsWall(EngineBullet b, int fieldWidth, int fieldHeight) {
        return b.x - RADIUS <= 0 || b.y - RADIUS <= 0 || b.x + RADIUS >= fieldWidth || b.y + RADIUS >= fieldHeight;
    }

    /** {@code BulletPeer.checkRobotCollision}: whether the last move's segment touches the robot's box. */
    public static boolean hits(EngineBullet b, RobotBox box) {
        return box.intersectsLine(b.lastX, b.lastY, b.x, b.y);
    }

    /**
     * Where a bullet that hit a robot ends up: at the start of its last move if that lies inside the box, otherwise
     * at its end. Returns {x, y}.
     */
    public static double[] impact(EngineBullet b, RobotBox box) {
        return box.contains(b.lastX, b.lastY) ? new double[] {b.lastX, b.lastY} : new double[] {b.x, b.y};
    }

    /**
     * {@code BulletPeer.intersect}: whether the last-move segments of two bullets cross. The engine updates bullets
     * in random order, so {@code other}'s segment may still be the one from the previous turn (engine-model G-04).
     */
    public static boolean cross(EngineBullet b, EngineBullet other) {
        double x1 = other.lastX;
        double x2 = other.x;
        double x3 = b.lastX;
        double x4 = b.x;
        double y1 = other.lastY;
        double y2 = other.y;
        double y3 = b.lastY;
        double y4 = b.y;
        double dx13 = (x1 - x3);
        double dx21 = (x2 - x1);
        double dx43 = (x4 - x3);
        double dy13 = (y1 - y3);
        double dy21 = (y2 - y1);
        double dy43 = (y4 - y3);
        double dn = dy43 * dx21 - dx43 * dy21;
        double ua = (dx43 * dy13 - dy43 * dx13) / dn;
        double ub = (dx21 * dy13 - dy21 * dx13) / dn;
        return (ua >= 0 && ua <= 1) && (ub >= 0 && ub <= 1);
    }

    /** {@code Rules.getBulletHitBonus}: energy the shooter regains when its bullet hits a robot. */
    public static double hitBonus(double power) {
        return 3 * power;
    }

    /** {@code Rules.getBulletDamage}. */
    public static double damage(double power) {
        double damage = 4 * power;
        if (power > 1) {
            damage += 2 * (power - 1);
        }
        return damage;
    }
}
