package cz.libors.ogi.physics;

/**
 * A bullet in flight as the engine keeps it, in the engine's units and angle convention (see {@link EngineRobot}).
 * Its last move is the segment from ({@code lastX}, {@code lastY}) to ({@code x}, {@code y}); hits are tested
 * against that segment.
 */
public final class EngineBullet {

    public double x;
    public double y;
    public double lastX;
    public double lastY;
    public double heading;
    public double power;
}
