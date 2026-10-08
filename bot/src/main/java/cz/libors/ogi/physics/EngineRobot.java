package cz.libors.ogi.physics;

/**
 * One robot's physical state and pending commands as the engine keeps them between turns.
 *
 * <p>Units and angles are the engine's own (pixels, radians, 0 = north, clockwise), so that {@link RobotMotion} can
 * repeat the engine's arithmetic operation by operation and match it bit for bit. Code outside this package converts
 * to the mathematical convention only through {@code cz.libors.ogi.geom.Angles}.
 *
 * <p>Mutable on purpose: predictions step many candidate states through many turns without allocating.
 */
public final class EngineRobot {

    public double x;
    public double y;
    public double velocity;
    public double bodyHeading;
    public double gunHeading;
    public double radarHeading;
    public double gunHeat;
    public double energy;

    /** Remaining distance and turns; the engine counts them down as the robot moves. */
    public double distanceRemaining;
    public double bodyTurnRemaining;
    public double gunTurnRemaining;
    public double radarTurnRemaining;
    /** Already limited by {@link RobotMotion#limitVelocity} and {@link RobotMotion#limitTurnRate}. */
    public double maxVelocity = RobotMotion.MAX_VELOCITY;
    public double maxTurnRate = RobotMotion.MAX_TURN_RATE;
    public boolean adjustGunForBodyTurn;
    public boolean adjustRadarForGunTurn;
    public boolean adjustRadarForBodyTurn;

    /**
     * Hidden engine state: the robot brakes for the end of its remaining distance. The engine never resets it, not
     * even between rounds (engine-model G-35).
     */
    public boolean overDriving;
    /** Hidden engine state: the robot rammed another one this turn, so it does not turn its body next turn. */
    public boolean inCollision;
}
