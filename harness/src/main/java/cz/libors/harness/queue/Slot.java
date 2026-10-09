package cz.libors.harness.queue;

import cz.libors.harness.BattleOutcome;
import cz.libors.harness.BattleRunner;
import cz.libors.harness.BattleSetup;
import cz.libors.harness.Sandbox;
import cz.libors.harness.Starts;
import cz.libors.harness.queue.Protocol.Channel;
import cz.libors.harness.queue.Protocol.Message;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * One battle at a time, in a JVM of its own with a Robocode home of its own; the executor starts one per slot.
 *
 * <p>Reads {@code battle} messages ({@link BattleSpec}) from standard input and answers each with {@code result} (the
 * outcome) or {@code error}. {@code measure} runs one short battle in a home without a CPU constant, so that the engine
 * measures it, and answers {@code measured}. Ends on {@code quit} or at the end of input. Everything else the
 * process prints goes to standard error.
 *
 * <p>Usage: {@code Slot <robocodeHome>}.
 */
public final class Slot {

    private Slot() {
    }

    public static void main(String[] args) throws IOException {
        Channel out = Protocol.takeStandardOutput();
        Path home = Path.of(args[0]);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII));
        try (BattleRunner runner = new BattleRunner(home)) {
            out.send(Message.of("ready", "robocode", runner.robocodeVersion(),
                    "java", System.getProperty("java.vm.vendor") + " " + System.getProperty("java.version"),
                    "os", System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                    "sandbox", Sandbox.active(),
                    "cpuConstant", BattleRunner.cpuConstant(home).orElse(0),
                    "robots", runner.repository()));
            String line;
            while ((line = in.readLine()) != null) {
                Optional<Message> parsed = Protocol.parse(line);
                if (parsed.isEmpty()) {
                    continue;
                }
                Message m = parsed.get();
                switch (m.type()) {
                    case "battle" -> out.send(battle(runner, m));
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
        } finally {
            // The engine leaves non-daemon threads behind.
            System.exit(0);
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
            BattleOutcome outcome = runner.run(setup);
            return Message.of("result", "id", id, "outcome", BattleSpec.outcomeToMap(outcome));
        } catch (RuntimeException e) {
            e.printStackTrace();
            return Message.of("error", "id", id, "message", e.toString());
        }
    }
}
