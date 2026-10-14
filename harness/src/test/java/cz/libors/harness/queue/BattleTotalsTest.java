package cz.libors.harness.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BattleTotalsTest {

    private static final String HEADER = "finished\tworker\tslot\tbattle\twaitedSeconds\tbattleSeconds\toutcome\n";

    private static Path job(Path jobs, String id, String type, String format, String dispatch) throws IOException {
        Path dir = Files.createDirectories(jobs.resolve(id));
        Files.writeString(dir.resolve("job.properties"), "type=" + type + "\n");
        Files.writeString(dir.resolve("opponents.txt"), "title Some bots\nformat " + format + "\nsample.Walls\n");
        if (dispatch != null) {
            Files.writeString(dir.resolve("dispatch.tsv"), HEADER + dispatch);
        }
        return dir;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> format(Map<String, Object> totals, String name) {
        return ((List<Map<String, Object>>) totals.get("formats")).stream().filter(f -> f.get("format").equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void countsOnlyBattlesPlayedToTheirEndPerFormatKindAndWorker(@TempDir Path jobs) throws IOException {
        job(jobs, "a", "compare", "1v1", """
                2026-10-10 10:00:00\tw\t0\tx.Bot 1.0 0 BASELINE\t0.0\t20.0\tok; own turns max 1.00 ms, 0 over half the idle constant, 0 over it
                2026-10-10 10:00:01\tw\t1\tx.Bot 1.0 0 CANDIDATE\t0.0\t\tfailed: timeout
                2026-10-09 09:00:00\tw\t2\tx.Bot 1.0 1 CANDIDATE\t0.0\t16.0\tok
                2026-10-10 10:00:02\tw\t3\tx.Bot 1.0 1 BASELINE\t0.0\t\tlost: the worker went away
                """);
        job(jobs, "b", "record", "1v1", "2026-10-11 08:00:00\tv\t0\tx.Bot 1.0 0 CANDIDATE\t0.0\t3600.0\tok\n");
        job(jobs, "c", "compare", "team", "2026-10-12 08:00:00\tw\t0\tx.Team 1.0 0 CANDIDATE\t0.0\t7200.0\tok\n");
        job(jobs, "d", "compare", "1v1", null);

        BattleTotals totals = BattleTotals.read(jobs);
        Map<String, Object> t = totals.toMap();

        assertEquals(4L, t.get("battles"));
        assertEquals((20 + 16 + 3600 + 7200) / 3600.0, (double) t.get("slotHours"), 1e-9);
        assertEquals("2026-10-09 09:00:00", t.get("since"));
        Map<String, Object> duel = format(t, "1v1");
        assertEquals(3L, duel.get("battles"));
        assertEquals(Map.of("comparisons", 2L, "recordings", 1L), duel.get("kinds"));
        assertEquals(1L, format(t, "team").get("battles"));
        assertEquals(3L, totals.battles("w"));
        assertEquals(1L, totals.battles("v"));
        assertEquals(0L, totals.battles("nobody"));
    }

    @Test
    void addsTheBattlesTheServerLogsAfterReading(@TempDir Path jobs) throws IOException {
        Path a = job(jobs, "a", "compare", "1v1", null);
        BattleTotals totals = BattleTotals.read(jobs);
        assertEquals(0L, totals.toMap().get("battles"));

        totals.add(a, "w", 12.5, "2026-10-14 10:00:00");
        totals.add(jobs.resolve("gone"), "w", 1.0, "2026-10-14 09:00:00");

        Map<String, Object> t = totals.toMap();
        assertEquals(2L, t.get("battles"));
        assertEquals("2026-10-14 09:00:00", t.get("since"));
        assertEquals(1L, format(t, "1v1").get("battles"));
        assertEquals(Map.of("?", 1L), format(t, "?").get("kinds"));
        assertEquals(2L, totals.battles("w"));
    }
}
