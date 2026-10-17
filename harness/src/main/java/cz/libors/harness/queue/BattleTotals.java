package cz.libors.harness.queue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Every battle a worker has played to its end, over all the jobs on disk: how many and their time in a slot, per
 * battle format (the format line of the job's opponent set: 1v1, team, ...) and per kind of job (comparisons,
 * recordings), how many each worker played, and per format how they added up over time. The jobs'
 * {@code dispatch.tsv} files are read once when the server starts, and the server adds each battle it logs there, so
 * the totals survive a restart. Battles a job took from the battle cache are in no dispatch log, so every battle
 * counts once; a deleted job takes its battles with it.
 */
final class BattleTotals {

    private static final int FINISHED = 0;
    private static final int WORKER = 1;
    private static final int BATTLE_SECONDS = 5;
    private static final int OUTCOME = 6;

    private static final DateTimeFormatter FINISHED_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Format, then kind of job: battles and seconds. */
    private final Map<String, Map<String, double[]>> totals = new TreeMap<>();
    /** Format, then the minute the battles ended in (epoch milliseconds at its start): battles and seconds. */
    private final Map<String, TreeMap<Long, double[]>> minutes = new TreeMap<>();
    /** Battles per worker. */
    private final Map<String, Long> workers = new TreeMap<>();
    /** Per job directory: its format and kind, read once. */
    private final Map<Path, String[]> jobs = new HashMap<>();
    /** When the earliest battle counted ended, as {@code dispatch.tsv} writes it; empty before the first. */
    private String since = "";

    /** Reads the dispatch logs of every job directory under {@code jobsDir}. */
    static BattleTotals read(Path jobsDir) throws IOException {
        BattleTotals t = new BattleTotals();
        List<Path> dirs;
        try (Stream<Path> list = Files.list(jobsDir)) {
            dirs = list.filter(Files::isDirectory).sorted().toList();
        }
        for (Path dir : dirs) {
            Path log = dir.resolve("dispatch.tsv");
            if (!Files.exists(log)) {
                continue;
            }
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length <= OUTCOME || f[FINISHED].equals("finished") || !f[OUTCOME].startsWith("ok")) {
                    continue;
                }
                t.add(dir, f[WORKER], seconds(f[BATTLE_SECONDS]), f[FINISHED]);
            }
        }
        return t;
    }

    /** A battle of the job in {@code jobDir} that {@code worker} ended at {@code finished}, after {@code seconds}. */
    synchronized void add(Path jobDir, String worker, double seconds, String finished) {
        workers.merge(worker, 1L, Long::sum);
        String[] job = jobs.computeIfAbsent(jobDir, BattleTotals::describe);
        double[] t = totals.computeIfAbsent(job[0], k -> new TreeMap<>()).computeIfAbsent(job[1], k -> new double[2]);
        t[0]++;
        t[1] += seconds;
        if (since.isEmpty() || finished.compareTo(since) < 0) {
            since = finished;
        }
        try {
            long millis = LocalDateTime.parse(finished, FINISHED_TIME).atZone(ZoneId.systemDefault()).toInstant()
                    .toEpochMilli();
            double[] m = minutes.computeIfAbsent(job[0], k -> new TreeMap<>())
                    .computeIfAbsent(millis - Math.floorMod(millis, 60_000L), k -> new double[2]);
            m[0]++;
            m[1] += seconds;
        } catch (DateTimeParseException e) {
            // counted in the totals, but at no time
        }
    }

    /**
     * The battles of a format and their slot seconds as they added up: at the end of each minute in which some
     * ended, all that had ended by then.
     *
     * @param minuteEnds epoch milliseconds, ascending
     */
    record Timeline(long[] minuteEnds, long[] battles, double[] seconds) {

        static final Timeline EMPTY = new Timeline(new long[0], new long[0], new double[0]);

        /** How many of the battles had ended at {@code millis} (to the minute), and the seconds they took. */
        long battlesAt(long millis) {
            int i = index(millis);
            return i < 0 ? 0 : battles[i];
        }

        double secondsAt(long millis) {
            int i = index(millis);
            return i < 0 ? 0 : seconds[i];
        }

        /** The last minute that ended by {@code millis}, counting the minute {@code millis} is in; -1 for none. */
        private int index(long millis) {
            int i = Arrays.binarySearch(minuteEnds, millis - Math.floorMod(millis, 60_000L) + 60_000L);
            return i >= 0 ? i : -i - 2;
        }
    }

    /** The format's battles over time; empty for a format without battles. */
    synchronized Timeline timeline(String format) {
        TreeMap<Long, double[]> m = minutes.get(format);
        if (m == null) {
            return Timeline.EMPTY;
        }
        long[] ends = new long[m.size()];
        long[] battles = new long[m.size()];
        double[] seconds = new double[m.size()];
        int i = 0;
        long b = 0;
        double s = 0;
        for (Map.Entry<Long, double[]> e : m.entrySet()) {
            b += (long) e.getValue()[0];
            s += e.getValue()[1];
            ends[i] = e.getKey() + 60_000L;
            battles[i] = b;
            seconds[i] = s;
            i++;
        }
        return new Timeline(ends, battles, seconds);
    }

    /** The battles {@code worker} played over all jobs. */
    synchronized long battles(String worker) {
        return workers.getOrDefault(worker, 0L);
    }

    /** For the dashboard: the battles and slot hours in all, and per format with its kinds. */
    synchronized Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        long battles = 0;
        double seconds = 0;
        List<Object> formats = new ArrayList<>();
        for (Map.Entry<String, Map<String, double[]>> e : totals.entrySet()) {
            long fb = 0;
            double fs = 0;
            Map<String, Object> kinds = new LinkedHashMap<>();
            for (Map.Entry<String, double[]> k : e.getValue().entrySet()) {
                kinds.put(k.getKey(), (long) k.getValue()[0]);
                fb += (long) k.getValue()[0];
                fs += k.getValue()[1];
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("format", e.getKey());
            f.put("battles", fb);
            f.put("slotHours", fs / 3600);
            f.put("kinds", kinds);
            formats.add(f);
            battles += fb;
            seconds += fs;
        }
        m.put("since", since);
        m.put("battles", battles);
        m.put("slotHours", seconds / 3600);
        m.put("formats", formats);
        return m;
    }

    /** A battle's seconds in its slot as the dispatch log writes them; 0 when missing or unreadable. */
    private static double seconds(String field) {
        try {
            return field.isEmpty() ? 0 : Double.parseDouble(field);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A job's format and kind from its directory; "?" for what cannot be read. */
    private static String[] describe(Path dir) {
        String format = "?";
        try (Stream<String> lines = Files.lines(dir.resolve("opponents.txt"), StandardCharsets.UTF_8)) {
            format = lines.map(String::strip).filter(l -> l.startsWith("format ")).map(l -> l.substring(7).strip())
                    .findFirst().orElse("?");
        } catch (IOException | RuntimeException e) {
            // an unreadable set counts as "?"
        }
        String type = null;
        Properties spec = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve("job.properties"))) {
            spec.load(in);
            type = spec.getProperty("type");
        } catch (IOException e) {
            // an unreadable job counts as "?"
        }
        String kind = "compare".equals(type) ? "comparisons" : "record".equals(type) ? "recordings"
                : type == null ? "?" : type;
        return new String[] {format, kind};
    }
}
