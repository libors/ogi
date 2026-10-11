package cz.libors.harness.queue;

import cz.libors.harness.BattleOutcome;
import cz.libors.harness.BattleRunner;
import cz.libors.harness.BattleSetup;
import cz.libors.harness.Sandbox;
import cz.libors.harness.Starts;
import cz.libors.harness.queue.Protocol.Channel;
import cz.libors.harness.queue.Protocol.Message;
import cz.libors.harness.recording.TurnRecorder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One battle at a time, in a JVM of its own with a Robocode home of its own; the executor starts one per slot.
 *
 * <p>Reads {@code battle} messages ({@link BattleSpec}) from standard input and answers each with {@code result} (the
 * outcome, with the recording in Base64 if the spec asks for one) or {@code error}. {@code measure} runs one short
 * battle in a home without a CPU constant, so that the engine measures it, and answers {@code measured}. Ends on
 * {@code quit} or at the end of input (exit code 0). After a battle whose robots left threads running
 * ({@link #STOP_GRACE_MILLIS}) it ends with exit code 4, and with 3 when that battle's robot data cannot be cleared
 * ({@link BattleRunner.RobotDataNotCleared}); it says {@code leaving} before that battle's result, and the executor
 * restarts the slot. A slot that fails ends with exit code 1 and its stack trace. Everything else the process prints
 * goes to standard error.
 *
 * <p>Usage: {@code Slot <robocodeHome>}.
 */
public final class Slot {

    private Slot() {
    }

    public static void main(String[] args) {
        int exit = 0;
        try {
            serve(args);
        } catch (Throwable t) {
            t.printStackTrace();
            exit = 1;
        } finally {
            // The engine leaves non-daemon threads behind.
            System.exit(exit);
        }
    }

    private static void serve(String[] args) throws IOException {
        Channel out = Protocol.takeStandardOutput();
        Path home = Path.of(args[0]);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII));
        try (BattleRunner runner = new BattleRunner(home)) {
            out.send(Message.of("ready", "robocode", runner.robocodeVersion(),
                    "java", System.getProperty("java.vm.vendor") + " " + System.getProperty("java.version"),
                    "os", System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                    "sandbox", Sandbox.active(),
                    "cpuConstant", BattleRunner.cpuConstant(home).orElse(0),
                    "robots", runner.repository(), "robotJars", runner.repositoryJars()));
            String line;
            while ((line = in.readLine()) != null) {
                Optional<Message> parsed = Protocol.parse(line);
                if (parsed.isEmpty()) {
                    continue;
                }
                Message m = parsed.get();
                switch (m.type()) {
                    case "battle" -> {
                        Message result = battle(runner, m);
                        List<String> alive = threadsLeft(runner);
                        int exit = alive.isEmpty() ? (dataCleared(runner) ? 0 : 3) : 4;
                        if (exit == 0) {
                            out.send(result);
                        } else {
                            // "leaving" first, so that the executor gives this slot no other battle.
                            String reason = exit == 4 ? "robot threads still running after the battle: " + alive
                                    : "the robots' data of the battle cannot be cleared";
                            System.err.println(reason);
                            out.send(Message.of("leaving", "reason", reason));
                            out.send(result);
                            System.exit(exit);
                        }
                    }
                    case "measure" -> {
                        runner.run(new BattleSetup("cpu constant", 800, 600, 1,
                                List.of("sample.TrackFire", "sample.SittingDuck"), 1L));
                        out.send(Message.of("measured", "cpuConstant", BattleRunner.cpuConstant(home).orElse(0)));
                    }
                    case "quit" -> {
                        return;
                    }
                    default -> System.err.println("slot: unknown message " + m.type());
                }
            }
        }
    }

    /**
     * How long a robot's thread may outlive its battle. The engine gives up on a thread that ignores its interrupt
     * and leaves it running (engine-model G-48); one that loops never ends and would take a core from every later
     * battle on the machine, so the slot ends and a new JVM takes its place.
     */
    static final long STOP_GRACE_MILLIS = 3000;

    /** The last battle's robot threads still alive after {@link #STOP_GRACE_MILLIS}; empty at once if none are. */
    private static List<String> threadsLeft(BattleRunner runner) {
        long deadline = System.currentTimeMillis() + STOP_GRACE_MILLIS;
        List<String> alive = runner.robotThreadsAlive();
        while (!alive.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            alive = runner.robotThreadsAlive();
        }
        return alive;
    }

    /**
     * Clears the robots' data right after the battle, while its result is still here: a file a robot left open then
     * restarts this slot (exit code 3) without costing the next battle anything.
     */
    private static boolean dataCleared(BattleRunner runner) {
        try {
            runner.clearRobotData();
            return true;
        } catch (BattleRunner.RobotDataNotCleared e) {
            e.printStackTrace();
            return false;
        }
    }

    private static Message battle(BattleRunner runner, Message m) {
        long id = m.longValue("id");
        try {
            BattleSpec spec = BattleSpec.fromMap(m.map("spec"));
            BattleSetup setup = spec.setup();
            if (spec.startsSeed() != null) {
                setup = setup.withStarts(Starts.random(spec.startsSeed(), runner.robotCount(spec.robots()),
                        spec.width(), spec.height()));
            }
            if (!spec.record()) {
                return Message.of("result", "id", id, "outcome", BattleSpec.outcomeToMap(runner.run(setup)));
            }
            // A recording goes back inside the result, so the worker keeps nothing.
            Path file = Files.createTempFile("battle", ".rec.gz");
            try {
                BattleOutcome outcome;
                try (TurnRecorder recorder = TurnRecorder.open(file, setup,
                        spec.recordKeys() == null ? null : Set.copyOf(spec.recordKeys()))) {
                    outcome = runner.run(setup, recorder);
                }
                Map<String, Object> result = BattleSpec.outcomeToMap(outcome);
                result.put("recording", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
                return Message.of("result", "id", id, "outcome", result);
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (BattleRunner.RobotDataNotCleared e) {
            // Rare since the data is cleared after every battle too: a new JVM in a fresh home frees the file. The
            // executor restarts the slot and reports this battle failed, so its job tries it again.
            e.printStackTrace();
            System.exit(3);
            throw e;
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
            return Message.of("error", "id", id, "message", e.toString());
        }
    }
}
