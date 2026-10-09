package cz.libors.ogi.trace;

/**
 * Wall time of the robot's own part of each turn: from its first event of the turn ({@code onStatus}, which the
 * engine delivers before any other) to its {@code execute()}. The engine gives a robot the CPU constant of wall time
 * for this and skips its turn when it takes longer, so the bot reports it: the harness compares it with the constant
 * of an idle machine, which a raised constant on a loaded worker would otherwise hide (P-04).
 */
public final class TurnClock {

    /** The debug property that carries the previous turn's time, in microseconds. */
    public static final String PROPERTY = "cpu";

    private long started = -1;
    private long lastMicros = -1;

    /** The turn's first event arrived. */
    public void started(long nanos) {
        started = nanos;
    }

    /** The turn's work is done; returns its time in microseconds, or -1 if its start was not seen. */
    public long ended(long nanos) {
        lastMicros = started < 0 ? -1 : (nanos - started) / 1000;
        started = -1;
        return lastMicros;
    }

    /** The time of the last finished turn, in microseconds, or -1. */
    public long lastMicros() {
        return lastMicros;
    }
}
