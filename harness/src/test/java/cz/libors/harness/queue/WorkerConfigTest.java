package cz.libors.harness.queue;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkerConfigTest {

    @Test
    void queueProfileKeepsTheLoadStudyOptions() {
        WorkerConfig w = WorkerConfig.parse("ogi-win:12", Path.of("state"), Path.of("bundle"), "java", false,
                WorkerConfig.baseJvm("queue"), List.of("-Xss2m"), 1.5);
        assertEquals(List.of("-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss2m", "-Djava.security.manager=allow"),
                w.jvm());
        assertEquals(12, w.slots());
    }

    @Test
    void rumbleProfileHasTheClientsOptionsOnly() {
        WorkerConfig w = WorkerConfig.parse("local:1", Path.of("state"), Path.of("bundle"), "java", true,
                WorkerConfig.baseJvm("rumble"), List.of(), 1);
        assertEquals(List.of("-Xmx512M", "-Djava.security.manager=allow"), w.jvm());
        assertEquals(1.0, w.cpuFactor());
    }

    @Test
    void unknownProfileIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> WorkerConfig.baseJvm("fast"));
    }
}
