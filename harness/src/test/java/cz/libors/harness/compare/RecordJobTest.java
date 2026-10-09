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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordJobTest {

    private static Path job(Path dir) throws IOException {
        Files.createDirectories(dir);
        Properties spec = new Properties();
        spec.setProperty("type", "record");
        spec.setProperty("submittedAt", "1");
        spec.setProperty("robot", "cz.libors.OgiGunLog");
        spec.setProperty("version", "w1234567");
        spec.setProperty("opponentSet", "test");
        spec.setProperty("pairs", "2");
        spec.setProperty("keys", "gunshot,gunpass");
        spec.setProperty("jar.cz.libors.OgiGunLog_w1234567.jar", "c".repeat(64));
        try (OutputStream out = Files.newOutputStream(dir.resolve("job.properties"))) {
            spec.store(out, null);
        }
        Files.writeString(dir.resolve("opponents.txt"), "format 1v1\nsample.Walls\ncz.libors.probe.RandomProbe 0.1\n");
        Files.writeString(dir.resolve("status.properties"), "state=QUEUED\norder=1\n");
        return dir;
    }

    private static RecordJob open(Path dir) throws IOException {
        Properties spec = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve("job.properties"))) {
            spec.load(in);
        }
        return RecordJob.open(dir, spec);
    }

    private static Map<String, Object> outcome(Work work, String recording) {
        BattleSpec spec = work.spec();
        String subject = spec.robots().get(0);
        BattleOutcome o = new BattleOutcome(spec.setup(), spec.seed(),
                List.of(new ContestantResult(subject, 1, 900, 0, 0, 0, 0, 0, 0, 1),
                        new ContestantResult(spec.robots().get(1), 2, 100, 0, 0, 0, 0, 0, 0, 0)),
                Map.of(subject, 1), List.of(), 0, Map.of(subject, new BattleOutcome.Shots(10, 4)), 100,
                Map.of(subject, new BattleOutcome.TurnTimes(500, 700, 0, 0)));
        Map<String, Object> m = BattleSpec.outcomeToMap(o);
        m.put("recording", Base64.getEncoder().encodeToString(recording.getBytes(StandardCharsets.UTF_8)));
        return m;
    }

    @Test
    void writesEveryRecordingOnceAndResumes(@TempDir Path tmp) throws IOException {
        Path dir = job(tmp.resolve("j"));
        RecordJob job = open(dir);
        List<Work> first = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            first.addAll(job.next("w"));
        }
        // Pair by pair over all opponents, recorded and on compare's seeds.
        assertEquals(List.of("sample.Walls\t0", "cz.libors.probe.RandomProbe 0.1\t0", "sample.Walls\t1"),
                first.stream().map(Work::key).toList());
        assertTrue(first.get(0).spec().record());
        assertTrue(first.get(0).spec().debug());
        assertEquals(List.of("gunshot", "gunpass"), first.get(0).spec().recordKeys());
        assertEquals(Compare.pairSeed(1, "sample.Walls", 1), first.get(2).spec().seed());

        job.completed(first.get(0), outcome(first.get(0), "walls 0"), "w");
        job.lost(first.get(1));
        job.failed(first.get(2), "boom", "w");
        Path out = dir.resolve("recordings/w1234567");
        assertEquals("walls 0", Files.readString(out.resolve("sample.Walls_p0.rec.gz")));
        assertEquals(2, Files.readAllLines(out.resolve("battles.tsv")).size());

        // A restarted server plays only what is missing.
        RecordJob resumed = open(dir);
        List<String> keys = new ArrayList<>();
        List<Work> works;
        while (!(works = resumed.next("w")).isEmpty()) {
            for (Work w : works) {
                keys.add(w.key());
                resumed.completed(w, outcome(w, w.key()), "w");
            }
        }
        assertEquals(3, keys.size());
        assertEquals(State.DONE, resumed.state());
        assertTrue(Files.exists(out.resolve("cz.libors.probe.RandomProbe_0.1_p1.rec.gz")));
        assertEquals(0, resumed.inFlight());
    }

    @Test
    void aBattleThatKeepsFailingIsGivenUp(@TempDir Path tmp) throws IOException {
        RecordJob job = open(job(tmp.resolve("j")));
        Work w = job.next("w").get(0);
        for (int i = 0; i < 3; i++) {
            job.failed(w, "boom", "w");
            if (i < 2) {
                // Retried first, before the battles not yet played.
                assertEquals(w.key(), job.next("w").get(0).key());
            }
        }
        assertTrue(job.state().active());
        List<Work> works;
        while (!(works = job.next("w")).isEmpty()) {
            for (Work rest : works) {
                job.completed(rest, outcome(rest, "x"), "w");
            }
        }
        assertEquals(State.DONE, job.state());
        assertTrue(job.reason().contains("given up after 3 failures: [sample.Walls pair 0]"), job.reason());
    }
}
