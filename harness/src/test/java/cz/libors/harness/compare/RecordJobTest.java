package cz.libors.harness.compare;

import cz.libors.harness.BattleOutcome;
import cz.libors.harness.ContestantResult;
import cz.libors.harness.queue.BattleSpec;
import cz.libors.harness.queue.QueueJob.State;
import cz.libors.harness.queue.QueueJob.Work;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
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
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** A recording job of a foreign robot without its jar, as {@code submitRecordings -Psubject} queues it. */
    private static Path foreignJob(Path dir) throws IOException {
        Files.createDirectories(dir);
        Properties spec = new Properties();
        spec.setProperty("type", "record");
        spec.setProperty("submittedAt", "1");
        spec.setProperty("robot", "kc.serpent.Hydra");
        spec.setProperty("version", "0.21");
        spec.setProperty("opponentSet", "test");
        spec.setProperty("debug", "false");
        try (OutputStream out = Files.newOutputStream(dir.resolve("job.properties"))) {
            spec.store(out, null);
        }
        Files.writeString(dir.resolve("opponents.txt"), "format 1v1\nsample.Walls\nabc.Foo 1.0\n");
        Files.writeString(dir.resolve("status.properties"), "state=QUEUED\norder=1\n");
        return dir;
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    @Test
    void aForeignSubjectComesFromTheWorkerAndTheIndexHasBothSides(@TempDir Path tmp) throws IOException {
        Path dir = foreignJob(tmp.resolve("j"));
        RecordJob job = open(dir);
        // The worker must have the subject like an opponent, and only a worker for foreign robots may take the job.
        assertEquals(List.of("sample.Walls", "abc.Foo 1.0", "kc.serpent.Hydra 0.21"), job.opponents());
        assertTrue(job.needsForeign());
        assertTrue(job.jars().isEmpty());
        Work w = job.next("w").get(0);
        assertEquals(List.of("kc.serpent.Hydra 0.21", "sample.Walls"), w.spec().robots());
        assertFalse(w.spec().debug());
        assertTrue(w.spec().record());

        BattleSpec spec = w.spec();
        BattleOutcome o = new BattleOutcome(spec.setup(), spec.seed(),
                List.of(new ContestantResult("kc.serpent.Hydra 0.21", 1, 5000, 1700, 340, 2400, 400, 100, 60, 34),
                        new ContestantResult("sample.Walls", 2, 300, 50, 10, 200, 0, 40, 0, 1)),
                Map.of("kc.serpent.Hydra 0.21", 0, "sample.Walls", 2), List.of(), 0,
                Map.of("kc.serpent.Hydra 0.21", new BattleOutcome.Shots(600, 250),
                        "sample.Walls", new BattleOutcome.Shots(400, 30)), 4321, Map.of());
        Map<String, Object> m = BattleSpec.outcomeToMap(o);
        String recording = "#ogi-recording\t1\nr\t0\t0\t0\nend\t0\t812\nend\t1\t655\n";
        m.put("recording", Base64.getEncoder().encodeToString(gzip(recording)));
        job.completed(w, m, "w");

        Path index = dir.resolve("recordings/kc.serpent.Hydra_0.21/battles.tsv");
        List<String> lines = Files.readAllLines(index);
        assertEquals(RecordJob.INDEX_COLUMNS, List.of(lines.get(0).split("\t")));
        String[] f = lines.get(1).split("\t", -1);
        assertEquals(RecordJob.INDEX_COLUMNS.size(), f.length);
        Map<String, String> row = new java.util.HashMap<>();
        for (int i = 0; i < f.length; i++) {
            row.put(RecordJob.INDEX_COLUMNS.get(i), f[i]);
        }
        assertEquals("600", row.get("fired"));
        assertEquals("250", row.get("hits"));
        assertEquals("2", row.get("opponentSkippedTurns"));
        assertEquals("false", row.get("debug"));
        assertEquals("5000", row.get("score"));
        assertEquals("60", row.get("ramDamageBonus"));
        assertEquals("34", row.get("firsts"));
        assertEquals("300", row.get("opponentScore"));
        assertEquals("40", row.get("opponentRamDamage"));
        assertEquals("1", row.get("opponentFirsts"));
        assertEquals("400", row.get("opponentFired"));
        assertEquals("30", row.get("opponentHits"));
        assertEquals("4321", row.get("elapsedMillis"));
        assertEquals("812,655", row.get("roundTurns"));
    }

    @Test
    void checkRefusesOurRobotWithoutItsJarAndTheSubjectAsItsOwnOpponent() {
        Properties spec = new Properties();
        spec.setProperty("opponentSet", "test");
        spec.setProperty("robot", "cz.libors.Ogi");
        spec.setProperty("version", "w1234567");
        List<String> lines = List.of("format 1v1", "sample.Walls", "kc.serpent.Hydra 0.21");
        assertThrows(IllegalArgumentException.class, () -> RecordJob.check(spec, lines));
        spec.setProperty("robot", "kc.serpent.Hydra");
        spec.setProperty("version", "0.21");
        assertThrows(IllegalArgumentException.class, () -> RecordJob.check(spec, lines));
        spec.setProperty("version", "0.22");
        assertTrue(RecordJob.check(spec, lines).contains("the worker's jar"));
        spec.setProperty("debug", "no");
        assertThrows(IllegalArgumentException.class, () -> RecordJob.check(spec, lines));
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
