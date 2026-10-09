package cz.libors.harness;

import cz.libors.harness.foreign.ApiRules.Level;
import cz.libors.harness.foreign.JarCheck;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.IDebugProperty;
import robocode.control.snapshot.IRobotSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Checks that the battle JVM runs robots in Robocode's sandbox, and that the jar check sees what the sandbox blocks.
 *
 * <ul>
 *   <li>{@code SandboxProbe} tries a socket, file reads and writes outside its data directory, a process, exit,
 *       reflection and threads; the sandbox must block all of them and stop it at 5 extra threads.</li>
 *   <li>The robot can read only the environment the harness passes to the battle JVM.</li>
 *   <li>The jar check passes Ogi's jar and denies the probe, in {@code SandboxProbe} only.</li>
 * </ul>
 *
 * <p>Usage: {@code SandboxCheck <robocodeHome> <probe version> <Ogi jar> <probe jar>}. On Java 24+ it fails by
 * design: Robocode has no sandbox there (engine-model G-43).
 */
public final class SandboxCheck {

    private static final String PROBE = "cz.libors.probe.SandboxProbe";
    private static final List<String> MUST_BE_BLOCKED = List.of(
            "socket", "fileRead", "fileWrite", "robocodeFileWrite", "exec", "exit", "reflection");
    /** The sandbox refuses a new thread once the robot's group has more than 5, its main thread included. */
    private static final String THREADS = "5 started, then blocked SecurityException";
    /** What harness/build.gradle.kts passes to the battle JVM, if set, and what macOS adds to every process. */
    private static final Set<String> ENVIRONMENT = Set.of("PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ",
            "__CF_USER_TEXT_ENCODING");
    private static final long REPORT_TIMEOUT_NANOS = 20_000_000_000L;
    private static final long TURN_WAIT_NANOS = 100_000_000L;

    private record Line(String label, boolean passed, String detail) {
    }

    private SandboxCheck() {
    }

    /** Waits even if the thread was interrupted, and keeps the interrupt. */
    private static void pause(long nanos) {
        long end = System.nanoTime() + nanos;
        boolean interrupted = false;
        while (System.nanoTime() < end) {
            LockSupport.parkNanos(end - System.nanoTime());
            interrupted |= Thread.interrupted();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            System.err.println("Usage: SandboxCheck <robocodeHome> <probe version> <Ogi jar> <probe jar>");
            System.exit(2);
        }
        Path home = Path.of(args[0]);
        String probe = PROBE + " " + args[1];
        List<Line> lines = new ArrayList<>();
        Map<String, String> results = new TreeMap<>();
        try (BattleRunner runner = new BattleRunner(home)) {
            System.out.printf("Java %s, Robocode %s%n", Runtime.version(), runner.robocodeVersion());
            lines.add(new Line("Robocode's Security Manager is installed", Sandbox.active(),
                    Sandbox.manager().orElse("none")));
            // The first blocked attempt kills the probe, but its code runs on, and its next execute() still delivers
            // its debug properties, to the snapshot of the turn after. The battle does not wait for a dead robot, so
            // until the report is there, each turn waits a little for it.
            long deadline = System.nanoTime() + REPORT_TIMEOUT_NANOS;
            runner.run(BattleSetup.duel(probe, "sample.SittingDuck", 1), new BattleAdaptor() {
                @Override
                public void onTurnEnded(TurnEndedEvent event) {
                    for (IRobotSnapshot robot : event.getTurnSnapshot().getRobots()) {
                        IDebugProperty[] properties = robot.getDebugProperties();
                        if (robot.getName().startsWith(PROBE) && properties != null) {
                            for (IDebugProperty p : properties) {
                                results.put(p.getKey(), p.getValue());
                            }
                        }
                    }
                    if (!results.containsKey("sandbox.done") && System.nanoTime() < deadline) {
                        pause(TURN_WAIT_NANOS);
                    }
                }
            });
        }
        for (String attempt : MUST_BE_BLOCKED) {
            String outcome = results.getOrDefault("sandbox." + attempt, "no report");
            lines.add(new Line(attempt + " is blocked", outcome.startsWith("blocked"), outcome));
        }
        String threads = results.getOrDefault("sandbox.threads", "no report");
        lines.add(new Line("extra threads stop at 5", threads.equals(THREADS), threads));
        String env = results.get("sandbox.env");
        List<String> extra = env == null ? List.of("no report") : Arrays.stream(env.split(","))
                .filter(name -> !name.isEmpty() && !ENVIRONMENT.contains(name)).toList();
        lines.add(new Line("the environment is minimal", extra.isEmpty(),
                env == null ? "no report" : extra.isEmpty() ? env : "also " + String.join(",", extra)));

        JarCheck.Report ogi = JarCheck.check(Path.of(args[2]), true);
        lines.add(new Line("the jar check passes Ogi", ogi.verdict() == JarCheck.Verdict.CLEAN,
                ogi.jar() + " " + ogi.verdict()));
        JarCheck.Report probes = JarCheck.check(Path.of(args[3]), true);
        List<JarCheck.Finding> denied = probes.findings().stream().filter(f -> f.level() == Level.DENY).toList();
        boolean onlyProbe = !denied.isEmpty() && denied.stream()
                .allMatch(f -> f.classes().stream().allMatch(c -> c.startsWith(PROBE)));
        lines.add(new Line("the jar check denies SandboxProbe only", onlyProbe,
                denied.size() + " denied APIs, e.g. " + denied.stream().limit(3).map(JarCheck.Finding::api).toList()));

        for (Line line : lines) {
            System.out.printf("%-4s %-42s %s%n", line.passed() ? "OK" : "FAIL", line.label(), line.detail());
        }
        // Robocode's class loader refuses engine classes on every Java version; the probe tries last, if alive.
        results.computeIfPresent("sandbox.internalClass", (k, v) -> {
            System.out.printf("     %-42s %s%n", "(engine classes, class loader)", v);
            return v;
        });
        long failed = lines.stream().filter(l -> !l.passed()).count();
        System.out.printf("%n%d/%d checks passed.%n", lines.size() - failed, lines.size());
        if (!Sandbox.active()) {
            System.out.println("No sandbox on this JVM: foreign robots must not run here (engine-model G-43).");
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
