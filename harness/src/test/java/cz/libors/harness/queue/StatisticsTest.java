package cz.libors.harness.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsTest {

    private static final String HEADER = "finished\tworker\tslot\tbattle\twaitedSeconds\tbattleSeconds\toutcome\n";
    private static final String TIERS = "weight [strong] 30\nweight [medium] 270\nweight [weak] 916\n";

    private final List<Statistics.Job> jobs = new ArrayList<>();

    private static long at(String time) {
        return LocalDateTime.parse(time.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static String bots(String group, int n) {
        return "[" + group + "]\n" + IntStream.range(0, n).mapToObj(i -> "x." + group + i + " 1.0\n")
                .collect(Collectors.joining());
    }

    /** A sample of the 1v1 rumble: three groups that weigh as their tiers, a few bots each. */
    private static final String SAMPLE = "title A sample\nformat 1v1\n" + TIERS + bots("strong", 2) + bots("medium", 2)
            + bots("weak", 2);
    /** The whole 1v1 rumble, but for a few bots. */
    private static final String WHOLE = "format 1v1\n" + TIERS + bots("strong", 30) + bots("medium", 270)
            + bots("weak", 900);
    /** The strong and medium tiers only: their weights stand for 300 of the 1216. */
    private static final String UPPER = "format 1v1\nweight [strong] 30\nweight [medium] 270\n" + bots("strong", 2)
            + bots("medium", 2);
    /** The whole TeamRumble without weights. */
    private static final String TEAMS = "format team\n" + bots("strong", 12) + bots("weak", 33);

    private void job(Path root, String id, String type, String format, String state, String set, String setLines,
                     String finished, String baseline, String candidate, Map<String, Object> scoreboard,
                     String dispatch) throws IOException {
        Path dir = Files.createDirectories(root.resolve(id));
        Files.writeString(dir.resolve("job.properties"), "type=" + type + "\nopponentSet=" + set + "\n");
        Files.writeString(dir.resolve("opponents.txt"), setLines);
        if (scoreboard != null) {
            Files.writeString(dir.resolve("summary.json"), cz.libors.harness.Json.write(Map.of("scoreboards",
                    List.of(board("all", 50, 51), scoreboard))));
        }
        if (dispatch != null) {
            Files.writeString(dir.resolve("dispatch.tsv"), HEADER + dispatch);
        }
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("opponentSet", set);
        progress.put("baseline", baseline);
        progress.put("candidate", candidate);
        progress.put("reusedBattles", 10L);
        jobs.add(new Statistics.Job(id, dir, type, format, state, at(finished) - 3_600_000, at(finished),
                "note " + id, progress));
    }

    private static Map<String, Object> board(String name, double baseline, double candidate) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("apsBaseline", baseline);
        b.put("apsCandidate", candidate);
        b.put("difference", candidate - baseline);
        b.put("halfWidth", 0.5);
        return b;
    }

    private static String battles(String time, int n) {
        return IntStream.range(0, n).mapToObj(i -> time + "\tw\t0\tx.Bot 1.0 " + i + " CANDIDATE\t0.0\t60.0\tok\n")
                .collect(Collectors.joining());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> format(Map<String, Object> stats, String name) {
        return ((List<Map<String, Object>>) stats.get("formats")).stream().filter(f -> f.get("format").equals(name))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> part(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    @Test
    void plotsTheComparisonsWhoseSetsStandForTheWholeRumble(@TempDir Path root) throws IOException {
        job(root, "a", "compare", "1v1", "DONE", "pop", SAMPLE, "2026-10-10 10:00:00", "v1", "v2",
                board("rumble", 80, 81), battles("2026-10-10 09:30:00", 4));
        job(root, "b", "compare", "1v1", "DONE", "pop", SAMPLE, "2026-10-10 11:00:00", "v2", "v3",
                board("rumble", 81, 80.5), battles("2026-10-10 10:30:00", 2));
        // Back to an older version: a dot, the line stays at v2.
        job(root, "c", "compare", "1v1", "DONE", "pop", SAMPLE, "2026-10-10 12:00:00", "v1", "v4",
                board("rumble", 80, 82), null);
        job(root, "d", "compare", "1v1", "DONE", "pop", SAMPLE, "2026-10-10 13:00:00", "v2", "v5",
                board("rumble", 81.1, 81.2), battles("2026-10-10 12:59:30", 1));
        job(root, "e", "compare", "1v1", "DONE", "rumble", WHOLE, "2026-10-10 14:00:00", "v2", "v5",
                board("rumble", 82, 82.3), battles("2026-10-10 13:30:00", 8));
        // A subset, a cancelled comparison and a recording count as jobs and battles, but measure nothing.
        job(root, "f", "compare", "1v1", "DONE", "upper", UPPER, "2026-10-10 15:00:00", "v5", "v6",
                board("rumble", 60, 61), battles("2026-10-10 14:30:00", 16));
        job(root, "g", "compare", "1v1", "CANCELLED", "pop", SAMPLE, "2026-10-10 16:00:00", "v5", "v7",
                board("rumble", 82, 83), null);
        job(root, "h", "record", "1v1", "DONE", "pop", SAMPLE, "2026-10-10 17:00:00", null, null, null,
                battles("2026-10-10 16:30:00", 32));
        job(root, "t", "compare", "team", "DONE", "teams", TEAMS, "2026-10-10 10:00:00", "v1", "v2",
                board("strong", 40, 45), battles("2026-10-10 09:00:00", 3));

        Map<String, Object> stats = new Statistics().toMap(jobs, BattleTotals.read(root));

        Map<String, Object> duel = format(stats, "1v1");
        Map<String, Object> counts = part(duel, "jobs");
        assertEquals(8, counts.get("submitted"));
        assertEquals(7L, counts.get("comparisons"));
        assertEquals(1L, counts.get("recordings"));
        assertEquals(Map.of("DONE", 7L, "CANCELLED", 1L), counts.get("states"));
        Map<String, Object> battles = part(duel, "battles");
        assertEquals(63L, battles.get("played"));
        assertEquals(63 / 60.0, (double) battles.get("slotHours"), 1e-9);
        assertEquals(80L, battles.get("reused"));
        assertEquals(at("2026-10-10 09:30:00"), battles.get("firstEnded"));

        Map<String, Object> rumble = part(duel, "rumble");
        assertEquals(1216.0, rumble.get("standsFor"));
        List<Map<String, Object>> sets = list(rumble, "sets");
        assertEquals(List.of("pop", "rumble", "upper"), sets.stream().map(s -> s.get("set")).toList());
        assertEquals(List.of(true, true, false), sets.stream().map(s -> s.get("measuresRumble")).toList());
        assertEquals(List.of(true, false, false), sets.stream().map(s -> s.get("sample")).toList());
        assertEquals(List.of(6L, 1L, 1L), sets.stream().map(s -> s.get("jobs")).toList());
        assertEquals(List.of(4L, 1L, 0L), sets.stream().map(s -> s.get("plotted")).toList());

        List<Map<String, Object>> points = list(rumble, "points");
        assertEquals(List.of("a", "b", "c", "d", "e"), points.stream().map(p -> p.get("id")).toList());
        assertEquals(List.of(true, true, false, true, true), points.stream().map(p -> p.get("line")).toList());
        // The battles of all the format's jobs that had ended by then, to the minute.
        assertEquals(List.of(4L, 6L, 6L, 7L, 15L), points.stream().map(p -> p.get("battles")).toList());
        assertEquals(7 / 60.0, (double) points.get(3).get("slotHours"), 1e-9);
        Map<String, Object> e = points.get(4);
        assertEquals("rumble", e.get("board"));
        assertEquals(82.0, e.get("apsBaseline"));
        assertEquals(82.3, (double) e.get("apsCandidate"), 1e-9);
        assertEquals("v5", e.get("candidate"));
        assertEquals("note e", e.get("note"));

        // A team set without weights stands for its teams; its APS is the board of all opponents.
        Map<String, Object> team = part(format(stats, "team"), "rumble");
        assertEquals(45.0, team.get("standsFor"));
        Map<String, Object> t = list(team, "points").get(0);
        assertEquals("all", t.get("board"));
        assertEquals(51.0, t.get("apsCandidate"));
        assertFalse((boolean) list(team, "sets").get(0).get("sample"));
    }

    @Test
    void aSetThatChangedShowsOncePerVersion(@TempDir Path root) throws IOException {
        String early = "format 1v1\n" + bots("strong", 5) + bots("weak", 5);
        job(root, "a", "compare", "1v1", "DONE", "pop", early, "2026-10-09 10:00:00", "v1", "v2",
                board("strong", 50, 51), null);
        job(root, "b", "compare", "1v1", "DONE", "pop", SAMPLE, "2026-10-10 10:00:00", "v1", "v2",
                board("rumble", 80, 81), null);

        Map<String, Object> rumble = part(format(new Statistics().toMap(jobs, BattleTotals.read(root)), "1v1"),
                "rumble");

        List<Map<String, Object>> sets = list(rumble, "sets");
        assertEquals(2, sets.size());
        assertEquals(10, sets.get(0).get("opponents"));
        assertFalse((boolean) sets.get(0).get("measuresRumble"));
        assertTrue((boolean) sets.get(1).get("measuresRumble"));
        assertEquals(List.of("b"), list(rumble, "points").stream().map(p -> p.get("id")).toList());
    }

    @Test
    void aFormatWithBattlesButNoJobsLeftStillShows(@TempDir Path root) throws IOException {
        BattleTotals totals = BattleTotals.read(Files.createDirectories(root.resolve("jobs")));
        totals.add(root.resolve("gone"), "w", 30, "2026-10-10 10:00:00");

        Map<String, Object> unknown = format(new Statistics().toMap(List.of(), totals), "?");

        assertEquals(1L, part(unknown, "battles").get("played"));
        assertEquals(0, part(unknown, "jobs").get("submitted"));
        assertTrue(list(part(unknown, "rumble"), "points").isEmpty());
    }
}
