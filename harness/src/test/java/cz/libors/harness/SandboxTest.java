package cz.libors.harness;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxTest {

    private static final List<String> TRUSTED = List.of("cz.libors.Ogi 0.1", "cz.libors.probe.RandomProbe 0.1",
            "sample.Walls", "sampleteam.MyFirstTeam", "sampleex.Alien", "samplesentry.BorderGuard");

    @Test
    void ourRobotsAndTheSampleRobotsAreTrusted() {
        assertEquals(List.of(), Sandbox.foreign(TRUSTED));
        assertEquals(List.of("kc.mega.BeepBoop 2.0", "samples.Fake 1.0"),
                Sandbox.foreign(List.of("sample.Walls", "kc.mega.BeepBoop 2.0", "samples.Fake 1.0")));
    }

    @Test
    void foreignRobotsNeedTheSandbox() {
        assertDoesNotThrow(() -> Sandbox.requireFor(TRUSTED, false));
        assertDoesNotThrow(() -> Sandbox.requireFor(List.of("kc.mega.BeepBoop 2.0"), true));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Sandbox.requireFor(List.of("sample.Walls", "kc.mega.BeepBoop 2.0"), false));
        assertTrue(e.getMessage().contains("kc.mega.BeepBoop 2.0"), e.getMessage());
    }
}
