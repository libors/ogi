package cz.libors.ogi.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * What the bot decided in the current turn and the state behind it, as its consumers see it: the overlay now, later
 * the harness and a replay viewer. Components describe themselves into it; consumers only read it. Geometry is
 * mathematical.
 */
public final class Trace {

    /**
     * A wave in flight. Offsets are relative to {@code reference}; NaN while no heading has hit.
     *
     * @param real  whether a bullet was fired with it (always for an enemy's)
     * @param enemy whether an enemy fired it; the hit range is then the one on us
     */
    public record WaveView(double x, double y, double radius, boolean real, boolean enemy, double reference,
                           double minOffset, double maxOffset) {
    }

    /**
     * The gun's plan for the next turn.
     *
     * @param time     the turn the plan was made in; it is the shot of the turn after
     * @param aims     each gun's aim, NaN if it had none
     * @param hitRates each gun's virtual hit rate, NaN before it was scored
     */
    public record GunView(String target, long time, double originX, double originY, double targetX, double targetY,
                          double reference, double escape, double power, int direction, double[] aims, int chosen,
                          String[] gunNames, double[] hitRates, int data) {
    }

    /**
     * A gun plan as one line for the harness, which reads it from the debug property {@code gun} in a recording:
     * the plan's turn, whether last turn's plan was fired in this turn (1 or 0), the chosen gun's index, each gun's
     * aim, the origin, the reference, the direction, the power, the data size, each gun's virtual hit rate and the
     * target's name. Comma-separated; angles are mathematical, in radians; numbers as {@link Double#toString}.
     */
    public static String gunLine(GunView g, boolean fired) {
        StringBuilder b = new StringBuilder(256);
        b.append(g.time()).append(',').append(fired ? 1 : 0).append(',').append(g.chosen());
        for (double aim : g.aims()) {
            b.append(',').append(aim);
        }
        b.append(',').append(g.originX()).append(',').append(g.originY()).append(',').append(g.reference())
                .append(',').append(g.direction()).append(',').append(g.power()).append(',').append(g.data());
        for (double rate : g.hitRates()) {
            b.append(',').append(rate);
        }
        return b.append(',').append(g.target()).toString();
    }

    private long time;
    private double fieldHeight;
    private final List<WaveView> waves = new ArrayList<>();
    private final List<GunView> guns = new ArrayList<>();

    /** Clears the trace for a new turn. */
    public void start(long turn, double height) {
        time = turn;
        fieldHeight = height;
        waves.clear();
        guns.clear();
    }

    public long time() {
        return time;
    }

    public double fieldHeight() {
        return fieldHeight;
    }

    public void add(WaveView wave) {
        waves.add(wave);
    }

    public void add(GunView gun) {
        guns.add(gun);
    }

    public List<WaveView> waves() {
        return waves;
    }

    public List<GunView> guns() {
        return guns;
    }
}
