package cz.libors.harness.queue;

import cz.libors.harness.BattleOutcome;
import cz.libors.harness.ContestantResult;
import cz.libors.harness.queue.Protocol.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {

    @Test
    void messagesSurviveTheLine() {
        Message m = Message.of("failed", "id", 42L, "reason", "Proces nemá přístup k souboru", "slot", 3);
        String line = m.line();
        assertTrue(line.startsWith("@ogi "));
        assertTrue(line.chars().allMatch(c -> c < 0x7f), line);
        Message back = Protocol.parse(line).orElseThrow();
        assertEquals("failed", back.type());
        assertEquals(42, back.longValue("id"));
        assertEquals(3, back.intValue("slot"));
        assertEquals("Proces nemá přístup k souboru", back.string("reason"));
        assertEquals(Optional.empty(), Protocol.parse("Unable to stop thread"));
    }

    @Test
    void aBattleAndItsOutcomeSurviveTheCodec() {
        BattleSpec spec = new BattleSpec("1v1 vs sample.Walls", 800, 600, 35,
                List.of("cz.libors.Ogi w1234567", "sample.Walls"), Long.MIN_VALUE + 1, -7L);
        BattleSpec back = BattleSpec.fromMap(Message.of("battle", "spec", spec.toMap()).line().transform(
                l -> Protocol.parse(l).orElseThrow().map("spec")));
        assertEquals(spec, back);

        BattleOutcome outcome = new BattleOutcome(spec.setup(), spec.seed(),
                List.of(new ContestantResult("cz.libors.Ogi w1234567", 1, 4000, 1750, 350, 1500, 300, 80, 20, 35),
                        new ContestantResult("sample.Walls", 2, 100, 0, 0, 90, 0, 10, 0, 0)),
                Map.of("cz.libors.Ogi w1234567", 2), List.of("sample.Walls [round 3, turn 9]: Exception x"), 1,
                Map.of("cz.libors.Ogi w1234567", new BattleOutcome.Shots(300, 120),
                        "sample.Walls", new BattleOutcome.Shots(400, 30)), 2345,
                Map.of("cz.libors.Ogi w1234567", new BattleOutcome.TurnTimes(1000, 2500, 3, 1)));
        Map<String, Object> wire = Protocol.parse(Message.of("result", "outcome",
                BattleSpec.outcomeToMap(outcome)).line()).orElseThrow().map("outcome");
        assertEquals(outcome, spec.outcomeFromMap(wire));
    }

    @Test
    void powerShellArgumentsAreLiteral() {
        assertEquals("'it''s'", Ssh.quote("it's"));
        assertEquals("& 'C:/ogi/jdk/bin/java.exe' '-cp' 'a b;c' 'jvm=-XX:+UseSerialGC' '$HOME'",
                Ssh.command("C:/ogi/jdk/bin/java.exe", List.of("-cp", "a b;c", "jvm=-XX:+UseSerialGC", "$HOME")));
    }
}
