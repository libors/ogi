package cz.libors.harness.queue;

import cz.libors.harness.compare.CompareJob;
import cz.libors.harness.compare.RecordJob;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Puts a job into the queue, whether the server runs or not: a new directory under {@code <state>/jobs/}, which the
 * server picks up within seconds. The job's jars are copied into {@code <state>/jars/}; a jar name stands for one
 * content there, so a version must change when its code does (the working tree's snapshots are named after their
 * content, see harness/build.gradle.kts).
 *
 * <p>Usage: {@code Submit state=<dir> type=compare robot=<class> team=<class> baseline=<version>
 * candidate=<version> opponents=<file> jar=<path>... [bounds=..] [maxPairs=n] [pairsPerOpponent=n] [starts=..]
 * [seed=n] [rounds=n] [metric=..] [timeout=<seconds per battle>] [note=<text>] [reuse=false]}, or {@code type=record robot=<class>
 * version=<version> opponents=<file> [jar=<path>] [pairs=n] [keys=<debug key>,...] [seed=n] [rounds=n] [starts=..]
 * [timeout=..] [debug=false] [note=..]}; a recording without the subject's jar plays a foreign robot the worker has.
 */
public final class Submit {

    private static final List<String> PASSED = List.of("robot", "team", "baseline", "candidate", "bounds",
            "maxPairs", "pairsPerOpponent", "starts", "seed", "rounds", "metric", "timeout", "note", "version", "pairs",
            "keys", "reuse", "debug");

    private Submit() {
    }

    public static void main(String[] args) throws IOException {
        Options o = Options.parse(args, 0);
        Path state = Path.of(o.get("state")).toAbsolutePath();
        String type = o.get("type", "compare");
        if (!type.equals("compare") && !type.equals("record")) {
            throw new IllegalArgumentException("unknown job type: " + type);
        }
        long now = System.currentTimeMillis();
        Properties spec = new Properties();
        spec.setProperty("type", type);
        spec.setProperty("submittedAt", Long.toString(now));
        for (String key : PASSED) {
            if (o.has(key) && !o.get(key).isEmpty()) {
                spec.setProperty(key, o.get(key));
            }
        }
        Path opponents = Path.of(o.get("opponents"));
        String setName = opponents.getFileName().toString().replaceFirst("\\.txt$", "");
        spec.setProperty("opponentSet", setName);
        List<String> lines = Files.readAllLines(opponents);

        Path jars = state.resolve("jars");
        Files.createDirectories(jars);
        for (String jar : o.all("jar")) {
            Path file = Path.of(jar);
            String name = file.getFileName().toString();
            String sha = Executor.sha256(file);
            Path stored = jars.resolve(name);
            if (Files.exists(stored) && !Executor.sha256(stored).equals(sha)) {
                throw new IllegalArgumentException(name + " is already queued with other content; a version's code "
                        + "must not change (delete " + stored + " if no job uses it)");
            }
            if (!Files.exists(stored)) {
                Path tmp = jars.resolve(name + ".tmp");
                Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, stored, StandardCopyOption.ATOMIC_MOVE);
            }
            spec.setProperty("jar." + name, sha);
        }
        String what = type.equals("compare") ? CompareJob.check(spec, lines) : RecordJob.check(spec, lines);

        String id = new SimpleDateFormat("yyMMdd-HHmmss").format(new Date(now)) + "-"
                + Integer.toHexString(0x100 + new SecureRandom().nextInt(0xf00));
        Path incoming = state.resolve("incoming").resolve(id);
        Files.createDirectories(incoming);
        try (OutputStream out = Files.newOutputStream(incoming.resolve("job.properties"))) {
            spec.store(out, "queue job, as submitted");
        }
        Files.write(incoming.resolve("opponents.txt"), lines);
        Properties status = new Properties();
        status.setProperty("state", QueueJob.State.QUEUED.name());
        status.setProperty("order", Long.toString(now));
        try (OutputStream out = Files.newOutputStream(incoming.resolve("status.properties"))) {
            status.store(out, "queue job status");
        }
        Files.createDirectories(state.resolve("jobs"));
        // One move, so the server never sees half a job.
        Files.move(incoming, state.resolve("jobs").resolve(id), StandardCopyOption.ATOMIC_MOVE);
        System.out.println("Queued " + id + ": " + what
                + (spec.getProperty("note") == null ? "" : " (" + spec.getProperty("note") + ")"));
        System.out.println("Dashboard: http://localhost:" + o.get("port", "8765") + "/  (the server: ./gradlew queueServer)");
    }
}
