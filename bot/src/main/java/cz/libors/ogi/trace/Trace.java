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
     * @param aims     each gun's aim, NaN if it had none
     * @param hitRates each gun's virtual hit rate, NaN before it was scored
     */
    public record GunView(String target, double originX, double originY, double targetX, double targetY,
                          double reference, double escape, int direction, double[] aims, int chosen,
                          String[] gunNames, double[] hitRates, int data) {
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
