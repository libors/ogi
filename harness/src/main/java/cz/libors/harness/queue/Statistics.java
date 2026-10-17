package cz.libors.harness.queue;

import cz.libors.harness.Json;
import cz.libors.harness.compare.OpponentSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The statistics page's numbers ({@code /api/stats}), per battle format: the jobs submitted, the battles the workers
 * played and their slot hours, the battles taken from the battle cache, and the APS of the versions each comparison
 * measured against the rumble, at the battles the format had played when the job ended.
 *
 * <p><b>Which comparisons measure the rumble.</b> A set stands for as many robots of the rumble as its weights add
 * up to, or without weights as it has opponents ({@link OpponentSet#standsFor}). The largest such number among a
 * format's jobs is taken as the whole rumble, and a set that stands for at least {@link #RUMBLE_SHARE} of it
 * measures the rumble's APS: the whole rumble, or a sample of it whose groups weigh as many robots as they stand for.
 * A subset (the strong bots, some groups, bots picked by a trait) stands for less: its APS says how we do against
 * those bots, not where we stand. The margin lets the sets of a newer ranking with a few more robots keep the older
 * ones in; a subset leaves out at least a whole group (the 1v1 rumble's smallest, its top 30, is 2.5 % of it).
 *
 * <p>Only comparisons that ended normally count: a cancelled or failed one may not have met every opponent. A job's
 * set and its result never change once it has ended, so both are read once.
 */
final class Statistics {

    static final double RUMBLE_SHARE = 0.99;
    /** A set with fewer opponents than this share of the robots it stands for is a sample of the rumble. */
    static final double SAMPLE_SHARE = 0.5;

    /** A job as the server sees it, taken under the server's lock. */
    record Job(String id, Path dir, String type, String format, String state, long submittedAt, long finishedAt,
               String note, Map<String, Object> progress) {

        static Job of(QueueJob j) {
            return new Job(j.id(), j.dir(), j.type(), j.format(), j.state().name(), j.submittedAt(), j.finishedAt(),
                    j.note(), j.progress());
        }

        boolean comparison() {
            return "compare".equals(type);
        }
    }

    /** What a job's copy of its opponent set says about it; {@link #UNREADABLE} when it cannot be read. */
    private record SetFacts(String name, String title, double standsFor, int opponents) {
        static final SetFacts UNREADABLE = new SetFacts("?", "", 0, 0);
    }

    /** Both versions' APS on the scoreboard that estimates the rumble's, and the change between them. */
    private record Measure(String board, double apsBaseline, double apsCandidate, double difference,
                           double halfWidth) {
        static final Measure NONE = new Measure("", Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    private final Map<String, SetFacts> sets = new HashMap<>();
    private final Map<String, Measure> measures = new HashMap<>();

    synchronized Map<String, Object> toMap(List<Job> jobs, BattleTotals totals) {
        Map<String, List<Job>> byFormat = new TreeMap<>();
        for (Job j : jobs) {
            byFormat.computeIfAbsent(j.format(), k -> new ArrayList<>()).add(j);
        }
        Map<String, Map<String, Object>> played = new HashMap<>();
        for (Object o : (List<?>) totals.toMap().get("formats")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> f = (Map<String, Object>) o;
            played.put((String) f.get("format"), f);
            byFormat.putIfAbsent((String) f.get("format"), List.of());
        }
        List<Object> formats = new ArrayList<>();
        for (Map.Entry<String, List<Job>> e : byFormat.entrySet()) {
            formats.add(format(e.getKey(), e.getValue(), played.get(e.getKey()), totals.timeline(e.getKey())));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("now", System.currentTimeMillis());
        m.put("rumbleShare", RUMBLE_SHARE);
        m.put("formats", formats);
        return m;
    }

    private Map<String, Object> format(String format, List<Job> jobs, Map<String, Object> played,
                                       BattleTotals.Timeline timeline) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", format);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("submitted", jobs.size());
        counts.put("comparisons", jobs.stream().filter(Job::comparison).count());
        counts.put("recordings", jobs.stream().filter(j -> "record".equals(j.type())).count());
        Map<String, Long> states = new TreeMap<>();
        jobs.forEach(j -> states.merge(j.state(), 1L, Long::sum));
        counts.put("states", states);
        counts.put("firstSubmitted", jobs.stream().mapToLong(Job::submittedAt).min().orElse(0));
        counts.put("lastSubmitted", jobs.stream().mapToLong(Job::submittedAt).max().orElse(0));
        m.put("jobs", counts);

        Map<String, Object> battles = new LinkedHashMap<>();
        battles.put("played", played == null ? 0L : played.get("battles"));
        battles.put("slotHours", played == null ? 0.0 : played.get("slotHours"));
        battles.put("kinds", played == null ? Map.of() : played.get("kinds"));
        battles.put("reused", jobs.stream().mapToLong(j -> number(j.progress().get("reusedBattles"))).sum());
        battles.put("firstEnded", timeline.minuteEnds().length == 0 ? 0 : timeline.minuteEnds()[0] - 60_000L);
        m.put("battles", battles);

        m.put("rumble", rumble(jobs, timeline));
        return m;
    }

    /** The sets of the format's jobs, which of them measure the rumble, and what those comparisons measured. */
    private Map<String, Object> rumble(List<Job> jobs, BattleTotals.Timeline timeline) {
        Map<String, SetFacts> facts = new HashMap<>();
        double whole = 0;
        for (Job j : jobs) {
            SetFacts f = sets.computeIfAbsent(j.id(), id -> readSet(j));
            facts.put(j.id(), f);
            whole = Math.max(whole, f.standsFor());
        }
        final double rumbleSize = whole;

        // One row per set as the jobs played it: a set whose file changed shows once per version.
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        List<Job> measured = new ArrayList<>();
        for (Job j : jobs.stream().sorted(Comparator.comparingLong(Job::submittedAt)).toList()) {
            SetFacts f = facts.get(j.id());
            boolean rumble = f != SetFacts.UNREADABLE && f.standsFor() >= RUMBLE_SHARE * rumbleSize;
            Map<String, Object> row = rows.computeIfAbsent(f.name() + "\t" + f.standsFor() + "\t" + f.opponents(),
                    k -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("set", f.name());
                        r.put("title", f.title());
                        r.put("opponents", f.opponents());
                        r.put("standsFor", f.standsFor());
                        r.put("measuresRumble", rumble);
                        r.put("sample", rumble && f.opponents() < SAMPLE_SHARE * f.standsFor());
                        r.put("jobs", 0L);
                        r.put("comparisons", 0L);
                        r.put("plotted", 0L);
                        return r;
                    });
            row.merge("jobs", 1L, (a, b) -> (long) a + (long) b);
            if (j.comparison()) {
                row.merge("comparisons", 1L, (a, b) -> (long) a + (long) b);
                if (rumble && j.state().equals(QueueJob.State.DONE.name())
                        && measures.computeIfAbsent(j.id(), id -> readMeasure(j)) != Measure.NONE) {
                    row.merge("plotted", 1L, (a, b) -> (long) a + (long) b);
                    measured.add(j);
                }
            }
        }

        measured.sort(Comparator.comparingLong(Job::finishedAt).thenComparing(Job::id));
        List<Object> points = new ArrayList<>();
        Map<String, Set<String>> baselinesSeen = new HashMap<>();
        Map<String, String> currentBaseline = new HashMap<>();
        for (Job j : measured) {
            SetFacts f = facts.get(j.id());
            Measure measure = measures.get(j.id());
            String baseline = String.valueOf(j.progress().get("baseline"));
            // The line follows the version the jobs compare against as it changes; a job that goes back to an older
            // version is a dot, not a step back.
            boolean line = baselinesSeen.computeIfAbsent(f.name(), k -> new HashSet<>()).add(baseline)
                    || baseline.equals(currentBaseline.get(f.name()));
            if (line) {
                currentBaseline.put(f.name(), baseline);
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", j.id());
            p.put("note", j.note());
            p.put("finishedAt", j.finishedAt());
            p.put("set", f.name());
            p.put("baseline", baseline);
            p.put("candidate", String.valueOf(j.progress().get("candidate")));
            p.put("board", measure.board());
            p.put("apsBaseline", measure.apsBaseline());
            p.put("apsCandidate", measure.apsCandidate());
            p.put("difference", measure.difference());
            p.put("halfWidth", measure.halfWidth());
            p.put("battles", timeline.battlesAt(j.finishedAt()));
            p.put("slotHours", timeline.secondsAt(j.finishedAt()) / 3600);
            p.put("line", line);
            points.add(p);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("standsFor", rumbleSize);
        m.put("sets", new ArrayList<>(rows.values()));
        m.put("points", points);
        return m;
    }

    private static SetFacts readSet(Job j) {
        try {
            String name = String.valueOf(j.progress().getOrDefault("opponentSet", j.dir().getFileName()));
            OpponentSet set = OpponentSet.parse(name,
                    Files.readAllLines(j.dir().resolve("opponents.txt"), StandardCharsets.UTF_8));
            return new SetFacts(set.name(), set.title(), set.standsFor(), set.opponents().size());
        } catch (IOException | RuntimeException e) {
            return SetFacts.UNREADABLE;
        }
    }

    /**
     * The scoreboard that estimates the rumble's APS in the job's {@code summary.json}: the rumble estimate of a
     * weighted 1v1 or team set, otherwise all opponents (each weighing the same, as the rumble weighs them; a melee
     * comparison's draws give every group its share).
     */
    @SuppressWarnings("unchecked")
    private static Measure readMeasure(Job j) {
        try {
            Map<String, Object> summary = Json.parseObject(Files.readString(j.dir().resolve("summary.json")));
            Map<String, Object> board = null;
            for (Object o : (List<Object>) summary.get("scoreboards")) {
                Map<String, Object> b = (Map<String, Object>) o;
                if (b.get("name").equals("rumble") || b.get("name").equals("all") && board == null) {
                    board = b;
                }
            }
            if (board == null || !(board.get("apsBaseline") instanceof Number)
                    || !(board.get("apsCandidate") instanceof Number)) {
                return Measure.NONE;
            }
            return new Measure((String) board.get("name"), real(board.get("apsBaseline")),
                    real(board.get("apsCandidate")), real(board.get("difference")), real(board.get("halfWidth")));
        } catch (IOException | RuntimeException e) {
            return Measure.NONE;
        }
    }

    private static double real(Object v) {
        return v instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static long number(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }
}
