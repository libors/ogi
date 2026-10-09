package cz.libors.harness.compare;

import cz.libors.harness.BattleOutcome;
import cz.libors.harness.ContestantResult;
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
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompareJobTest {

    private static final String SET = "format 1v1\n[strong]\nsample.Walls\n[other]\nsample.SpinBot\n";

    private static Path job(Path dir, Map<String, String> options) throws IOException {
        Files.createDirectories(dir);
        Properties spec = new Properties();
        spec.setProperty("type", "compare");
        spec.setProperty("submittedAt", "1");
        spec.setProperty("robot", "cz.libors.Ogi");
        spec.setProperty("team", "cz.libors.OgiTeam");
        spec.setProperty("baseline", "base");
        spec.setProperty("candidate", "cand");
        spec.setProperty("opponentSet", "test");
        spec.setProperty("jar.cz.libors.Ogi_base.jar", "a".repeat(64));
        spec.setProperty("jar.cz.libors.Ogi_cand.jar", "b".repeat(64));
        options.forEach(spec::setProperty);
        try (OutputStream out = Files.newOutputStream(dir.resolve("job.properties"))) {
            spec.store(out, null);
        }
        Files.writeString(dir.resolve("opponents.txt"), SET);
        Files.writeString(dir.resolve("status.properties"), "state=QUEUED\norder=1\n");
        return dir;
    }

    private static CompareJob open(Path dir) throws IOException {
        Properties spec = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve("job.properties"))) {
            spec.load(in);
        }
        return CompareJob.open(dir, spec);
    }

    /** What a slot would send back for this battle, with the subject's score share given. */
    private static Map<String, Object> outcome(Work work, double share) {
        BattleSpec spec = work.spec();
        String subject = spec.robots().get(0);
        String opponent = spec.robots().get(1);
        int mine = (int) Math.round(share * 10);
        BattleOutcome o = new BattleOutcome(spec.setup(), spec.seed(),
                List.of(new ContestantResult(subject, 1, mine, 0, 0, 0, 0, 0, 0, 1),
                        new ContestantResult(opponent, 2, 1000 - mine, 0, 0, 0, 0, 0, 0, 0)),
                Map.of(), List.of(), 0, Map.of(subject, new BattleOutcome.Shots(10, 5)), 100);
        return BattleSpec.outcomeToMap(o);
    }

    private static double share(Work work, Random random, double gain) {
        boolean candidate = work.spec().robots().get(0).endsWith("cand");
        return 60 + 5 * random.nextGaussian() + (candidate ? gain : 0);
    }

    private static int battles(Path dir) throws IOException {
        return (int) Files.readAllLines(dir.resolve("battles.tsv")).stream().filter(l -> l.startsWith("battle")).count();
    }

    @Test
    void fixedModeEndsWithExactlyThePairsAskedFor(@TempDir Path tmp) throws IOException {
        Path dir = job(tmp.resolve("j"), Map.of("pairsPerOpponent", "3"));
        CompareJob job = open(dir);
        Random random = new Random(1);
        Deque<Work> running = new ArrayDeque<>();
        while (job.state().active()) {
            // Twelve slots: hand out what the job gives, then finish the oldest battle.
            while (running.size() < 12) {
                List<Work> works = job.next("w");
                if (works.isEmpty()) {
                    break;
                }
                running.addAll(works);
            }
            Work w = running.poll();
            job.completed(w, outcome(w, share(w, random, 0)), "w");
        }
        assertEquals(State.DONE, job.state());
        assertEquals("3 pairs per opponent", job.reason());
        assertEquals(12, battles(dir));
        assertEquals(0, job.inFlight());
        assertTrue(Files.exists(dir.resolve("summary.json")));
    }

    @Test
    void sequentialModeDecidesAndStopsHandingOut(@TempDir Path tmp) throws IOException {
        Path dir = job(tmp.resolve("j"), Map.of("bounds", "screening"));
        CompareJob job = open(dir);
        Random random = new Random(2);
        Deque<Work> running = new ArrayDeque<>();
        int guard = 0;
        while (job.state().active() && guard++ < 10_000) {
            List<Work> works = job.next("w");
            running.addAll(works);
            if (running.size() >= 8 || works.isEmpty()) {
                Work w = running.poll();
                job.completed(w, outcome(w, share(w, random, 5)), "w");
            }
        }
        assertEquals(State.DONE, job.state());
        assertTrue(job.reason().startsWith("H1"), job.reason());
        assertEquals(List.of(), job.next("w"));
        // Battles still being played are logged when they come back.
        int logged = battles(dir);
        int late = running.size();
        while (!running.isEmpty()) {
            Work w = running.poll();
            job.completed(w, outcome(w, 60), "w");
        }
        assertEquals(logged + late, battles(dir));
        assertEquals(0, job.inFlight());
    }

    @Test
    void aFailedBattleIsRetriedOnItsWorkerThenFailsTheJob(@TempDir Path tmp) throws IOException {
        CompareJob job = open(job(tmp.resolve("j"), Map.of()));
        List<Work> pair = job.next("w");
        Work w = pair.get(0);
        job.failed(w, "boom", "w");
        assertEquals(List.of(w), job.next("w"));
        job.failed(w, "boom", "w");
        assertEquals(List.of(w), job.next("w"));
        assertTrue(job.state().active());
        job.failed(w, "boom", "w");
        assertEquals(State.FAILED, job.state());
        assertTrue(job.reason().contains("failed 3 times on w: boom"), job.reason());
    }

    @Test
    void aLostWorkersPairsAreReplacedByNewOnes(@TempDir Path tmp) throws IOException {
        Path dir = job(tmp.resolve("j"), Map.of("pairsPerOpponent", "1"));
        CompareJob job = open(dir);
        List<Work> first = job.next("a");
        List<Work> second = job.next("a");
        job.completed(first.get(0), outcome(first.get(0), 50), "a");
        job.lost(first.get(1));
        second.forEach(job::lost);
        job.workerLost("a");
        assertEquals(0, job.inFlight());
        // Both opponents are free again; the new pairs get new indices and seeds.
        List<Work> again = job.next("b");
        assertEquals(2, again.size());
        assertTrue(again.get(0).key().contains("\t1\t"), again.get(0).key());
        while (job.state().active()) {
            List<Work> works = again.isEmpty() ? job.next("b") : again;
            again = List.of();
            for (Work w : works) {
                job.completed(w, outcome(w, 55), "b");
            }
        }
        assertEquals(State.DONE, job.state());
        // The half pair of the lost worker stays in the log, which the test ignores.
        assertEquals(5, battles(dir));
    }

    @Test
    void resumesFromItsLog(@TempDir Path tmp) throws IOException {
        Path dir = job(tmp.resolve("j"), Map.of("pairsPerOpponent", "4"));
        CompareJob job = open(dir);
        Random random = new Random(3);
        for (int i = 0; i < 3; i++) {
            for (Work w : job.next("w")) {
                job.completed(w, outcome(w, share(w, random, 0)), "w");
            }
        }
        job.next("w").subList(0, 1).forEach(w -> {
            try {
                job.completed(w, outcome(w, 50), "w");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        CompareJob resumed = open(dir);
        assertEquals(3.0, ((Number) resumed.progress().get("pairs")).doubleValue());
        int handedOut = 0;
        while (resumed.state().active()) {
            for (Work w : resumed.next("w")) {
                handedOut++;
                resumed.completed(w, outcome(w, share(w, random, 0)), "w");
            }
        }
        assertEquals(10, handedOut);
        assertEquals(8.0, ((Number) resumed.progress().get("pairs")).doubleValue());
    }
}
