package cz.libors.harness;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Part of {@code recordProbes}, in a JVM of its own without the engine's debug mode ({@link ProbeRecordings}):
 * {@code SpinProbe} stops calling the robot API and computes for {@value #SPIN_SECONDS} s, and {@code
 * sample.TrackFire} kills it in a few seconds. The engine interrupts the probe's thread, waits for it and gives up, so
 * the thread outlives the battle and keeps computing (engine-model G-48); {@link BattleRunner#robotThreadsAlive} must
 * see it then, and no longer once the probe has stopped by itself.
 *
 * <p>Usage: {@code StrandedThreadCheck <robocodeHome> <probeVersion>}. Prints {@code RESULT\tOK|FAIL\t<detail>}.
 */
public final class StrandedThreadCheck {

    /** How long {@code SpinProbe} computes; the battle must end well before. */
    private static final int SPIN_SECONDS = 20;

    private StrandedThreadCheck() {
    }

    public static void main(String[] args) throws InterruptedException {
        String probe = "cz.libors.probe.SpinProbe " + args[1];
        List<String> robots = List.of(probe, "sample.TrackFire");
        String verdict;
        String detail;
        try (BattleRunner runner = new BattleRunner(Path.of(args[0]))) {
            long start = System.nanoTime();
            runner.run(new BattleSetup("stranded-thread", 800, 600, 1, robots, 42L));
            double battleSeconds = (System.nanoTime() - start) / 1e9;
            List<String> after = runner.robotThreadsAlive();
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            long cpuBefore = cpuNanos(mx, probe);
            Thread.sleep(500);
            double busy = (cpuNanos(mx, probe) - cpuBefore) / 5e8;
            long deadline = start + (SPIN_SECONDS + 10) * 1_000_000_000L;
            List<String> later = runner.robotThreadsAlive();
            while (!later.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(200);
                later = runner.robotThreadsAlive();
            }
            boolean passed = battleSeconds < SPIN_SECONDS - 5 && after.contains(probe) && busy > 0.5
                    && later.isEmpty();
            verdict = passed ? "OK" : "FAIL";
            detail = String.format(Locale.ROOT, "battle %.1f s; alive after it %s; busy %.0f %% of a core; alive at "
                    + "the end %s", battleSeconds, after, 100 * busy, later);
        }
        System.out.println("RESULT\t" + verdict + "\t" + detail);
        // The engine leaves non-daemon threads behind.
        System.exit(0);
    }

    /** CPU time of the live threads with this name. */
    private static long cpuNanos(ThreadMXBean mx, String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().equals(name))
                .mapToLong(t -> Math.max(0, mx.getThreadCpuTime(t.getId())))
                .sum();
    }
}
