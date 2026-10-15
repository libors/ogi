package cz.libors.harness.compare;

import cz.libors.harness.BattleOutcome;
import cz.libors.harness.ContestantResult;
import cz.libors.harness.Json;
import cz.libors.harness.compare.Compare.Health;
import cz.libors.harness.compare.Compare.Noise;
import cz.libors.harness.compare.Compare.OpponentRow;
import cz.libors.harness.compare.Compare.Scoreboard;
import cz.libors.harness.compare.Compare.Subject;
import cz.libors.harness.compare.Compare.Summary;
import cz.libors.harness.compare.CompareLog.Side;
import cz.libors.harness.compare.MeleeDraws.Draw;
import cz.libors.harness.compare.MeleeLog.Battle;
import cz.libors.harness.compare.MeleeLog.Opponent;
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
 * A melee comparison run by the queue. Instead of opponents it plays draws ({@link MeleeDraws}): draw d gives both
 * versions the same nine opponents, the same place among them and the same seed. A battle's measure is the subject's
 * mean pairwise share against its nine opponents ({@link Battle#share()}), as the rumble scores it, so the mean over
 * draws of one version estimates its melee APS. The test is {@link Compare}'s sequential test on one stratum, the
 * draws, with the difference of the two versions' measures per draw; in fixed mode ({@code draws=n}) a set number of
 * draws and no test.
 *
 * <p>The rest works as in {@link CompareJob}: a draw's two battles go to one worker, a failed battle is retried there
 * twice, a draw whose worker went away is dropped and a new draw takes its place, the log ({@link MeleeLog}) makes the
 * job resumable, and battles come from the {@link BattleCache} when it holds them.
 */
public final class MeleeJob extends QueueJob {

    private static final int ATTEMPTS = 3;
    private static final long RATE_WINDOW_MILLIS = 120_000;
    /** Seconds per battle: ten robots for 35 rounds take several times a duel's time. */
    static final int DEFAULT_TIMEOUT = 1800;
    /**
     * Draws before the test may decide: {@link Sprt#decide} wants {@link Sprt#MIN_FREEDOM} degrees of freedom, and
     * the draws are one stratum.
     */
    static final int BURN_IN_DRAWS = Sprt.MIN_FREEDOM + 1;
    /** The draw scheme in the log's configuration: logs of another scheme are not resumed. */
    private static final String DRAW_SCHEME = "group quotas 1";

    private final OpponentSet set;
    private final MeleeDraws draws;
    private final int rounds;
    private final long runSeed;
    private final Bounds bounds;
    private final int maxPairs;
    private final int fixedDraws;
    private final long timeoutSeconds;
    private final Subject baseline;
    private final Subject candidate;
    private final Map<String, String> config;
    private final Map<String, String> jars = new LinkedHashMap<>();
    private final Stratum stratum = new Stratum("draws", false, "");
    private final Map<Integer, PairState> pairs = new LinkedHashMap<>();
    private final Map<String, Deque<Work>> retries = new HashMap<>();
    private final Deque<Long> completions = new ArrayDeque<>();
    private final boolean reuse;
    /** The cache keys of the battles handed out, by work key. */
    private final Map<String, String> cacheKeys = new HashMap<>();
    /** The versions' own turn times, by side, from the battles of this server run. */
    private final BattleOutcome.TurnTimes[] turnTimes = {BattleOutcome.TurnTimes.NONE, BattleOutcome.TurnTimes.NONE};
    private Supplier<Map<String, String>> environment = Map::of;
    private BattleCache cache;
    private MeleeLog log;
    private int nextDraw;
    private int outstanding;
    private int battles;
    private int failures;
    private int problems;
    private int skippedSubject;
    private int skippedOpponents;
    /** Battles taken from the cache in this server run, and battles counted in it (the log's earlier ones not). */
    private int reused;
    private int battlesThisRun;

    /** A draw handed out and not yet complete. */
    private static final class PairState {
        final String worker;
        final Battle[] done = new Battle[2];
        final int[] attempts = new int[2];

        PairState(String worker) {
            this.worker = worker;
        }
    }

    private MeleeJob(Path dir, Properties spec) throws IOException {
        super(dir, spec);
        set = OpponentSet.parse(spec.getProperty("opponentSet"), Files.readAllLines(dir.resolve("opponents.txt")));
        draws = new MeleeDraws(set);
        int roundsOption = Integer.parseInt(spec.getProperty("rounds", "0"));
        rounds = roundsOption > 0 ? roundsOption : set.format().rounds;
        runSeed = Long.parseLong(spec.getProperty("seed", "1"));
        bounds = Bounds.parse(spec.getProperty("bounds", "gainer"));
        maxPairs = Integer.parseInt(spec.getProperty("maxPairs", "1000"));
        fixedDraws = Integer.parseInt(spec.getProperty("draws", "0"));
        timeoutSeconds = Long.parseLong(spec.getProperty("timeout", Integer.toString(DEFAULT_TIMEOUT)));
        reuse = Boolean.parseBoolean(spec.getProperty("reuse", "true"));
        spec.stringPropertyNames().stream().filter(k -> k.startsWith("jar.")).sorted()
                .forEach(k -> jars.put(k.substring(4), spec.getProperty(k)));
        String robot = spec.getProperty("robot");
        baseline = subject(Side.BASELINE, robot, spec.getProperty("baseline"));
        candidate = subject(Side.CANDIDATE, robot, spec.getProperty("candidate"));
        config = Compare.config(baseline, candidate, set, rounds, false, runSeed);
        config.put("draws", DRAW_SCHEME);
        if (state().active()) {
            log = MeleeLog.open(dir.resolve("battles.tsv"), config);
            for (Battle b : log.battles()) {
                nextDraw = Math.max(nextDraw, b.draw() + 1);
                count(b);
            }
            for (Battle[] p : log.completeDraws()) {
                stratum.add(p[0].share(), p[1].share());
            }
        }
    }

    public static MeleeJob open(Path dir, Properties spec) throws IOException {
        return new MeleeJob(dir, spec);
    }

    /** Whether an opponent set is a melee one, so that its comparison is a {@link MeleeJob}. */
    public static boolean melee(List<String> opponentLines) {
        return OpponentSet.parse("set", opponentLines).format() == Format.MELEE;
    }

    /**
     * Checks a melee comparison before it is queued and describes it; throws {@link IllegalArgumentException} on a
     * bad option, a missing subject jar or equal versions.
     */
    public static String check(Properties spec, List<String> opponentLines) {
        OpponentSet set = OpponentSet.parse(spec.getProperty("opponentSet"), opponentLines);
        if (set.format() != Format.MELEE) {
            throw new IllegalArgumentException(set.name() + " is not a melee set");
        }
        new MeleeDraws(set);
        Bounds.parse(spec.getProperty("bounds", "gainer"));
        if (!spec.getProperty("metric", "score").equals("score")) {
            throw new IllegalArgumentException("a melee comparison tests the score share only");
        }
        if (!spec.getProperty("starts", "random").equals("random")) {
            // Fixed starts repeat one layout in every round (engine-model G-41); the rumble's rounds start anywhere.
            throw new IllegalArgumentException("a melee comparison plays random starts only");
        }
        if (spec.getProperty("pairsPerOpponent") != null) {
            throw new IllegalArgumentException("a melee comparison has draws, not opponents: use draws=n");
        }
        Long.parseLong(spec.getProperty("seed", "1"));
        Compare.intOption(spec, "rounds", 0, 0);
        int maxPairs = Compare.intOption(spec, "maxPairs", 1000, 1);
        int fixed = Compare.intOption(spec, "draws", 0, 0);
        Compare.intOption(spec, "timeout", DEFAULT_TIMEOUT, 1);
        if (fixed == 0 && maxPairs < BURN_IN_DRAWS) {
            throw new IllegalArgumentException("maxPairs " + maxPairs + " is below the test's " + BURN_IN_DRAWS
                    + " draws before a decision: the test could never decide");
        }
        String robot = spec.getProperty("robot");
        String baseline = spec.getProperty("baseline");
        String candidate = spec.getProperty("candidate");
        if (robot == null || baseline == null || candidate == null) {
            throw new IllegalArgumentException("a comparison needs its robot, a baseline and a candidate");
        }
        if (!cz.libors.harness.Sandbox.trusted(robot + " " + baseline)) {
            // The subjects come with the job's jars; a foreign robot must not skip the worker's jar check.
            throw new IllegalArgumentException(robot + " is not one of our robots");
        }
        if (baseline.equals(candidate)) {
            throw new IllegalArgumentException("baseline and candidate are the same version: " + baseline);
        }
        for (String version : List.of(baseline, candidate)) {
            if (spec.getProperty("jar." + robot + "_" + version + ".jar") == null) {
                throw new IllegalArgumentException("no jar " + robot + "_" + version + ".jar in the job");
            }
        }
        return String.format(Locale.ROOT, "%s %s vs %s on %s (melee draws from %d opponents%s)", robot, candidate,
                baseline, set.name(), set.opponents().size(), fixed > 0 ? ", " + fixed + " draws" : "");
    }

    private Subject subject(Side side, String robot, String version) throws IOException {
        String jar = robot + "_" + version + ".jar";
        String sha = jars.get(jar);
        if (sha == null) {
            throw new IOException(dir + ": the job has no " + jar);
        }
        return new Subject(side, robot + " " + version, robot + " " + version, sha);
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
        return Format.MELEE.label;
    }

    @Override
    public String title() {
        return String.format(Locale.ROOT, "%s vs %s · %s · melee %s", candidate.name(), spec.getProperty("baseline"),
                set.name(), fixedDraws > 0 ? fixedDraws + " draws" : spec.getProperty("bounds", "gainer") + " "
                        + bounds);
    }

    private List<Stratum> strata() {
        return List.of(stratum);
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
        // A draw whose battles both come from the cache is complete at once; the next draw follows.
        while (state().active()) {
            int planned = stratum.size() + pairs.size();
            if (fixedDraws > 0 ? planned >= fixedDraws
                    : Sprt.decide(strata(), bounds) != Decision.CONTINUE || planned >= maxPairs) {
                return List.of();
            }
            Draw draw = draws.draw(runSeed, nextDraw++);
            pairs.put(draw.number(), new PairState(worker));
            // Which version goes first alternates, as in Compare; with many slots both usually run at once.
            List<Subject> order = draw.number() % 2 == 0 ? List.of(baseline, candidate)
                    : List.of(candidate, baseline);
            List<Work> works = new ArrayList<>();
            Map<Work, Map<String, Object>> cached = new LinkedHashMap<>();
            for (Subject subject : order) {
                BattleSpec battle = new BattleSpec("melee draw " + draw.number(), Format.MELEE.width,
                        Format.MELEE.height, rounds, draw.robots(subject.name()), draw.seed(), null);
                Work work = new Work(this, draw.number() + "\t" + subject.side(), battle, timeoutSeconds);
                Optional<String> key = reuse && cache != null
                        ? cache.key(worker, battle, draw.subjectIndex(), subject.jarSha()) : Optional.empty();
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
                if (!state().active() || !pairs.containsKey(draw(work))) {
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
        BattleOutcome outcome = work.spec().outcomeFromMap(outcomeMap);
        Optional<String> missing = work.spec().robots().stream().filter(r -> result(outcome, r).isEmpty())
                .findFirst();
        if (missing.isPresent()) {
            // Not a battle of the ten robots asked for: played again, like a battle that failed.
            if (key != null) {
                cacheKeys.put(work.key(), key);
            }
            failed(work, "no result for " + missing.get() + " in " + outcome.results().stream()
                    .map(ContestantResult::name).toList(), worker);
            return;
        }
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
        int d = draw(work);
        Side side = Side.valueOf(work.key().split("\t")[1]);
        BattleOutcome outcome = work.spec().outcomeFromMap(outcomeMap);
        Subject subject = side == Side.BASELINE ? baseline : candidate;
        Battle battle = battle(outcome, subject, d, work.spec().robots());
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
                    .map(p -> side + " draw " + d + " (" + worker + "): " + p).toList());
        }
        PairState p = pairs.get(d);
        if (p == null || !state().active()) {
            return;
        }
        p.done[side.ordinal()] = battle;
        if (p.done[0] != null && p.done[1] != null) {
            pairs.remove(d);
            stratum.add(p.done[0].share(), p.done[1].share());
            checkEnd();
        }
    }

    /** The log's line of a battle of {@code subject} among {@code robots}. */
    static Battle battle(BattleOutcome outcome, Subject subject, int draw, List<String> robots) {
        ContestantResult mine = result(outcome, subject.name()).orElseThrow(
                () -> new IllegalStateException("no result for " + subject.name() + ": " + outcome.results()));
        List<Opponent> opponents = new ArrayList<>();
        for (String robot : robots) {
            if (!robot.equals(subject.name())) {
                ContestantResult r = result(outcome, robot).orElseThrow(
                        () -> new IllegalStateException("no result for " + robot + ": " + outcome.results()));
                opponents.add(new Opponent(robot, r.score(), r.firsts(),
                        outcome.skippedTurns().getOrDefault(robot, 0)));
            }
        }
        BattleOutcome.Shots shots = outcome.shots(subject.name());
        int skipped = outcome.skippedTurns(subject.robotPrefix());
        int allSkipped = outcome.skippedTurns().values().stream().mapToInt(Integer::intValue).sum();
        return new Battle(draw, subject.side(), outcome.seed(), robots.indexOf(subject.name()), mine.score(),
                mine.survival(), mine.lastSurvivorBonus(), mine.bulletDamage(), mine.bulletDamageBonus(),
                mine.ramDamage(), mine.ramDamageBonus(), mine.firsts(), shots.fired(), shots.hits(), skipped,
                allSkipped - skipped, outcome.problems().size(), outcome.elapsedMillis(), opponents);
    }

    /**
     * The result of {@code robot}: the contestant named exactly so; the engine adds " (n)" to the names of robots
     * that appear more than once, which a draw never has, and versions such as "1.1" and "1.10" share a prefix.
     */
    private static Optional<ContestantResult> result(BattleOutcome outcome, String robot) {
        return outcome.results().stream().filter(r -> r.name().equals(robot)).findFirst();
    }

    @Override
    public void failed(Work work, String reason, String worker) throws IOException {
        outstanding--;
        if (!state().active()) {
            release(work);
        }
        failures++;
        int d = draw(work);
        String side = work.key().split("\t")[1];
        problems(List.of(side + " draw " + d + " (" + worker + ") failed: " + reason));
        PairState p = pairs.get(d);
        if (p == null || !state().active()) {
            return;
        }
        int index = Side.valueOf(side).ordinal();
        if (++p.attempts[index] < ATTEMPTS) {
            retries.computeIfAbsent(worker, k -> new ArrayDeque<>()).add(work);
        } else {
            release(work);
            end(State.FAILED, side + " draw " + d + " failed " + ATTEMPTS + " times on " + worker + ": " + reason);
        }
    }

    @Override
    public void lost(Work work) throws IOException {
        outstanding--;
        release(work);
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
        Deque<Work> queued = retries.remove(worker);
        if (queued != null) {
            for (Work w : queued) {
                release(w);
            }
        }
        pairs.values().removeIf(p -> p.worker.equals(worker));
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
        int total = stratum.size();
        if (fixedDraws > 0) {
            if (pairs.isEmpty() && total >= fixedDraws) {
                end(State.DONE, fixedDraws + " draws");
            }
            return;
        }
        Decision decision = Sprt.decide(strata(), bounds);
        if (decision != Decision.CONTINUE) {
            end(State.DONE, Compare.describe(decision, bounds));
        } else if (total >= maxPairs && pairs.isEmpty()) {
            end(State.DONE, "undecided after " + total + " draws (maxPairs)");
        }
    }

    private void end(State state, String reason) throws IOException {
        List<Deque<Work>> queued = new ArrayList<>(retries.values());
        retries.clear();
        for (Deque<Work> q : queued) {
            for (Work w : q) {
                release(w);
            }
        }
        Summary summary = summary();
        Files.writeString(dir.resolve("summary.json"), Json.write(summary));
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(text, true, StandardCharsets.UTF_8)) {
            Compare.printReport(summary, out);
        }
        Files.writeString(dir.resolve("summary.txt"), text.toString(StandardCharsets.UTF_8));
        Map<String, String> results = new LinkedHashMap<>();
        progress().forEach((k, v) -> {
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
        return summarize(config, env, bounds, stratum, set, log.completeDraws(), log.battles(), rounds, fixedDraws);
    }

    /**
     * The scoreboards, the opponents, noise and health of a melee comparison, in {@link Compare}'s shapes.
     *
     * <ul>
     *   <li><b>all</b>: the draws' measure, the subject's mean pairwise share in a battle: the rumble's APS estimate
     *       of each version, their difference and its interval as the test sees them.</li>
     *   <li>One per group of the set: the pairwise share against the group's opponents over all their appearances,
     *       a ratio of sums over the draws; its interval treats the draws as clusters, since the opponents of one
     *       battle share its luck.</li>
     *   <li><b>survival</b>: the subject's first places, in percent of the rounds, per draw (the rumble's second
     *       column); its count is the draws.</li>
     *   <li>Opponents: each one that appeared, with its appearances as pairs.</li>
     * </ul>
     *
     * <p>Wins are pairwise (more points than the opponent in the battle); hit rates are the subject's over all its
     * bullets, on the overall row only: a melee battle does not tell who hit whom.
     */
    static Summary summarize(Map<String, String> config, Map<String, String> environment, Bounds bounds,
                             Stratum stratum, OpponentSet set, List<Battle[]> complete, List<Battle> all, int rounds,
                             int fixedDraws) {
        Map<String, String> groupOf = new LinkedHashMap<>();
        set.opponents().forEach(o -> groupOf.put(o.name(), o.group()));
        Map<String, Stratum> byOpponent = new LinkedHashMap<>();
        Map<String, double[]> winsByOpponent = new HashMap<>();
        int[] fired = new int[2];
        int[] hits = new int[2];
        Stratum survival = new Stratum("survival", false, "");
        for (Battle[] p : complete) {
            survival.add(100.0 * p[0].firsts() / rounds, 100.0 * p[1].firsts() / rounds);
            for (int side = 0; side < 2; side++) {
                fired[side] += p[side].fired();
                hits[side] += p[side].hits();
            }
            for (int i = 0; i < p[0].opponents().size(); i++) {
                Opponent ob = p[0].opponents().get(i);
                Opponent oc = opponent(p[1], ob.name());
                byOpponent.computeIfAbsent(ob.name(), n -> new Stratum(n, false, groupOf.getOrDefault(n, "")))
                        .add(p[0].share(ob), p[1].share(oc));
                double[] w = winsByOpponent.computeIfAbsent(ob.name(), n -> new double[2]);
                w[0] += win(p[0], ob);
                w[1] += win(p[1], oc);
            }
        }
        List<OpponentRow> rows = new ArrayList<>();
        byOpponent.values().forEach(s -> {
            double[] w = winsByOpponent.get(s.opponent());
            rows.add(new OpponentRow(s.opponent(), false, s.size(), s.mean(Metric.BASELINE),
                    s.mean(Metric.CANDIDATE), s.mean(Metric.DIFFERENCE), Math.sqrt(s.variance(Metric.BASELINE)),
                    Math.sqrt(s.variance(Metric.CANDIDATE)), Math.sqrt(s.variance(Metric.DIFFERENCE)),
                    s.correlation(), s.mean(Metric.BASELINE), s.mean(Metric.CANDIDATE), 100 * w[0] / s.size(),
                    100 * w[1] / s.size(), Double.NaN, Double.NaN, Double.NaN, Double.NaN));
        });

        List<Scoreboard> boards = new ArrayList<>();
        Estimate base = Sprt.estimate(List.of(stratum), Metric.BASELINE, x -> true);
        Estimate cand = Sprt.estimate(List.of(stratum), Metric.CANDIDATE, x -> true);
        Estimate diff = Sprt.estimate(List.of(stratum), Metric.DIFFERENCE, x -> true);
        double[] allWins = pooledWins(rows);
        boards.add(new Scoreboard("all", set.opponents().size(), base.mean(), cand.mean(), diff.mean(),
                diff.halfWidth(), base.mean(), cand.mean(), allWins[0], allWins[1], rate(hits[0], fired[0]),
                rate(hits[1], fired[1]), Double.NaN, Double.NaN));
        for (String group : groupOf.values().stream().distinct().toList()) {
            boards.add(groupBoard(group, groupOf, complete, rows));
        }
        Estimate sb = Sprt.estimate(List.of(survival), Metric.BASELINE, x -> true);
        Estimate sc = Sprt.estimate(List.of(survival), Metric.CANDIDATE, x -> true);
        Estimate sd = Sprt.estimate(List.of(survival), Metric.DIFFERENCE, x -> true);
        boards.add(new Scoreboard("survival", complete.size(), sb.mean(), sc.mean(), sd.mean(), sd.halfWidth(),
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN));

        int draws = stratum.size();
        double vb = stratum.variance(Metric.BASELINE);
        double vc = stratum.variance(Metric.CANDIDATE);
        double vd = stratum.variance(Metric.DIFFERENCE);
        double sigma = Math.sqrt(vd);
        Noise noise = new Noise(sigma, Math.sqrt(vb), sigma, (vb + vc - vd) / (2 * Math.sqrt(vb * vc)),
                Sprt.expectedPairs(sigma, bounds, bounds.delta0()),
                Sprt.expectedPairs(sigma, bounds, (bounds.delta0() + bounds.delta1()) / 2));
        int[] skipped = new int[3];
        int problems = 0;
        long battleMillis = 0;
        for (Battle b : all) {
            skipped[b.side().ordinal()] += b.skippedTurns();
            skipped[2] += b.opponentSkippedTurns();
            problems += b.problems();
            battleMillis += b.elapsedMillis();
        }
        Health health = new Health(skipped[0], skipped[1], skipped[2], problems, 0, battleMillis / 1000);
        if (fixedDraws > 0) {
            return new Summary(config, environment, "score", "fixed", fixedDraws + " draws", Double.NaN, Double.NaN,
                    Double.NaN, draws, boards, rows, noise, health);
        }
        Decision decision = Sprt.decide(List.of(stratum), bounds);
        return new Summary(config, environment, "score", bounds.toString(), Compare.describe(decision, bounds),
                Sprt.llr(diff, bounds), bounds.lower(), bounds.upper(), draws, boards, rows, noise, health);
    }

    /**
     * A group's pairwise shares as ratios of sums over the draws, with the variance of a ratio estimator whose
     * clusters are the draws: (D/(D − 1))·Σ (y_d − r·n_d)² / (Σ n_d)², y_d the sum over the group's opponents of draw
     * d and n_d their number.
     */
    private static Scoreboard groupBoard(String group, Map<String, String> groupOf, List<Battle[]> complete,
                                         List<OpponentRow> rows) {
        int d = complete.size();
        double[] n = new double[d];
        double[][] y = new double[3][d];
        double[] sums = new double[4];
        for (int i = 0; i < d; i++) {
            Battle[] p = complete.get(i);
            for (Opponent ob : p[0].opponents()) {
                if (!group.equals(groupOf.get(ob.name()))) {
                    continue;
                }
                double b = p[0].share(ob);
                double c = p[1].share(opponent(p[1], ob.name()));
                n[i]++;
                y[0][i] += b;
                y[1][i] += c;
                y[2][i] += c - b;
            }
        }
        for (int i = 0; i < d; i++) {
            sums[0] += n[i];
            sums[1] += y[0][i];
            sums[2] += y[1][i];
            sums[3] += y[2][i];
        }
        double meanDiff = sums[3] / sums[0];
        double squares = 0;
        for (int i = 0; i < d; i++) {
            squares += Math.pow(y[2][i] - meanDiff * n[i], 2);
        }
        double variance = d < 2 || sums[0] == 0 ? Double.NaN : d / (d - 1.0) * squares / (sums[0] * sums[0]);
        List<OpponentRow> groupRows = rows.stream().filter(r -> group.equals(groupOf.get(r.opponent()))).toList();
        double[] wins = pooledWins(groupRows);
        long size = groupOf.values().stream().filter(group::equals).count();
        return new Scoreboard(group, (int) size, sums[1] / sums[0], sums[2] / sums[0], meanDiff,
                1.96 * Math.sqrt(variance), sums[1] / sums[0], sums[2] / sums[0], wins[0], wins[1], Double.NaN,
                Double.NaN, Double.NaN, Double.NaN);
    }

    /** Pairwise wins over the rows' appearances, in percent, per version. */
    private static double[] pooledWins(List<OpponentRow> rows) {
        double[] wins = new double[2];
        int n = 0;
        for (OpponentRow r : rows) {
            wins[0] += r.pwinBaseline() * r.pairs();
            wins[1] += r.pwinCandidate() * r.pairs();
            n += r.pairs();
        }
        return n == 0 ? new double[] {Double.NaN, Double.NaN} : new double[] {wins[0] / n, wins[1] / n};
    }

    private static double win(Battle b, Opponent o) {
        return b.score() > o.score() ? 1 : b.score() == o.score() ? 0.5 : 0;
    }

    /** The draw's other battle has the same opponents, in the same order but around the subject. */
    private static Opponent opponent(Battle b, String name) {
        for (Opponent o : b.opponents()) {
            if (o.name().equals(name)) {
                return o;
            }
        }
        throw new IllegalStateException("draw " + b.draw() + ": " + name + " is not in the " + b.side() + " battle");
    }

    private static double rate(int hits, int fired) {
        return fired == 0 ? Double.NaN : 100.0 * hits / fired;
    }

    @Override
    public Map<String, Object> progress() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", "score");
        m.put("unit", "draws");
        m.put("bounds", fixedDraws > 0 ? "fixed" : spec.getProperty("bounds", "gainer"));
        m.put("delta0", fixedDraws > 0 ? null : bounds.delta0());
        m.put("delta1", fixedDraws > 0 ? null : bounds.delta1());
        m.put("draws", fixedDraws > 0 ? fixedDraws : null);
        m.put("opponents", set.opponents().size());
        m.put("opponentSet", set.name());
        m.put("opponentsTitle", set.title().isEmpty() ? null : set.title());
        m.put("candidate", spec.getProperty("candidate"));
        m.put("baseline", spec.getProperty("baseline"));
        if (!state().active() && log == null) {
            for (String key : List.of("pairs", "estimate", "halfWidth", "llr", "lower", "upper", "decision",
                    "battles", "reusedBattles", "failures", "problems", "skippedSubject", "skippedOpponents",
                    "maxPairs", "apsBaseline", "apsCandidate", "ownTurns", "ownTurnMaxMicros",
                    "ownTurnsOverHalfBaseline", "ownTurnsOverHalfCandidate", "ownTurnsOverIdleBaseline",
                    "ownTurnsOverIdleCandidate")) {
                String v = result(key);
                m.put(key, v == null ? null : number(v));
            }
            return m;
        }
        int total = stratum.size();
        Estimate d = Sprt.estimate(strata(), Metric.DIFFERENCE, x -> true);
        m.put("pairs", total);
        m.put("pendingPairs", pairs.size());
        m.put("maxPairs", fixedDraws > 0 ? fixedDraws : maxPairs);
        m.put("estimate", finite(d.mean()));
        m.put("halfWidth", finite(d.halfWidth()));
        m.put("llr", fixedDraws > 0 ? null : finite(Sprt.llr(d, bounds)));
        m.put("lower", bounds.lower());
        m.put("upper", bounds.upper());
        m.put("decision", fixedDraws > 0 ? null : Compare.describe(Sprt.decide(strata(), bounds), bounds));
        m.put("apsBaseline", finite(stratum.mean(Metric.BASELINE)));
        m.put("apsCandidate", finite(stratum.mean(Metric.CANDIDATE)));
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
        Double remaining = remainingDraws(total, d);
        m.put("remainingPairs", remaining);
        // Draws take their battles from the cache as often as they did so far in this server run.
        double played = battlesThisRun == 0 ? 1 : Math.max(0.05, 1 - (double) reused / battlesThisRun);
        m.put("etaSeconds", remaining == null || !(rate > 0) ? null : 2 * remaining * played / rate);
        return m;
    }

    /**
     * Draws still to play: exact in fixed mode; otherwise the Brownian approximation of the test's expected length at
     * the current estimate, less the draws played, at most up to {@code maxPairs}.
     */
    private Double remainingDraws(int total, Estimate d) {
        if (fixedDraws > 0) {
            return (double) Math.max(0, fixedDraws - total);
        }
        if (!state().active()) {
            return 0.0;
        }
        if (!Double.isFinite(d.mean()) || !(d.variance() > 0) || total == 0) {
            return null;
        }
        double width = bounds.delta1() - bounds.delta0();
        double mean = Math.max(bounds.delta0() - width, Math.min(bounds.delta1() + width, d.mean()));
        double expected = Math.max(BURN_IN_DRAWS, Sprt.expectedPairs(Math.sqrt(d.variance() * total), bounds, mean));
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

    private static int draw(Work work) {
        return Integer.parseInt(work.key().split("\t")[0]);
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
