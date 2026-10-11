package cz.libors.harness.queue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * A job in the queue, kept in a directory of its own under the queue's {@code jobs/}: {@code job.properties} (what
 * was submitted, never changed), {@code status.properties} (state, times, order) and whatever the job writes. The
 * server calls a job only while holding its lock, so jobs need no locking of their own.
 */
public abstract class QueueJob {

    public enum State {
        QUEUED, RUNNING, DONE, CANCELLED, FAILED;

        public boolean active() {
            return this == QUEUED || this == RUNNING;
        }
    }

    /** One battle handed to a worker. The pair keeps both of its battles on one worker. */
    public record Work(QueueJob job, String key, BattleSpec spec, long timeoutSeconds) {
    }

    protected final Path dir;
    protected final Properties spec;
    private final Properties status = new Properties();

    protected QueueJob(Path dir, Properties spec) throws IOException {
        this.dir = dir;
        this.spec = spec;
        try (InputStream in = Files.newInputStream(dir.resolve("status.properties"))) {
            status.load(in);
        }
    }

    public String id() {
        return dir.getFileName().toString();
    }

    public Path dir() {
        return dir;
    }

    public String type() {
        return spec.getProperty("type");
    }

    public String note() {
        return spec.getProperty("note", "");
    }

    public State state() {
        return State.valueOf(status.getProperty("state"));
    }

    public double order() {
        return Double.parseDouble(status.getProperty("order", "0"));
    }

    public long submittedAt() {
        return Long.parseLong(spec.getProperty("submittedAt"));
    }

    public long startedAt() {
        return Long.parseLong(status.getProperty("startedAt", "0"));
    }

    public long finishedAt() {
        return Long.parseLong(status.getProperty("finishedAt", "0"));
    }

    public String reason() {
        return status.getProperty("reason", "");
    }

    void setOrder(double order) throws IOException {
        status.setProperty("order", Double.toString(order));
        saveStatus();
    }

    /** Marks the job started when its first battle goes out, or when it first takes a battle from the cache. */
    protected void started() throws IOException {
        if (state() == State.QUEUED) {
            status.setProperty("state", State.RUNNING.name());
            status.setProperty("startedAt", Long.toString(System.currentTimeMillis()));
            saveStatus();
        }
    }

    /** Ends the job; {@code results} are kept in its status, for the dashboard after a restart. */
    protected void finish(State end, String reason, Map<String, String> results) throws IOException {
        status.setProperty("state", end.name());
        status.setProperty("finishedAt", Long.toString(System.currentTimeMillis()));
        status.setProperty("reason", reason);
        results.forEach((k, v) -> status.setProperty("result." + k, v));
        saveStatus();
    }

    /** A result kept by {@link #finish}, or null. */
    protected String result(String key) {
        return status.getProperty("result." + key);
    }

    private void saveStatus() throws IOException {
        Path tmp = dir.resolve("status.properties.tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            status.store(out, "queue job status");
        }
        Files.move(tmp, dir.resolve("status.properties"), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /** The jar files (in the queue's {@code jars/}) and their SHA-256 that a worker needs for this job. */
    public abstract Map<String, String> jars();

    /** The robots and teams the job's battles use besides its own jars; a worker must have them all. */
    public abstract List<String> opponents();

    /** Whether some opponent is foreign, so that only workers allowed to run foreign code may take the job. */
    public abstract boolean needsForeign();

    /**
     * The next unit of work for this worker: both battles of a pair, a battle to retry, or nothing for now (the job
     * waits for results, or is finished).
     */
    public abstract List<Work> next(String worker) throws IOException;

    /** Whether this job's battles run in the engine's debug mode; a worker plays one mode at a time. */
    public boolean debug() {
        return false;
    }

    public abstract void completed(Work work, Map<String, Object> outcome, String worker) throws IOException;

    /** A battle failed on the worker (an exception, a crash, a timeout); the job may retry it there or give up. */
    public abstract void failed(Work work, String reason, String worker) throws IOException;

    /** The worker went away before the battle ended. */
    public abstract void lost(Work work) throws IOException;

    /** The worker went away; called after {@link #lost} for each of its battles. */
    public abstract void workerLost(String worker) throws IOException;

    public abstract void cancel() throws IOException;

    /** Ends the job as failed, e.g. when no worker can run it. */
    public void fail(String reason) throws IOException {
        if (state().active()) {
            finish(State.FAILED, reason, Map.of());
        }
    }

    /** Battles handed out and not yet back. */
    public abstract int inFlight();

    /** A one-line description: what is compared with what, against what. */
    public abstract String title();

    /** For the dashboard's queue table. */
    public abstract Map<String, Object> progress();

    /** For the dashboard's job page: everything the job can say about itself now. */
    public abstract Object detail() throws IOException;

    /** The common fields of the dashboard's rows. */
    public Map<String, Object> row() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id());
        m.put("type", type());
        m.put("title", title());
        m.put("note", note());
        m.put("state", state().name());
        m.put("reason", reason());
        m.put("order", order());
        m.put("submittedAt", submittedAt());
        m.put("startedAt", startedAt());
        m.put("finishedAt", finishedAt());
        m.put("progress", progress());
        return m;
    }

    /** Reads a job directory written by {@link Submit}. */
    static QueueJob open(Path dir) throws IOException {
        Properties spec = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve("job.properties"))) {
            spec.load(in);
        }
        String type = spec.getProperty("type");
        if ("compare".equals(type)) {
            return cz.libors.harness.compare.CompareJob.open(dir, spec);
        }
        if ("record".equals(type)) {
            return cz.libors.harness.compare.RecordJob.open(dir, spec);
        }
        throw new IOException(dir + ": unknown job type " + type);
    }
}
