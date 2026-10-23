package cz.libors.harness.compare;

import cz.libors.harness.BattleOutcome;
import cz.libors.harness.ContestantResult;
import cz.libors.harness.Json;
import cz.libors.harness.queue.BattleCache;
import cz.libors.harness.queue.BattleSpec;
import cz.libors.harness.queue.QueueJob.State;
import cz.libors.harness.queue.QueueJob.Work;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeleeJobTest {

    /** Two groups: the strong one always gets three of the nine places, the other six. */
    private static final String SET = "title A melee test\nformat melee\nweight [strong] 30\nweight [weak] 60\n"
            + "[strong]\ns.A 1\ns.B 1\ns.C 1\ns.D 1\ns.E 1\n"
            + "[weak]\nw.A 1\nw.B 1\nw.C 1\nw.D 1\nw.E 1\nw.F 1\nw.G 1\nw.H 1\nw.I 1\nw.J 1\n";

    private static Properties spec(Map<String, String> options) {
        Properties spec = new Properties();
        spec.setProperty("type", "compare");
        spec.setProperty("submittedAt", "1");
        spec.setProperty("robot", "cz.libors.Ogi");
        spec.setProperty("team", "cz.libors.OgiTeam");
        spec.setProperty("baseline", "base");
        spec.setProperty("candidate", "cand");
        spec.setProperty("opponentSet", "melee-test");
        spec.setProperty("jar.cz.libors.Ogi_base.jar", "a".repeat(64));
        spec.setProperty("jar.cz.libors.Ogi_cand.jar", "b".repeat(64));
        options.forEach(spec::setProperty);
        return spec;
    }

    private static Path job(Path dir, Map<String, String> options) throws IOException {
        Files.createDirectories(dir);
        try (OutputStream out = Files.newOutputStream(dir.resolve("job.properties"))) {
            spec(options).store(out, null);
        }
        Files.writeString(dir.resolve("opponents.txt"), SET);
        Files.writeString(dir.resolve("status.properties"), "state=QUEUED\norder=1\n");
        return dir;
    }

    private static MeleeJob open(Path dir) throws IOException {
        Properties spec = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve("job.properties"))) {
            spec.load(in);
        }
        return MeleeJob.open(dir, spec);
    }

    /**
     * What a slot would send back: the subject scores 1000 (plus {@code gain} for the candidate) with noise, strong
     * opponents 1500 and weak ones 500, so its pairwise shares are near 40 and 67.
     */
    private static Map<String, Object> outcome(Work work, Random random, double gain) {
        BattleSpec spec = work.spec();
        List<ContestantResult> results = new ArrayList<>();
        String subject = null;
        for (String robot : spec.robots()) {
            boolean ours = robot.startsWith("cz.libors.Ogi");
            int score = (int) Math.round(ours ? 1000 + 50 * random.nextGaussian() + (robot.endsWith("cand") ? gain : 0)
                    : robot.startsWith("s.") ? 1500 : 500);
            results.add(new ContestantResult(robot, 0, score, 0, 0, 0, 0, 0, 0, ours ? 7 : 1));
            if (ours) {
                subject = robot;
            }
        }
        results.sort(Comparator.comparingInt(ContestantResult::score).reversed());
        BattleOutcome o = new BattleOutcome(spec.setup(), spec.seed(), results, Map.of(), List.of(), 0,
                Map.of(subject, new BattleOutcome.Shots(100, 20)), 1000,
                Map.of(subject, new BattleOutcome.TurnTimes(1000, 900, 0, 0)));
        return BattleSpec.outcomeToMap(o);
    }

    private static int battles(Path dir) throws IOException {
        return (int) Files.readAllLines(dir.resolve("battles.tsv")).stream().filter(l -> l.startsWith("battle")).count();
    }

    /** Plays a job to its end on twelve slots; returns the battles it handed out. */
    private static int play(MeleeJob job, Random random, double gain) throws IOException {
        Deque<Work> running = new ArrayDeque<>();
        int handedOut = 0;
        while (job.state().active()) {
            while (running.size() < 12) {
                List<Work> works = job.next("w");
                if (works.isEmpty()) {
                    break;
                }
                handedOut += works.size();
                running.addAll(works);
            }
            if (running.isEmpty()) {
                break;
            }
            Work w = running.poll();
            job.completed(w, outcome(w, random, gain), "w");
        }
        return handedOut;
    }

    @Test
    void fixedModePlaysTheDrawsAskedForAndSummarisesThemAsTheRumbleWould(@TempDir Path tmp) throws IOException {
        Path dir = job(tmp.resolve("j"), Map.of("draws", "5"));
        MeleeJob job = open(dir);
        assertEquals("melee", job.format());
        assertEquals(10, play(job, new Random(1), 0));
        assertEquals(State.DONE, job.state());
        assertEquals("5 draws", job.reason());
        assertEquals(10, battles(dir));
        assertEquals(0, job.inFlight());
        Map<String, Object> summary = Json.parseObject(Files.readString(dir.resolve("summary.json")));
        List<?> boards = (List<?>) summary.get("scoreboards");
        List<String> names = boards.stream().map(b -> (String) ((Map<?, ?>) b).get("name")).toList();
        assertEquals(List.of("all", "strong", "weak", "survival"), names);
        // Three strong opponents at 1000/2500 and six weak ones at 1000/1500, near enough with the noise.
        Map<?, ?> all = (Map<?, ?>) boards.get(0);
        double expected = (3 * 40.0 + 6 * 200.0 / 3) / 9;
        assertEquals(expected, ((Number) all.get("baseline")).doubleValue(), 2.0);
        assertEquals(40.0, ((Number) ((Map<?, ?>) boards.get(1)).get("baseline")).doubleValue(), 2.0);
        assertEquals(100.0 * 7 / 35, ((Number) ((Map<?, ?>) boards.get(3)).get("baseline")).doubleValue(), 1e-9);
        List<?> opponents = (List<?>) summary.get("opponents");
        int appearances = opponents.stream().mapToInt(o -> ((Number) ((Map<?, ?>) o).get("pairs")).intValue()).sum();
        assertEquals(5 * 9, appearances, "every opponent of every draw, once per draw");
    }

    @Test
    void sequentialModeDecidesAndStopsHandingOut(@TempDir Path tmp) throws IOException {
        Path dir = job(tmp.resolve("j"), Map.of("bounds", "screening"));
        MeleeJob job = open(dir);
        play(job, new Random(2), 300);
        assertEquals(State.DONE, job.state());
        assertTrue(job.reason().startsWith("H1"), job.reason());
        assertEquals(List.of(), job.next("w"));
        assertTrue(((Number) job.progress().get("pairs")).intValue() >= MeleeJob.BURN_IN_DRAWS);
    }

    @Test
    void bothVersionsOfADrawMeetTheSameFieldOnTheSameSeed(@TempDir Path tmp) throws IOException {
        MeleeJob job = open(job(tmp.resolve("j"), Map.of("draws", "3")));
        for (int d = 0; d < 3; d++) {
            List<Work> pair = job.next("w");
            assertEquals(2, pair.size());
            BattleSpec a = pair.get(0).spec();
            BattleSpec b = pair.get(1).spec();
            assertEquals(a.seed(), b.seed());
            assertEquals(10, a.robots().size());
            int place = a.robots().indexOf(a.robots().stream().filter(r -> r.startsWith("cz.libors")).findFirst()
                    .orElseThrow());
            assertTrue(b.robots().get(place).startsWith("cz.libors"), "the same place");
            List<String> other = new ArrayList<>(b.robots());
            other.set(place, a.robots().get(place));
            assertEquals(a.robots(), other, "the same opponents in the same order");
            assertEquals(1000, a.width());
            assertEquals(35, a.rounds());
        }
    }

    @Test
    void aBattleWithoutTheTenResultsIsPlayedAgain(@TempDir Path tmp) throws IOException {
        MeleeJob job = open(job(tmp.resolve("j"), Map.of("draws", "1")));
        Work w = job.next("w").get(0);
        Map<String, Object> outcome = outcome(w, new Random(3), 0);
        List<?> results = (List<?>) outcome.get("results");
        outcome.put("results", results.subList(0, 9));
        job.completed(w, outcome, "w");
        assertEquals(List.of(w), job.next("w"));
        assertEquals(0, battles(tmp.resolve("j")));
        assertTrue(job.state().active());
    }

    @Test
    void resumesFromItsLog(@TempDir Path tmp) throws IOException {
        Path dir = job(tmp.resolve("j"), Map.of("draws", "6"));
        MeleeJob job = open(dir);
        Random random = new Random(4);
        for (int i = 0; i < 3; i++) {
            for (Work w : job.next("w")) {
                job.completed(w, outcome(w, random, 0), "w");
            }
        }
        // Half a draw: the resumed job plays it as a new draw.
        Work half = job.next("w").get(0);
        job.completed(half, outcome(half, random, 0), "w");
        MeleeJob resumed = open(dir);
        assertEquals(3.0, ((Number) resumed.progress().get("pairs")).doubleValue());
        int handedOut = play(resumed, random, 0);
        assertEquals(6, handedOut);
        assertEquals(State.DONE, resumed.state());
        assertEquals(6.0, ((Number) resumed.progress().get("pairs")).doubleValue());
    }

    private static BattleCache cache(Path tmp) throws IOException {
        return new BattleCache(tmp.resolve("battle-cache.jsonl"), new BattleCache.Workers() {
            @Override
            public Optional<Map<String, String>> environment(String worker) {
                return Optional.of(Map.of("java", "17"));
            }

            @Override
            public Optional<String> robotJar(String worker, String robot) {
                return Optional.of("jar of " + robot);
            }
        });
    }

    /** The subject's score of each battle of a version, by draw. */
    private static Map<String, String> scores(Path dir, String side) throws IOException {
        Map<String, String> m = new TreeMap<>();
        for (String line : Files.readAllLines(dir.resolve("battles.tsv"))) {
            String[] f = line.split("\t");
            if (f[0].equals("battle") && f[2].equals(side)) {
                m.put(f[1], f[5]);
            }
        }
        return m;
    }

    @Test
    void aChainedComparisonTakesTheSharedVersionsBattlesFromTheCache(@TempDir Path tmp) throws IOException {
        BattleCache cache = cache(tmp);
        Random random = new Random(5);
        Path ab = job(tmp.resolve("ab"), Map.of("draws", "4", "baseline", "a", "candidate", "b",
                "jar.cz.libors.Ogi_a.jar", "a".repeat(64), "jar.cz.libors.Ogi_b.jar", "b".repeat(64)));
        MeleeJob first = open(ab);
        first.cache(cache);
        assertEquals(8, play(first, random, 0));
        assertEquals(8, cache.size());
        Path bc = job(tmp.resolve("bc"), Map.of("draws", "4", "baseline", "b", "candidate", "c",
                "jar.cz.libors.Ogi_b.jar", "b".repeat(64), "jar.cz.libors.Ogi_c.jar", "c".repeat(64)));
        MeleeJob second = open(bc);
        second.cache(cache);
        assertEquals(4, play(second, random, 0), "only c's battles are played");
        assertEquals(State.DONE, second.state());
        assertEquals(4, ((Number) second.progress().get("reusedBattles")).intValue());
        assertEquals(scores(ab, "CANDIDATE"), scores(bc, "BASELINE"), "b's battles are the same in both");
    }

    @Test
    void theChecksRefuseWhatAMeleeComparisonCannotDo() {
        List<String> lines = SET.lines().toList();
        assertTrue(MeleeJob.melee(lines));
        assertTrue(MeleeJob.check(spec(Map.of()), lines).contains("melee"));
        for (Map<String, String> bad : List.of(Map.of("metric", "hits"), Map.of("starts", "fixed"),
                Map.of("pairsPerOpponent", "2"), Map.of("maxPairs", "20"), Map.of("draws", "-1"),
                Map.of("candidate", "base"))) {
            assertThrows(IllegalArgumentException.class, () -> MeleeJob.check(spec(bad), lines), bad.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> CompareJob.check(spec(Map.of()), lines));
    }

    @Test
    void aSummaryThatCannotBeWrittenStillEndsTheJob(@TempDir Path tmp) throws IOException {
        Path dir = job(tmp.resolve("j"), Map.of("draws", "2"));
        Files.createDirectories(dir.resolve("summary.json"));
        MeleeJob job = open(dir);
        play(job, new Random(1), 0);
        assertEquals(State.DONE, job.state(), "a job left running could never be cancelled");
    }
}
