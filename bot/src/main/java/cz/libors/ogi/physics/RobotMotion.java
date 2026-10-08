package cz.libors.ogi.physics;

/**
 * The engine's rules for one robot in one turn, repeated from {@code RobotPeer} of Robocode 1.11.1 operation by
 * operation so that the results match the engine bit for bit (see docs/engine-model.md, tick order steps 2 and 4).
 *
 * <p>Robot collisions need the other robots and the engine's processing order, so this class only applies their
 * outcome ({@link #ram}). Angles are the engine's: radians, 0 = north, clockwise.
 */
public final class RobotMotion {

    public static final double ACCELERATION = 1;
    public static final double DECELERATION = 2;
    public static final double MAX_VELOCITY = 8;
    public static final double MAX_TURN_RATE = Math.toRadians(10);
    public static final double GUN_TURN_RATE = Math.toRadians(20);
    public static final double RADAR_TURN_RATE = Math.toRadians(45);
    public static final double MIN_BULLET_POWER = 0.1;
    public static final double MAX_BULLET_POWER = 3;
    public static final double ROBOT_HIT_DAMAGE = 0.6;
    /** Half of the 36 px bounding box; the engine keeps it as an int. */
    public static final int HALF_SIZE = 18;

    // Constants and tolerance of robocode.util.Utils.
    private static final double TWO_PI = 2 * Math.PI;
    private static final double PI_OVER_TWO = Math.PI / 2;
    private static final double THREE_PI_OVER_TWO = 3 * Math.PI / 2;
    private static final double NEAR_DELTA = .00001;

    private RobotMotion() {
    }

    /** The limit the engine applies to a max velocity a robot sends. */
    public static double limitVelocity(double maxVelocity) {
        return Math.min(Math.abs(maxVelocity), MAX_VELOCITY);
    }

    /** The limit the engine applies to a max turn rate (radians) a robot sends. */
    public static double limitTurnRate(double maxTurnRate) {
        return Math.min(Math.abs(maxTurnRate), MAX_TURN_RATE);
    }

    /**
     * Fires a bullet when commands load ({@code RobotPeer.fireBullets}): only with a cool gun and energy left, before
     * the gun cools or turns this turn. The bullet leaves from the robot's position along its gun heading.
     *
     * @return the bullet's power, or 0 if the engine refused to fire
     */
    public static double fire(EngineRobot r, double power) {
        if (Double.isNaN(power) || r.gunHeat > 0 || r.energy == 0) {
            return 0;
        }
        double firePower = Math.min(r.energy, Math.min(Math.max(power, MIN_BULLET_POWER), MAX_BULLET_POWER));
        setEnergy(r, r.energy - firePower);
        r.gunHeat += 1 + (firePower / 5);
        return firePower;
    }

    /**
     * One turn of {@code RobotPeer.performMove} up to and including wall collisions: the gun cools, the body, gun and
     * radar turn, the robot moves and is stopped by walls. Clears {@link EngineRobot#inCollision}, as the engine's
     * robot collision check does; call {@link #ram} afterwards if the robot rammed another one.
     *
     * @return whether the robot hit a wall
     */
    public static boolean move(EngineRobot r, int fieldWidth, int fieldHeight, double gunCoolingRate) {
        r.gunHeat -= gunCoolingRate;
        if (r.gunHeat < 0) {
            r.gunHeat = 0;
        }
        if (!r.inCollision) {
            turnBody(r);
        }
        turnGun(r);
        turnRadar(r);
        moveBody(r);
        boolean hitWall = hitWall(r, fieldWidth, fieldHeight);
        r.inCollision = false;
        return hitWall;
    }

    /**
     * Outcome of ramming another robot ({@code RobotPeer.checkRobotCollision}, the robot at fault): it is pushed
     * back by this turn's movement, stops, loses the ram damage and does not turn its body next turn.
     */
    public static void ram(EngineRobot r) {
        double movedX = r.velocity * Math.sin(r.bodyHeading);
        double movedY = r.velocity * Math.cos(r.bodyHeading);
        r.inCollision = true;
        r.velocity = 0;
        r.distanceRemaining = 0;
        r.x -= movedX;
        r.y -= movedY;
        setEnergy(r, r.energy - ROBOT_HIT_DAMAGE);
    }

    /** {@code RobotPeer.updateHeading}: the turn rate depends on the velocity before this turn's acceleration. */
    private static void turnBody(EngineRobot r) {
        double turnRate = Math.min(r.maxTurnRate,
                (.4 + .6 * (1 - (Math.abs(r.velocity) / MAX_VELOCITY))) * MAX_TURN_RATE);
        double remaining = r.bodyTurnRemaining;
        if (remaining == 0 || Double.isNaN(remaining)) {
            return;
        }
        boolean partial = remaining > 0 ? remaining < turnRate : remaining > -turnRate;
        double turn = partial ? remaining : remaining > 0 ? turnRate : -turnRate;
        r.bodyHeading += turn;
        r.gunHeading += turn;
        r.radarHeading += turn;
        if (r.adjustGunForBodyTurn) {
            r.gunTurnRemaining -= turn;
        }
        if (r.adjustRadarForBodyTurn) {
            r.radarTurnRemaining -= turn;
        }
        r.bodyTurnRemaining = partial ? 0 : remaining - turn;
        // A finished turn snaps to the nearest quarter if within NEAR_DELTA.
        r.bodyHeading = r.bodyTurnRemaining == 0 ? normalNearAbsolute(r.bodyHeading) : normalAbsolute(r.bodyHeading);
    }

    /** {@code RobotPeer.updateGunHeading}: the radar turns with the gun. */
    private static void turnGun(EngineRobot r) {
        double remaining = r.gunTurnRemaining;
        if (remaining > 0 || remaining < 0) {
            boolean partial = remaining > 0 ? remaining < GUN_TURN_RATE : remaining > -GUN_TURN_RATE;
            double turn = partial ? remaining : remaining > 0 ? GUN_TURN_RATE : -GUN_TURN_RATE;
            r.gunHeading += turn;
            r.radarHeading += turn;
            if (r.adjustRadarForGunTurn) {
                r.radarTurnRemaining -= turn;
            }
            r.gunTurnRemaining = partial ? 0 : remaining - turn;
        }
        r.gunHeading = normalAbsolute(r.gunHeading);
    }

    /** {@code RobotPeer.updateRadarHeading}. */
    private static void turnRadar(EngineRobot r) {
        double remaining = r.radarTurnRemaining;
        if (remaining > 0 || remaining < 0) {
            boolean partial = remaining > 0 ? remaining < RADAR_TURN_RATE : remaining > -RADAR_TURN_RATE;
            double turn = partial ? remaining : remaining > 0 ? RADAR_TURN_RATE : -RADAR_TURN_RATE;
            r.radarHeading += turn;
            r.radarTurnRemaining = partial ? 0 : remaining - turn;
        }
        r.radarHeading = normalAbsolute(r.radarHeading);
    }

    /** {@code RobotPeer.updateMovement}. */
    private static void moveBody(EngineRobot r) {
        double distance = r.distanceRemaining;
        if (Double.isNaN(distance)) {
            distance = 0;
        }
        r.velocity = newVelocity(r.velocity, distance, r.maxVelocity);
        // Braking for the end of the distance reached zero: the rest of the distance is dropped.
        if (isNear(r.velocity, 0) && r.overDriving) {
            distance = 0;
            r.overDriving = false;
        }
        if (Math.signum(distance * r.velocity) != -1) {
            r.overDriving = distanceUntilStop(r.velocity, r.maxVelocity) > Math.abs(distance);
        }
        r.distanceRemaining = distance - r.velocity;
        if (r.velocity != 0) {
            r.x += r.velocity * Math.sin(r.bodyHeading);
            r.y += r.velocity * Math.cos(r.bodyHeading);
        }
    }

    /** {@code RobotPeer.getNewVelocity}: accelerate by 1, brake by 2, and through zero by the remaining brake. */
    static double newVelocity(double velocity, double distance, double maxVelocity) {
        if (distance < 0) {
            return -newVelocity(-velocity, -distance, maxVelocity);
        }
        double goalVelocity = distance == Double.POSITIVE_INFINITY
                ? maxVelocity
                : Math.min(maxVelocityFor(distance), maxVelocity);
        if (velocity >= 0) {
            return Math.max(velocity - DECELERATION, Math.min(goalVelocity, velocity + ACCELERATION));
        }
        return Math.max(velocity - ACCELERATION, Math.min(goalVelocity, velocity + maxDeceleration(-velocity)));
    }

    /** {@code RobotPeer.getMaxVelocity}: the highest velocity from which the robot can still stop within distance. */
    static double maxVelocityFor(double distance) {
        double decelTime = Math.max(1, Math.ceil((Math.sqrt((4 * 2 / DECELERATION) * distance + 1) - 1) / 2));
        if (decelTime == Double.POSITIVE_INFINITY) {
            return MAX_VELOCITY;
        }
        double decelDistance = (decelTime / 2.0) * (decelTime - 1) * DECELERATION;
        return ((decelTime - 1) * DECELERATION) + ((distance - decelDistance) / decelTime);
    }

    /** {@code RobotPeer.maxDecel}: braking that crosses zero spends the rest of the turn accelerating. */
    private static double maxDeceleration(double speed) {
        double decelTime = speed / DECELERATION;
        double accelTime = (1 - decelTime);
        return Math.min(1, decelTime) * DECELERATION + Math.max(0, accelTime) * ACCELERATION;
    }

    /** {@code RobotPeer.getDistanceTraveledUntilStop}. */
    private static double distanceUntilStop(double velocity, double maxVelocity) {
        double distance = 0;
        double v = Math.abs(velocity);
        while (v > 0) {
            v = newVelocity(v, 0, maxVelocity);
            distance += v;
        }
        return distance;
    }

    /** {@code RobotPeer.checkWallCollision}: the robot is pushed back along its heading and stops. */
    private static boolean hitWall(EngineRobot r, int fieldWidth, int fieldHeight) {
        int minX = HALF_SIZE;
        int minY = HALF_SIZE;
        int maxX = fieldWidth - HALF_SIZE;
        int maxY = fieldHeight - HALF_SIZE;
        double adjustX = 0;
        double adjustY = 0;
        boolean hit = false;
        if (r.x < minX) {
            hit = true;
            adjustX = minX - r.x;
        } else if (r.x > maxX) {
            hit = true;
            adjustX = maxX - r.x;
        }
        if (r.y < minY) {
            hit = true;
            adjustY = minY - r.y;
        } else if (r.y > maxY) {
            hit = true;
            adjustY = maxY - r.y;
        }
        if (!hit) {
            return false;
        }
        // Both coordinates are fixed only when the robot hits at an angle.
        if ((r.bodyHeading % (Math.PI / 2)) != 0) {
            double tanHeading = Math.tan(r.bodyHeading);
            if (adjustX == 0) {
                adjustX = adjustY * tanHeading;
            } else if (adjustY == 0) {
                adjustY = adjustX / tanHeading;
            } else if (Math.abs(adjustX / tanHeading) > Math.abs(adjustY)) {
                adjustY = adjustX / tanHeading;
            } else if (Math.abs(adjustY * tanHeading) > Math.abs(adjustX)) {
                adjustX = adjustY * tanHeading;
            }
        }
        r.x += adjustX;
        r.y += adjustY;
        if (r.x < minX) {
            r.x = minX;
        } else if (r.x > maxX) {
            r.x = maxX;
        }
        if (r.y < minY) {
            r.y = minY;
        } else if (r.y > maxY) {
            r.y = maxY;
        }
        setEnergy(r, r.energy - wallHitDamage(r.velocity));
        r.distanceRemaining = 0;
        r.velocity = 0;
        return true;
    }

    /** {@code Rules.getWallHitDamage}, for an AdvancedRobot. */
    public static double wallHitDamage(double velocity) {
        return Math.max(Math.abs(velocity) / 2 - 1, 0);
    }

    /** {@code RobotPeer.setEnergy}: below 0.01 the robot is disabled and its distance and body turn are dropped. */
    private static void setEnergy(EngineRobot r, double energy) {
        r.energy = energy;
        if (r.energy < .01) {
            r.energy = 0;
            r.distanceRemaining = 0;
            r.bodyTurnRemaining = 0;
        }
    }

    private static double normalAbsolute(double angle) {
        return (angle %= TWO_PI) >= 0 ? angle : (angle + TWO_PI);
    }

    private static double normalNearAbsolute(double angle) {
        angle = (angle %= TWO_PI) >= 0 ? angle : (angle + TWO_PI);
        if (isNear(angle, Math.PI)) {
            return Math.PI;
        } else if (angle < Math.PI) {
            if (isNear(angle, 0)) {
                return 0;
            } else if (isNear(angle, PI_OVER_TWO)) {
                return PI_OVER_TWO;
            }
        } else {
            if (isNear(angle, THREE_PI_OVER_TWO)) {
                return THREE_PI_OVER_TWO;
            } else if (isNear(angle, TWO_PI)) {
                return 0;
            }
        }
        return angle;
    }

    private static boolean isNear(double a, double b) {
        return Math.abs(a - b) < NEAR_DELTA;
    }
}
