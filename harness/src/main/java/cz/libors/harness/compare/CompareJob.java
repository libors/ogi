package cz.libors.harness.compare;

import cz.libors.harness.BattleOutcome;
import cz.libors.harness.Json;
import cz.libors.harness.compare.Compare.Measure;
import cz.libors.harness.compare.Compare.Subject;
import cz.libors.harness.compare.Compare.Summary;
import cz.libors.harness.compare.CompareLog.Battle;
import cz.libors.harness.compare.CompareLog.Side;
import cz.libors.harness.compare.OpponentSet.Format;
import cz.libors.harness.compare.Sprt.Bounds;
import cz.libors.harness.compare.Sprt.Decision;
import cz.libors.harness.compare.Sprt.Estimate;
import cz.libors.harness.compare.Stratum.Metric;
import cz.libors.harness.queue.BattleSpec;
import cz.libors.harness.queue.QueueJob;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * A comparison run by the queue: the pairs, seeds, log, test and summary of {@link Compare}, with pairs handed out to
 * many slots at once instead of one after the other.
 *
 * <ul>
 *   <li>A pair's two battles go to one worker (D-016), to any of its slots.</li>
 *   <li>The next opponent is chosen as in {@link Compare}, counting the pairs still being played as played, so that
 *       pairs handed out at once spread over the opponents like pairs handed out one by one.</li>
 *   <li>The test is checked after every complete pair. Once it decides, no more pairs go out; battles still being
 *       played are logged when they come back, but the summary is the one at the decision.</li>
 *   <li>A failed battle is retried on the same worker, twice; a third failure fails the job. A pair whose worker
 *       went away is dropped (its battles that came back stay in the log as an incomplete pair, which the test
 *       ignores), and a new pair takes its place.</li>
 *   <li>The log is {@link Compare}'s, keyed by the same configuration, so the server resumes a job after a restart
 *       from its complete pairs.</li>
 * </ul>
 */
public final class CompareJob extends QueueJob {

    private static final int ATTEMPTS = 3;
    private static final long RATE_WINDOW_MILLIS = 120_000;

    private final OpponentSet set;
    private final Format format;
    private final int rounds;
    private final boolean fixedStarts;
    private final long runSeed;
    private final Bounds bounds;
    private final String metric;
    private final Measure measure;
    private final int maxPairs;
    private final int pairsPerOpponent;
    private final long timeoutSeconds;
    private final Subject baseline;
    private final Subject candidate;
    private final Map<String, String> config;
    private final Map<String, String> jars = new LinkedHashMap<>();
    private final List<Stratum> strata = new ArrayList<>();
    private final int[] pendingPairs;
    private final Map<String, Integer> nextPair = new HashMap<>();
    private final Map<String, PairState> pairs = new LinkedHashMap<>();
    private final Map<String, Deque<Work>> retries = new HashMap<>();
    private final Deque<Long> completions = new ArrayDeque<>();
    private CompareLog log;
    private int outstanding;
    private int battles;
    private int failures;
    private int problems;
    private int skippedSubject;
    private int skippedOpponents;
    private Supplier<Map<String, String>> environment = Map::of;

    /** A pair handed out and not yet complete. */
    private static final class PairState {
        final int stratum;
        final String worker;
        final Battle[] done = new Battle[2];
        final int[] attempts = new int[2];

        PairState(int stratum, String worker) {
            this.stratum = stratum;
            this.worker = worker;
        }
    }

    private CompareJob(Path dir, Properties spec) throws IOException {
        super(dir, spec);
        set = OpponentSet.parse(spec.getProperty("opponentSet"), Files.readAllLines(dir.resolve("opponents.txt")));
        format = set.format();
        int roundsOption = Integer.parseInt(spec.getProperty("rounds", "0"));
        rounds = roundsOption > 0 ? roundsOption : format.rounds;
        fixedStarts = Compare.fixedStarts(spec.getProperty("starts", "random"));
        runSeed = Long.parseLong(spec.getProperty("seed", "1"));
        bounds = Bounds.parse(spec.getProperty("bounds", "gainer"));
        metric = spec.getProperty("metric", "score");
        measure = Measure.parse(metric);
        maxPairs = Integer.parseInt(spec.getProperty("maxPairs", "1000"));
        pairsPerOpponent = Integer.parseInt(spec.getProperty("pairsPerOpponent", "0"));
        timeoutSeconds = Long.parseLong(spec.getProperty("timeout", "900"));
        spec.stringPropertyNames().stream().filter(k -> k.startsWith("jar.")).sorted()
                .forEach(k -> jars.put(k.substring(4), spec.getProperty(k)));
        String subjectClass = format == Format.TEAM ? spec.getProperty("team") : spec.getProperty("robot");
        baseline = subject(Side.BASELINE, subjectClass, spec.getProperty("baseline"));
        candidate = subject(Side.CANDIDATE, subjectClass, spec.getProperty("candidate"));
        config = Compare.config(baseline, candidate, set, rounds, fixedStarts, runSeed);
        set.opponents().forEach(o -> strata.add(new Stratum(o.name(), o.strong())));
        pendingPairs = new int[strata.size()];
        if (state().active()) {
            log = CompareLog.open(dir.resolve("battles.tsv"), config);
            for (Battle b : log.battles()) {
                nextPair.merge(b.opponent(), b.pair() + 1, Math::max);
                count(b);
            }
            log.completePairs().forEach((opponent, list) -> {
                Stratum s = strata.get(stratum(opponent));
                list.forEach(p -> s.add(measure.of(p[0]), measure.of(p[1])));
            });
        }
    }

    public static CompareJob open(Path dir, Properties spec) throws IOException {
        return new CompareJob(dir, spec);
    }

    /**
     * Checks a comparison before it is queued and describes it; throws {@link IllegalArgumentException} on a bad
     * option, a missing subject jar or equal versions.
     */
    public static String check(Properties spec, List<String> opponentLines) {
        OpponentSet set = OpponentSet.parse(spec.getProperty("opponentSet"), opponentLines);
        Bounds.parse(spec.getProperty("bounds", "gainer"));
        Measure.parse(spec.getProperty("metric", "score"));
        Compare.fixedStarts(spec.getProperty("starts", "random"));
        for (String key : List.of("seed", "rounds", "maxPairs", "pairsPerOpponent", "timeout")) {
            if (spec.getProperty(key) != null) {
                Long.parseLong(spec.getProperty(key));
            }
        }
        String subjectClass = set.format() == Format.TEAM ? spec.getProperty("team") : spec.getProperty("robot");
        String baseline = spec.getProperty("baseline");
        String candidate = spec.getProperty("candidate");
        if (baseline.equals(candidate)) {
            throw new IllegalArgumentException("baseline and candidate are the same version: " + baseline);
        }
        for (String version : List.of(baseline, candidate)) {
            if (spec.getProperty("jar." + subjectClass + "_" + version + ".jar") == null) {
                throw new IllegalArgumentException("no jar " + subjectClass + "_" + version + ".jar in the job");
            }
        }
        return String.format(Locale.ROOT, "%s %s vs %s on %s (%d opponents, %s)", subjectClass, candidate, baseline,
                set.name(), set.opponents().size(), set.format().label);
    }

    private Subject subject(Side side, String subjectClass, String version) throws IOException {
        String jar = subjectClass + "_" + version + ".jar";
        String sha = jars.get(jar);
        if (sha == null) {
            throw new IOException(dir + ": the job has no " + jar);
        }
        return new Subject(side, subjectClass + " " + version, spec.getProperty("robot") + " " + version, sha);
    }

    /** What the worker descriptions in the summary come from. */
    public void environment(Supplier<Map<String, String>> supplier) {
        environment = supplier;
    }

    @Override
    public Map<String, String> jars() {
        return jars;
    }

    @Override
    public List<String> opponents() {
        return set.opponents().stream().map(OpponentSet.Opponent::name).toList();
    }

    @Override
    public boolean needsForeign() {
        return !cz.libors.harness.Sandbox.foreign(opponents()).isEmpty();
    }

    @Override
    public String title() {
        return String.format(Locale.ROOT, "%s vs %s · %s · %s %s", candidate.name(), spec.getProperty("baseline"),
                set.name(), metric, pairsPerOpponent > 0 ? pairsPerOpponent + " pairs/opponent"
                        : spec.getProperty("bounds", "gainer") + " " + bounds);
    }

    @Override
    public List<Work> next(String worker) {
        if (!state().active()) {
            return List.of();
        }
        Deque<Work> retry = retries.get(worker);
        if (retry != null && !retry.isEmpty()) {
            outstanding++;
            return List.of(retry.poll());
        }
        int h;
        if (pairsPerOpponent > 0) {
            h = Compare.leastPlayed(strata, pendingPairs, pairsPerOpponent);
        } else {
            if (Sprt.decide(strata, bounds) != Decision.CONTINUE || Compare.total(strata) + pairs.size() >= maxPairs) {
                return List.of();
            }
            h = Sprt.next(strata, pendingPairs);
        }
        if (h < 0) {
            return List.of();
        }
        String opponent = strata.get(h).opponent();
        int pair = nextPair.merge(opponent, 1, Integer::sum) - 1;
        long seed = Compare.pairSeed(runSeed, opponent, pair);
        pairs.put(pairKey(opponent, pair), new PairState(h, worker));
        pendingPairs[h]++;
        // Which version goes first alternates, as in Compare; with many slots both usually run at once.
        List<Subject> order = pair % 2 == 0 ? List.of(baseline, candidate) : List.of(candidate, baseline);
        List<Work> works = new ArrayList<>();
        for (Subject subject : order) {
            BattleSpec battle = new BattleSpec(format.label + " vs " + opponent, format.width, format.height, rounds,
                    List.of(subject.name(), opponent), seed, fixedStarts ? Compare.mix(seed) : null);
            works.add(new Work(this, opponent + "\t" + pair + "\t" + subject.side(), battle, timeoutSeconds));
        }
        outstanding += works.size();
        return works;
    }

    @Override
    public void completed(Work work, Map<String, Object> outcomeMap, String worker) throws IOException {
        outstanding--;
        String[] key = work.key().split("\t");
        String opponent = key[0];
        int pair = Integer.parseInt(key[1]);
        Side side = Side.valueOf(key[2]);
        BattleOutcome outcome = work.spec().outcomeFromMap(outcomeMap);
        Battle battle = Compare.battle(outcome, side == Side.BASELINE ? baseline : candidate, opponent, pair);
        if (log != null) {
            log.append(battle);
        }
        count(battle);
        long now = System.currentTimeMillis();
        completions.add(now);
        while (!completions.isEmpty() && completions.peek() < now - RATE_WINDOW_MILLIS) {
            completions.poll();
        }
        if (!outcome.problems().isEmpty()) {
            problems(outcome.problems().stream()
                    .map(p -> side + " " + opponent + " pair " + pair + " (" + worker + "): " + p).toList());
        }
        PairState p = pairs.get(pairKey(opponent, pair));
        if (p == null || !state().active()) {
            return;
        }
        p.done[side.ordinal()] = battle;
        if (p.done[0] != null && p.done[1] != null) {
            pairs.remove(pairKey(opponent, pair));
            pendingPairs[p.stratum]--;
            strata.get(p.stratum).add(measure.of(p.done[0]), measure.of(p.done[1]));
            checkEnd();
        }
    }

    @Override
    public void failed(Work work, String reason, String worker) throws IOException {
        outstanding--;
        failures++;
        String[] key = work.key().split("\t");
        problems(List.of(key[2] + " " + key[0] + " pair " + key[1] + " (" + worker + ") failed: " + reason));
        PairState p = pairs.get(pairKey(key[0], Integer.parseInt(key[1])));
        if (p == null || !state().active()) {
            return;
        }
        int side = Side.valueOf(key[2]).ordinal();
        if (++p.attempts[side] < ATTEMPTS) {
            retries.computeIfAbsent(worker, k -> new ArrayDeque<>()).add(work);
        } else {
            end(State.FAILED, key[2] + " " + key[0] + " pair " + key[1] + " failed " + ATTEMPTS + " times on "
                    + worker + ": " + reason);
        }
    }

    @Override
    public void lost(Work work) {
        outstanding--;
    }

    @Override
    public void workerLost(String worker) {
        retries.remove(worker);
        pairs.entrySet().removeIf(e -> {
            if (!e.getValue().worker.equals(worker)) {
                return false;
            }
            pendingPairs[e.getValue().stratum]--;
            return true;
        });
    }

    @Override
    public void cancel() throws IOException {
        if (state().active()) {
            end(State.CANCELLED, "cancelled");
        }
    }

    @Override
    public void fail(String reason) throws IOException {
        if (state().active()) {
            end(State.FAILED, reason);
        }
    }

    @Override
    public int inFlight() {
        return outstanding;
    }

    private void checkEnd() throws IOException {
        int total = Compare.total(strata);
        if (pairsPerOpponent == 0) {
            Decision decision = Sprt.decide(strata, bounds);
            if (decision != Decision.CONTINUE) {
                end(State.DONE, Compare.describe(decision, bounds));
            } else if (total >= maxPairs && pairs.isEmpty()) {
                end(State.DONE, "undecided after " + total + " pairs (maxPairs)");
            }
        } else if (pairs.isEmpty() && Compare.leastPlayed(strata, pendingPairs, pairsPerOpponent) < 0) {
            end(State.DONE, pairsPerOpponent + " pairs per opponent");
        }
    }

    private void end(State state, String reason) throws IOException {
        retries.clear();
        Summary summary = summary();
        Files.writeString(dir.resolve("summary.json"), Json.write(summary));
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(text, true, StandardCharsets.UTF_8)) {
            Compare.printReport(summary, out);
        }
        Files.writeString(dir.resolve("summary.txt"), text.toString(StandardCharsets.UTF_8));
        Map<String, Object> progress = progress();
        Map<String, String> results = new LinkedHashMap<>();
        progress.forEach((k, v) -> {
            if (v != null && !k.equals("remainingPairs") && !k.equals("etaSeconds") && !k.equals("battlesPerSecond")
                    && !k.equals("pendingPairs")) {
                results.put(k, v.toString());
            }
        });
        finish(state, reason, results);
    }

    private Summary summary() {
        Map<String, String> env = new LinkedHashMap<>(environment.get());
        if (startedAt() > 0) {
            env.put("lastRunSeconds", Long.toString((System.currentTimeMillis() - startedAt()) / 1000));
        }
        return Compare.summarize(config, env, metric, bounds, strata, log);
    }

    @Override
    public Map<String, Object> progress() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", metric);
        m.put("bounds", pairsPerOpponent > 0 ? "fixed" : spec.getProperty("bounds", "gainer"));
        m.put("opponents", strata.size());
        if (!state().active() && log == null) {
            for (String key : List.of("pairs", "estimate", "halfWidth", "llr", "lower", "upper", "decision",
                    "battles", "failures", "problems", "skippedSubject", "skippedOpponents", "maxPairs")) {
                String v = result(key);
                m.put(key, v == null ? null : number(v));
            }
            return m;
        }
        int total = Compare.total(strata);
        Estimate d = Sprt.estimate(strata, Metric.DIFFERENCE, x -> true);
        m.put("pairs", total);
        m.put("pendingPairs", pairs.size());
        m.put("maxPairs", pairsPerOpponent > 0 ? pairsPerOpponent * strata.size() : maxPairs);
        m.put("estimate", finite(d.mean()));
        m.put("halfWidth", finite(d.halfWidth()));
        m.put("llr", pairsPerOpponent > 0 ? null : finite(Sprt.llr(d, bounds)));
        m.put("lower", bounds.lower());
        m.put("upper", bounds.upper());
        m.put("decision", pairsPerOpponent > 0 ? null : Compare.describe(Sprt.decide(strata, bounds), bounds));
        m.put("battles", battles);
        m.put("failures", failures);
        m.put("problems", problems);
        m.put("skippedSubject", skippedSubject);
        m.put("skippedOpponents", skippedOpponents);
        double rate = completions.size() < 2 ? Double.NaN
                : (completions.size() - 1) * 1000.0 / Math.max(1, completions.peekLast() - completions.peekFirst());
        m.put("battlesPerSecond", finite(rate));
        Double remaining = remainingPairs(total, d);
        m.put("remainingPairs", remaining);
        m.put("etaSeconds", remaining == null || !(rate > 0) ? null : 2 * remaining / rate);
        return m;
    }

    /**
     * Pairs still to play: exact in fixed mode; otherwise the Brownian approximation of the test's expected length at
     * the current estimate, less the pairs played, at most up to {@code maxPairs}. Rough while the estimate is.
     */
    private Double remainingPairs(int total, Estimate d) {
        if (pairsPerOpponent > 0) {
            return (double) Math.max(0, pairsPerOpponent * strata.size() - total);
        }
        if (!state().active()) {
            return 0.0;
        }
        if (!Double.isFinite(d.mean()) || !(d.variance() > 0) || total == 0) {
            return null;
        }
        double width = bounds.delta1() - bounds.delta0();
        double mean = Math.max(bounds.delta0() - width, Math.min(bounds.delta1() + width, d.mean()));
        double expected = Sprt.expectedPairs(Math.sqrt(d.variance() * total), bounds, mean);
        return Math.min(Math.max(0, expected - total), Math.max(0, maxPairs - total));
    }

    @Override
    public Object detail() throws IOException {
        Path file = dir.resolve("summary.json");
        if ((!state().active() || log == null) && Files.exists(file)) {
            return Json.parse(Files.readString(file));
        }
        return log == null ? Map.of() : summary();
    }

    private void count(Battle b) {
        battles++;
        skippedSubject += b.skippedTurns();
        skippedOpponents += b.opponentSkippedTurns();
        problems += b.problems();
    }

    private void problems(List<String> lines) throws IOException {
        Files.write(dir.resolve("problems.log"), lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private int stratum(String opponent) {
        for (int i = 0; i < strata.size(); i++) {
            if (strata.get(i).opponent().equals(opponent)) {
                return i;
            }
        }
        throw new IllegalStateException("not an opponent of the set: " + opponent);
    }

    private static String pairKey(String opponent, int pair) {
        return opponent + "\t" + pair;
    }

    private static Double finite(double v) {
        return Double.isFinite(v) ? v : null;
    }

    private static Object number(String v) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return v;
        }
    }
}
