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
import cz.libors.harness.queue.BattleCache;
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
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * A comparison run by the queue: the pairs, seeds, log, test and summary of {@link Compare}, with pairs handed out to
 * many slots at once instead of one after the other.
 *
 * <ul>
 *   <li>A pair's two battles go to one worker, to any of its slots.</li>
 *   <li>The next opponent is chosen as in {@link Compare}, counting the pairs still being played as played, so that
 *       pairs handed out at once spread over the opponents like pairs handed out one by one.</li>
 *   <li>The test is checked after every complete pair. Once it decides, no more pairs go out; battles still being
 *       played are logged when they come back, but the summary is the one at the decision.</li>
 *   <li>A failed battle is retried on the same worker, twice; a third failure fails the job. A pair whose worker
 *       went away is dropped (its battles that came back stay in the log as an incomplete pair, which the test
 *       ignores), and a new pair takes its place.</li>
 *   <li>The log is {@link Compare}'s, keyed by the same configuration, so the server resumes a job after a restart
 *       from its complete pairs.</li>
 *   <li>A battle the {@link BattleCache} already holds for the worker is not played: its outcome counts at once
 *       (unless {@code reuse=false}). One that another job is playing now is waited for. A battle played goes into
 *       the cache.</li>
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
    /** The versions' own turn times, by side, from the battles of this server run. */
    private final BattleOutcome.TurnTimes[] turnTimes = {BattleOutcome.TurnTimes.NONE, BattleOutcome.TurnTimes.NONE};
    private Supplier<Map<String, String>> environment = Map::of;
    private BattleCache cache;
    private final boolean reuse;
    /** The cache keys of the battles handed out, by work key. */
    private final Map<String, String> cacheKeys = new HashMap<>();
    /** Battles taken from the cache in this server run. */
    /** Battles taken from the cache in this server run, and battles counted in it (the log's earlier ones not). */
    private int reused;
    private int battlesThisRun;

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
        reuse = Boolean.parseBoolean(spec.getProperty("reuse", "true"));
        spec.stringPropertyNames().stream().filter(k -> k.startsWith("jar.")).sorted()
                .forEach(k -> jars.put(k.substring(4), spec.getProperty(k)));
        String subjectClass = format == Format.TEAM ? spec.getProperty("team") : spec.getProperty("robot");
        baseline = subject(Side.BASELINE, subjectClass, spec.getProperty("baseline"));
        candidate = subject(Side.CANDIDATE, subjectClass, spec.getProperty("candidate"));
        config = Compare.config(baseline, candidate, set, rounds, fixedStarts, runSeed);
        set.opponents().forEach(o -> strata.add(new Stratum(o.name(), o.strong(), o.group())));
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
        if (set.format() == Format.MELEE) {
            throw new IllegalArgumentException(set.name() + " is a melee set: its comparison is a MeleeJob");
        }
        if (spec.getProperty("draws") != null) {
            // Ignored here, it would queue a sequential test where a fixed number of battles was asked for.
            throw new IllegalArgumentException("draws are for melee sets; " + set.name()
                    + " has pairs: use pairsPerOpponent=n");
        }
        Bounds.parse(spec.getProperty("bounds", "gainer"));
        Measure.parse(spec.getProperty("metric", "score"));
        Compare.fixedStarts(spec.getProperty("starts", "random"));
        Long.parseLong(spec.getProperty("seed", "1"));
        Compare.intOption(spec, "rounds", 0, 0);
        int maxPairs = Compare.intOption(spec, "maxPairs", 1000, 1);
        int pairsPerOpponent = Compare.intOption(spec, "pairsPerOpponent", 0, 0);
        Compare.intOption(spec, "timeout", 900, 1);
        int burnIn = Sprt.BURN_IN_PAIRS * set.opponents().size();
        if (pairsPerOpponent == 0 && maxPairs < burnIn) {
            throw new IllegalArgumentException("maxPairs " + maxPairs + " is below the test's burn-in of "
                    + Sprt.BURN_IN_PAIRS + " pairs per opponent (" + burnIn + "): the test could never decide");
        }
        String subjectClass = set.format() == Format.TEAM ? spec.getProperty("team") : spec.getProperty("robot");
        String baseline = spec.getProperty("baseline");
        String candidate = spec.getProperty("candidate");
        if (subjectClass == null || baseline == null || candidate == null) {
            throw new IllegalArgumentException("a comparison needs its robot or team, a baseline and a candidate");
        }
        if (!cz.libors.harness.Sandbox.trusted(subjectClass + " " + baseline)) {
            // The subjects come with the job's jars; a foreign robot must not skip the worker's jar check.
            throw new IllegalArgumentException(subjectClass + " is not one of our robots");
        }
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

    /** Where battles already played come from, and where new ones go. */
    public void cache(BattleCache battles) {
        cache = battles;
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
    public String format() {
        return format.label;
    }

    @Override
    public String title() {
        return String.format(Locale.ROOT, "%s vs %s · %s · %s %s", candidate.name(), spec.getProperty("baseline"),
                set.name(), metric, pairsPerOpponent > 0 ? pairsPerOpponent + " pairs/opponent"
                        : spec.getProperty("bounds", "gainer") + " " + bounds);
    }

    @Override
    public List<Work> next(String worker) throws IOException {
        if (state().active() && pairs.isEmpty() && outstanding == 0) {
            // A job resumed with its end already reached (it stopped between the last battle and its end).
            checkEnd();
        }
        if (!state().active()) {
            return List.of();
        }
        Deque<Work> retry = retries.get(worker);
        if (retry != null && !retry.isEmpty()) {
            outstanding++;
            return List.of(retry.poll());
        }
        // A pair whose battles all come from the cache is complete at once; the next pair follows.
        while (state().active()) {
            int h;
            if (pairsPerOpponent > 0) {
                h = Compare.leastPlayed(strata, pendingPairs, pairsPerOpponent);
            } else {
                if (Sprt.decide(strata, bounds) != Decision.CONTINUE
                        || Compare.total(strata) + pairs.size() >= maxPairs) {
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
            Map<Work, Map<String, Object>> cached = new LinkedHashMap<>();
            for (Subject subject : order) {
                BattleSpec battle = new BattleSpec(format.label + " vs " + opponent, format.width, format.height,
                        rounds, List.of(subject.name(), opponent), seed, fixedStarts ? Compare.mix(seed) : null);
                Work work = new Work(this, opponent + "\t" + pair + "\t" + subject.side(), battle, timeoutSeconds);
                Optional<String> key = reuse && cache != null ? cache.key(worker, battle, subject.jarSha())
                        : Optional.empty();
                Optional<Map<String, Object>> hit = key.flatMap(k -> cache.get(k));
                if (hit.isPresent()) {
                    cached.put(work, hit.get());
                } else if (key.isPresent() && !cache.claim(key.get())) {
                    awaitOther(work, key.get(), worker);
                } else {
                    key.ifPresent(k -> cacheKeys.put(work.key(), k));
                    works.add(work);
                }
            }
            outstanding += works.size() + cached.size();
            if (!cached.isEmpty()) {
                started();
            }
            for (Map.Entry<Work, Map<String, Object>> e : cached.entrySet()) {
                reused++;
                record(e.getKey(), e.getValue(), worker, true);
            }
            if (!works.isEmpty()) {
                return works;
            }
        }
        return List.of();
    }

    /**
     * Waits for a battle another job is playing; counts its outcome when it comes, or plays it if that job does not
     * bring it back.
     */
    private void awaitOther(Work work, String key, String worker) {
        outstanding++;
        cache.await(key, outcome -> {
            if (outcome.isPresent()) {
                reused++;
                record(work, outcome.get(), worker, true);
            } else {
                outstanding--;
                String[] k = work.key().split("\t");
                if (!state().active() || !pairs.containsKey(pairKey(k[0], Integer.parseInt(k[1])))) {
                    return;
                }
                if (!cache.claim(key)) {
                    // Another waiting job took the battle over: wait for its outcome instead.
                    awaitOther(work, key, worker);
                    return;
                }
                cacheKeys.put(work.key(), key);
                retries.computeIfAbsent(worker, q -> new ArrayDeque<>()).add(work);
            }
        });
    }

    @Override
    public void completed(Work work, Map<String, Object> outcomeMap, String worker) throws IOException {
        String key = cacheKeys.remove(work.key());
        try {
            record(work, outcomeMap, worker, false);
        } finally {
            // Also when counting it failed: jobs waiting for this battle get it either way.
            if (key != null) {
                cache.put(key, work.spec(), outcomeMap);
            }
        }
    }

    /** Counts a battle's outcome, played now or taken from the cache. */
    private void record(Work work, Map<String, Object> outcomeMap, String worker, boolean fromCache)
            throws IOException {
        outstanding--;
        String[] key = work.key().split("\t");
        String opponent = key[0];
        int pair = Integer.parseInt(key[1]);
        Side side = Side.valueOf(key[2]);
        BattleOutcome outcome = work.spec().outcomeFromMap(outcomeMap);
        Subject subject = side == Side.BASELINE ? baseline : candidate;
        Battle battle = Compare.battle(outcome, subject, opponent, pair);
        turnTimes[side.ordinal()] = turnTimes[side.ordinal()].plus(outcome.turnTimes(subject.robotPrefix()));
        if (log != null) {
            log.append(battle);
        }
        count(battle);
        battlesThisRun++;
        long now = System.currentTimeMillis();
        if (!fromCache) {
            completions.add(now);
        }
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
        if (!state().active()) {
            release(work);
        }
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
            release(work);
            end(State.FAILED, key[2] + " " + key[0] + " pair " + key[1] + " failed " + ATTEMPTS + " times on "
                    + worker + ": " + reason);
        }
    }

    @Override
    public void lost(Work work) throws IOException {
        outstanding--;
        release(work);
    }

    /** Releases the claims of battles queued for a retry that will not be played now. */
    private void releaseAll(Deque<Work> queued) throws IOException {
        if (queued != null) {
            for (Work w : queued) {
                release(w);
            }
        }
    }

    /** This job will not bring a claimed battle back: jobs waiting for it play it themselves. */
    private void release(Work work) throws IOException {
        String key = cacheKeys.remove(work.key());
        if (key != null) {
            cache.release(key);
        }
    }

    @Override
    public void workerLost(String worker) throws IOException {
        releaseAll(retries.remove(worker));
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
        List<Deque<Work>> queued = new ArrayList<>(retries.values());
        retries.clear();
        for (Deque<Work> q : queued) {
            releaseAll(q);
        }
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
        return Compare.summarize(config, env, metric, bounds, strata, set.weights(), log, pairsPerOpponent);
    }

    @Override
    public Map<String, Object> progress() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", metric);
        m.put("bounds", pairsPerOpponent > 0 ? "fixed" : spec.getProperty("bounds", "gainer"));
        m.put("delta0", pairsPerOpponent > 0 ? null : bounds.delta0());
        m.put("delta1", pairsPerOpponent > 0 ? null : bounds.delta1());
        m.put("pairsPerOpponent", pairsPerOpponent > 0 ? pairsPerOpponent : null);
        m.put("opponents", strata.size());
        m.put("opponentSet", set.name());
        m.put("opponentsTitle", set.title().isEmpty() ? null : set.title());
        m.put("candidate", spec.getProperty("candidate"));
        m.put("baseline", spec.getProperty("baseline"));
        if (!state().active() && log == null) {
            for (String key : List.of("pairs", "estimate", "halfWidth", "llr", "lower", "upper", "decision",
                    "battles", "reusedBattles", "failures", "problems", "skippedSubject", "skippedOpponents",
                    "maxPairs", "ownTurns",
                    "ownTurnMaxMicros", "ownTurnsOverHalfBaseline", "ownTurnsOverHalfCandidate",
                    "ownTurnsOverIdleBaseline", "ownTurnsOverIdleCandidate")) {
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
        m.put("reusedBattles", reused);
        m.put("failures", failures);
        m.put("problems", problems);
        m.put("skippedSubject", skippedSubject);
        m.put("skippedOpponents", skippedOpponents);
        BattleOutcome.TurnTimes both = turnTimes[0].plus(turnTimes[1]);
        m.put("ownTurns", both.turns());
        m.put("ownTurnMaxMicros", both.maxMicros());
        m.put("ownTurnsOverHalfBaseline", turnTimes[0].overHalf());
        m.put("ownTurnsOverHalfCandidate", turnTimes[1].overHalf());
        m.put("ownTurnsOverIdleBaseline", turnTimes[0].overIdleConstant());
        m.put("ownTurnsOverIdleCandidate", turnTimes[1].overIdleConstant());
        double rate = completions.size() < 2 ? Double.NaN
                : (completions.size() - 1) * 1000.0 / Math.max(1, completions.peekLast() - completions.peekFirst());
        m.put("battlesPerSecond", finite(rate));
        Double remaining = remainingPairs(total, d);
        m.put("remainingPairs", remaining);
        // Pairs take their battles from the cache as often as they did so far in this server run.
        double played = battlesThisRun == 0 ? 1 : Math.max(0.05, 1 - (double) reused / battlesThisRun);
        m.put("etaSeconds", remaining == null || !(rate > 0) ? null : 2 * remaining * played / rate);
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
