package cz.libors.harness.queue;

import cz.libors.harness.Json;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Battles already played, so that comparisons do not play the same battle twice: after A vs B, a comparison of B
 * with C takes B's battles from here and plays only C's. Comparisons draw a pair's seed from the run seed, the
 * opponent and the pair's number, so the same battle comes up again.
 *
 * <p>A battle is the same when everything that decides it is the same: the field, the rounds, the seed and the start
 * positions; each participant's name and the SHA-256 of the jar it is loaded from (our robot's from the job, the
 * opponents' as the worker reports them, "builtin" for Robocode's own robots); and the worker with its operating
 * system, Java, Robocode, JVM options, CPU factor and sandbox. The CPU constant is left out: the executor measures it
 * once per worker and JVM options and keeps it, so it is the same for every battle unless someone measures again;
 * after a new measurement, raise {@link #VERSION}. A battle with an opponent whose jar the worker did not report, a
 * recorded one and one in debug mode are never cached.
 *
 * <p>A cached outcome is one sample of its battle: robots that skip turns or draw from the clock do not replay bit for
 * bit (harness.md), so it is as good as a new one, not equal to it. A comparison that wants new samples takes another
 * run seed or {@code reuse=false}.
 *
 * <p>Jobs that run at once share battles too: a job claims a battle it is about to play, and another job that needs
 * the same battle waits for its outcome instead of playing it ({@link #await}). If the claimed battle does not come
 * back (it failed for good, or its worker went away), the waiting jobs are told to play it themselves.
 *
 * <p>Stored as one JSON object per line in {@code <state>/battle-cache.jsonl}; a line cut short by a crash is skipped.
 * The claims live in memory only: after a restart the jobs resume from their logs.
 */
public final class BattleCache {

    /** Version 2: every battle starts with empty robot data ({@code BattleRunner}); version 1's battles did not. */
    static final String VERSION = "battle-cache 2";

    /** What the cache needs to know about a worker. */
    public interface Workers {

        /** The worker's fields that decide battles, empty while it has not reported them. */
        Optional<Map<String, String>> environment(String worker);

        /** The SHA-256 of the jar the worker loads this robot or team from, "builtin", or empty if unknown. */
        Optional<String> robotJar(String worker, String robot);
    }

    /** Hears about a battle another job is playing: its outcome, or empty if that job will not bring it back. */
    public interface Waiter {
        void done(Optional<Map<String, Object>> outcome) throws IOException;
    }

    private final Path file;
    private final Workers workers;
    private final Map<String, Map<String, Object>> outcomes = new HashMap<>();
    private final Set<String> claimed = new HashSet<>();
    private final Map<String, List<Waiter>> waiting = new HashMap<>();

    public BattleCache(Path file, Workers workers) throws IOException {
        this.file = file;
        this.workers = workers;
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                try {
                    Map<String, Object> entry = Json.parseObject(line);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> outcome = (Map<String, Object>) entry.get("outcome");
                    if (entry.get("key") instanceof String key && outcome != null) {
                        outcomes.put(key, outcome);
                    }
                } catch (RuntimeException e) {
                    // A line cut short by a crash.
                }
            }
        }
    }

    /**
     * The key of a battle played on this worker by a subject whose jar has this SHA-256 (the first of the battle's
     * robots), or empty if the battle is not to be cached.
     */
    public Optional<String> key(String worker, BattleSpec spec, String subjectJar) {
        return key(worker, spec, 0, subjectJar);
    }

    /** The same for a battle whose subject is robot {@code subjectIndex} (a melee battle's subject joins anywhere). */
    public Optional<String> key(String worker, BattleSpec spec, int subjectIndex, String subjectJar) {
        if (spec.record() || spec.debug()) {
            return Optional.empty();
        }
        Optional<Map<String, String>> environment = workers.environment(worker);
        if (environment.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("field", spec.width() + "x" + spec.height());
        fields.put("rounds", Integer.toString(spec.rounds()));
        fields.put("seed", Long.toString(spec.seed()));
        fields.put("starts", spec.startsSeed() == null ? "random" : "fixed " + spec.startsSeed());
        List<String> robots = spec.robots();
        for (int i = 0; i < robots.size(); i++) {
            Optional<String> jar = i == subjectIndex ? Optional.of(subjectJar) : workers.robotJar(worker, robots.get(i));
            if (jar.isEmpty()) {
                return Optional.empty();
            }
            fields.put("robot " + i, robots.get(i));
            fields.put("jar " + i, jar.get());
        }
        fields.put("worker", worker);
        new TreeMap<>(environment.get()).forEach((k, v) -> fields.put("worker " + k, v));
        StringBuilder text = new StringBuilder(VERSION);
        fields.forEach((k, v) -> text.append('\n').append(k).append('=').append(v));
        return Optional.of(sha256(text.toString()));
    }

    public Optional<Map<String, Object>> get(String key) {
        return Optional.ofNullable(outcomes.get(key));
    }

    /** Claims a battle a job is about to play; false if another job is playing it now. */
    public boolean claim(String key) {
        return claimed.add(key);
    }

    /** Waits for a battle another job has claimed. */
    public void await(String key, Waiter waiter) {
        waiting.computeIfAbsent(key, k -> new ArrayList<>()).add(waiter);
    }

    /** The claimed battle will not come back: the jobs waiting for it are told to play it themselves. */
    public void release(String key) throws IOException {
        claimed.remove(key);
        notify(waiting.remove(key), Optional.empty());
    }

    /**
     * Keeps a battle's outcome, in memory and in the file, and hands it to the jobs waiting for it. The outcome is
     * kept before any waiter hears of it, so a waiter that fails does not cost the others or the cache the battle.
     */
    public void put(String key, BattleSpec spec, Map<String, Object> outcome) throws IOException {
        claimed.remove(key);
        List<Waiter> waiters = waiting.remove(key);
        try {
            if (!outcomes.containsKey(key)) {
                outcomes.put(key, outcome);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("key", key);
                entry.put("battle", String.join(" vs ", spec.robots()) + ", seed " + spec.seed());
                entry.put("outcome", outcome);
                append(Json.line(entry));
            }
        } finally {
            notify(waiters, Optional.of(outcome));
        }
    }

    /** Tells every waiter, also after one of them failed; the first failure is thrown at the end. */
    private static void notify(List<Waiter> waiters, Optional<Map<String, Object>> outcome) throws IOException {
        if (waiters == null) {
            return;
        }
        IOException failure = null;
        for (Waiter w : waiters) {
            try {
                w.done(outcome);
            } catch (IOException | RuntimeException e) {
                IOException wrapped = e instanceof IOException io ? io : new IOException(e);
                if (failure == null) {
                    failure = wrapped;
                } else {
                    failure.addSuppressed(wrapped);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Appends one line. A line cut short by a crash has no newline at its end, so a newline goes first then: the
     * next entry gets a line of its own instead of being lost with the broken one.
     */
    private void append(String line) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        String text = line + "\n";
        if (Files.exists(file) && !endsWithNewline(file)) {
            text = "\n" + text;
        }
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static boolean endsWithNewline(Path file) throws IOException {
        long size = Files.size(file);
        if (size == 0) {
            return true;
        }
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            f.seek(size - 1);
            return f.read() == '\n';
        }
    }

    public int size() {
        return outcomes.size();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
