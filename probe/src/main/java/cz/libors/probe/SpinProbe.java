package cz.libors.probe;

import robocode.AdvancedRobot;

/**
 * A robot that stops answering: after a few turns its thread computes for {@link #SPIN_SECONDS} without calling the
 * robot API, as a robot stuck in an endless loop does. The engine only interrupts a robot's thread and waits for it,
 * so this thread outlives its battle (engine-model G-48); it ends by itself afterwards, so the JVM that ran the
 * battle gets its core back.
 */
public class SpinProbe extends AdvancedRobot {

    /** Longer than the battle lasts: an opponent kills the robot, and the engine then waits for its thread. */
    public static final int SPIN_SECONDS = 20;

    private static volatile double sink;

    @Override
    public void run() {
        for (int i = 0; i < 3; i++) {
            execute();
        }
        long end = System.nanoTime() + SPIN_SECONDS * 1_000_000_000L;
        double x = 1;
        while (System.nanoTime() < end) {
            for (int i = 0; i < 100_000; i++) {
                x = Math.sin(x) + 1;
            }
            sink = x;
        }
        while (true) {
            execute();
        }
    }
}
