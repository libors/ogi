package cz.libors.harness.compare;

import cz.libors.harness.BattleOutcome;
import cz.libors.harness.BattleRunner;
import cz.libors.harness.BattleSetup;
import cz.libors.harness.ContestantResult;
import cz.libors.harness.Json;
import cz.libors.harness.Starts;
import cz.libors.harness.compare.CompareLog.Battle;
import cz.libors.harness.compare.CompareLog.Side;
import cz.libors.harness.compare.OpponentSet.Format;
import cz.libors.harness.compare.OpponentSet.Opponent;
import cz.libors.harness.compare.Sprt.Bounds;
import cz.libors.harness.compare.Sprt.Decision;
import cz.libors.harness.compare.Sprt.Estimate;
import cz.libors.harness.compare.Stratum.Metric;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Paired comparison of two bot versions, the baseline and the candidate, against a fixed opponent set.
 *
 * <p>Both versions fight each opponent on the same seeds and, optionally, from the same fixed starts. In sequential
 * mode a stratified GSPRT ({@link Sprt}) picks the next opponent and stops once the difference is decided; in fixed
 * mode every opponent gets the same number of pairs, for measuring noise. The test runs on one measure of each
 * battle: the subject's score share, its hit rate, or how rarely the opponent hits it. Every battle is appended to a
 * log as it finishes, so running the same comparison again resumes it, with any measure.
 *
 * <p>Usage: {@code Compare <robocodeHome> key=value...}; see docs/harness.md for the keys.
 */
public final class Compare {

    private static final Map<String, String> DEFAULTS = Map.of(
            "bounds", "gainer",
            "maxPairs", "1000",
            "pairsPerOpponent", "0",
            "starts", "random",
            "seed", "1",
            "rounds", "0",
            "metric", "score");

    private static final List<String> REQUIRED = List.of("robot", "team", "baseline", "candidate", "opponents", "out");

    /** One of the two versions under comparison. */
    record Subject(Side side, String name, String robotPrefix, String jarSha) {
    }

    /** The number a battle contributes to the test, in points (0–100). */
    enum Measure {
        /** The subject's score share. */
        SCORE,
        /** The subject's hits per bullet fired, in percent; 0 without shots. */
        HITS,
        /** 100 minus the opponent's hit rate on the subject; 100 if the opponent never fires. */
        DODGE;

        double of(Battle b) {
            return switch (this) {
                case SCORE -> b.share();
                case HITS -> b.fired() == 0 ? 0 : 100.0 * b.hits() / b.fired();
                case DODGE -> 100 - (b.opponentFired() == 0 ? 0 : 100.0 * b.opponentHits() / b.opponentFired());
            };
        }

        static Measure parse(String metric) {
            return switch (metric) {
                case "score" -> SCORE;
                case "hits" -> HITS;
                case "dodge" -> DODGE;
                default -> throw new IllegalArgumentException("metric: score, hits or dodge");
            };
        }
    }

    /**
     * One opponent: the test measure's statistics, then the score share, PWIN and hit rates of both versions. Hit
     * rates pool all bullets of the pairs; {@code hitBy} is the opponent's hit rate on the version.
     */
    public record OpponentRow(String opponent, boolean strong, int pairs, double baseline, double candidate,
                               double difference, double sdBaseline, double sdCandidate, double sdDifference,
                               double correlation, double shareBaseline, double shareCandidate, double pwinBaseline,
                               double pwinCandidate, double hitRateBaseline, double hitRateCandidate,
                               double hitByBaseline, double hitByCandidate) {
    }

    /** The test measure over a set of opponents, and the rows' other columns, each opponent weighing the same. */
    public record Scoreboard(String name, int opponents, double baseline, double candidate, double difference,
                              double halfWidth, double apsBaseline, double apsCandidate, double pwinBaseline,
                              double pwinCandidate, double hitRateBaseline, double hitRateCandidate,
                              double hitByBaseline, double hitByCandidate) {
    }

    /**
     * @param sigmaPair           standard deviation of the estimated difference times √pairs: the noise per pair
     *                            under the allocation used
     * @param pooledSdBaseline    per-battle standard deviation of the baseline's score share within an opponent
     * @param pooledSdDifference  standard deviation of a pair's difference within an opponent
     * @param pooledCorrelation   correlation of the two battles of a pair, from the pooled variances
     */
    public record Noise(double sigmaPair, double pooledSdBaseline, double pooledSdDifference,
                         double pooledCorrelation, double expectedPairsAtBounds, double expectedPairsMidway) {
    }

    public record Health(int skippedTurnsBaseline, int skippedTurnsCandidate, int skippedTurnsOpponents,
                          int problems, int startMismatches, long battleSeconds) {
    }

    public record Summary(Map<String, String> config, Map<String, String> environment, String metric,
                           String bounds, String decision, double llr, double llrLower, double llrUpper, int pairs,
                           List<Scoreboard> scoreboards, List<OpponentRow> opponents, Noise noise, Health health) {
    }

    private final Map<String, String> options;
    private final OpponentSet set;
    private final Format format;
    private final int rounds;
    private final boolean fixedStarts;
    private final long runSeed;
    private final Bounds bounds;
    private final Measure measure;
    private final BattleRunner runner;
    private final Map<String, Integer> robotCounts = new HashMap<>();
    private Subject baseline;
    private Subject candidate;
    private CompareLog log;
    private Path problemsFile;

    private Compare(Map<String, String> options, OpponentSet set, BattleRunner runner) {
        this.options = options;
        this.set = set;
        this.format = set.format();
        int roundsOption = Integer.parseInt(options.get("rounds"));
        this.rounds = roundsOption > 0 ? roundsOption : format.rounds;
        this.fixedStarts = fixedStarts(options.get("starts"));
        this.runSeed = Long.parseLong(options.get("seed"));
        this.bounds = Bounds.parse(options.get("bounds"));
        this.measure = Measure.parse(options.get("metric"));
        this.runner = runner;
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new HashMap<>(DEFAULTS);
        for (int i = 1; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq <= 0) {
                usage("not key=value: " + args[i]);
            }
            options.put(args[i].substring(0, eq), args[i].substring(eq + 1));
        }
        if (args.length < 1 || !options.keySet().containsAll(REQUIRED)) {
            usage("missing one of " + REQUIRED);
        }
        Path home = Path.of(args[0]);
        OpponentSet set = OpponentSet.read(Path.of(options.get("opponents")));
        try (BattleRunner runner = new BattleRunner(home)) {
            new Compare(options, set, runner).run(home);
        }
        System.exit(0);
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("Usage: Compare <robocodeHome> robot=<class> team=<class> baseline=<version> "
                + "candidate=<version> opponents=<file> out=<dir> [bounds=gainer|screening|nonregression|δ0,δ1] "
                + "[maxPairs=n] [pairsPerOpponent=n] [starts=random|fixed] [seed=n] [rounds=n] "
                + "[metric=score|hits|dodge]");
        System.exit(2);
    }

    private void run(Path home) throws IOException {
        String subjectClass = format == Format.TEAM ? options.get("team") : options.get("robot");
        baseline = subject(Side.BASELINE, subjectClass, options.get("baseline"));
        candidate = subject(Side.CANDIDATE, subjectClass, options.get("candidate"));
        if (baseline.name().equals(candidate.name())) {
            throw new IllegalArgumentException("baseline and candidate are the same: " + baseline.name());
        }
        for (Opponent o : set.opponents()) {
            runner.jarFile(o.name()); // fails early on a missing opponent
        }

        Map<String, String> config = config(baseline, candidate, set, rounds, fixedStarts, runSeed);
        Path dir = Path.of(options.get("out")).resolve(set.name())
                .resolve(dirName(options.get("baseline"), options.get("candidate"), config));
        problemsFile = dir.resolve("problems.log");

        List<Stratum> strata = set.opponents().stream().map(o -> new Stratum(o.name(), o.strong(), o.group()))
                .toList();
        Map<String, Integer> nextPair = new HashMap<>();
        int pairsPerOpponent = Integer.parseInt(options.get("pairsPerOpponent"));
        int maxPairs = Integer.parseInt(options.get("maxPairs"));
        long start = System.nanoTime();
        try (CompareLog opened = CompareLog.open(dir.resolve("battles.tsv"), config)) {
            log = opened;
            for (Battle b : log.battles()) {
                nextPair.merge(b.opponent(), b.pair() + 1, Math::max);
            }
            log.completePairs().forEach((opponent, pairs) -> {
                Stratum s = strata.stream().filter(x -> x.opponent().equals(opponent)).findFirst().orElseThrow();
                pairs.forEach(p -> s.add(measure.of(p[0]), measure.of(p[1])));
            });
            int resumed = total(strata);
            System.out.printf(Locale.ROOT, "%s vs %s on %s (%s, %d rounds, %s starts, seed %d), %s, bounds %s%n",
                    candidate.name(), baseline.name(), set.name(), format.label, rounds, config.get("starts"),
                    runSeed, options.get("metric"), bounds);
            System.out.printf(Locale.ROOT, "log %s%s%n", log.file(),
                    resumed > 0 ? ", resuming after " + resumed + " pairs" : "");

            while (true) {
                if (pairsPerOpponent == 0 && Sprt.decide(strata, bounds) != Decision.CONTINUE) {
                    break;
                }
                int h = pairsPerOpponent > 0 ? leastPlayed(strata, pairsPerOpponent) : Sprt.next(strata);
                if (h < 0 || (pairsPerOpponent == 0 && total(strata) >= maxPairs)) {
                    break;
                }
                Stratum s = strata.get(h);
                int pair = nextPair.merge(s.opponent(), 1, Integer::sum) - 1;
                Battle[] battles = runPair(s.opponent(), pair);
                s.add(measure.of(battles[0]), measure.of(battles[1]));
                printProgress(strata, s, pair, battles);
            }
        }

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("robocode", runner.robocodeVersion());
        environment.put("java", System.getProperty("java.vm.vendor") + " " + System.getProperty("java.version"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        environment.put("cpuConstant", cpuConstant(home));
        environment.put("lastRunSeconds", Long.toString((System.nanoTime() - start) / 1_000_000_000));
        Summary summary = summarize(config, environment, options.get("metric"), bounds, strata, log);
        Path summaryFile = dir.resolve("summary.json");
        Files.writeString(summaryFile, Json.write(summary));
        printReport(summary, System.out);
        System.out.println("Details: " + summaryFile);
    }

    /** The configuration a comparison's log is keyed by; a log of another configuration is refused. */
    static Map<String, String> config(Subject baseline, Subject candidate, OpponentSet set, int rounds,
                                      boolean fixedStarts, long runSeed) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("baseline", baseline.name());
        config.put("baselineJar", baseline.jarSha());
        config.put("candidate", candidate.name());
        config.put("candidateJar", candidate.jarSha());
        config.put("opponentSet", set.name());
        config.put("opponents", String.join(",", set.opponents().stream().map(Opponent::name).toList()));
        config.put("format", set.format().label);
        config.put("field", set.format().width + "x" + set.format().height);
        config.put("rounds", Integer.toString(rounds));
        config.put("starts", fixedStarts ? "fixed" : "random");
        config.put("seed", Long.toString(runSeed));
        return config;
    }

    /** The comparison's directory under the opponent set's, from the versions as given and the configuration. */
    static String dirName(String baselineVersion, String candidateVersion, Map<String, String> config) {
        return String.format(Locale.ROOT, "%s-%s_vs_%s-%s_%s_s%s", baselineVersion,
                config.get("baselineJar").substring(0, 6), candidateVersion, config.get("candidateJar").substring(0, 6),
                config.get("starts"), config.get("seed"));
    }

    static boolean fixedStarts(String starts) {
        return switch (starts) {
            case "fixed" -> true;
            case "random" -> false;
            default -> throw new IllegalArgumentException("starts: fixed or random");
        };
    }

    private Subject subject(Side side, String className, String version) throws IOException {
        String name = className + " " + version;
        Path jar = runner.jarFile(name)
                .orElseThrow(() -> new IllegalArgumentException(name + " does not come from a jar"));
        return new Subject(side, name, options.get("robot") + " " + version, sha256(jar));
    }

    /** Runs both battles of a pair on one seed, alternating which version goes first. */
    private Battle[] runPair(String opponent, int pair) throws IOException {
        long seed = pairSeed(runSeed, opponent, pair);
        Battle[] battles = new Battle[2];
        List<Subject> order = pair % 2 == 0 ? List.of(baseline, candidate) : List.of(candidate, baseline);
        for (Subject subject : order) {
            List<String> robots = List.of(subject.name(), opponent);
            BattleSetup setup = new BattleSetup(format.label + " vs " + opponent, format.width, format.height,
                    rounds, robots, seed);
            if (fixedStarts) {
                setup = setup.withStarts(Starts.random(mix(seed), robotCount(robots), format.width, format.height));
            }
            BattleOutcome outcome = runner.run(setup);
            Battle battle = battle(outcome, subject, opponent, pair);
            log.append(battle);
            battles[subject.side().ordinal()] = battle;
            if (!outcome.problems().isEmpty()) {
                List<String> lines = outcome.problems().stream()
                        .map(p -> subject.side() + " " + opponent + " pair " + pair + ": " + p).toList();
                lines.forEach(l -> System.out.println("! " + l));
                Files.write(problemsFile, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        }
        return battles;
    }

    private int robotCount(List<String> robots) {
        return robotCounts.computeIfAbsent(String.join(",", robots), k -> runner.robotCount(robots));
    }

    /** The log's line of a battle of {@code subject} against {@code opponent}. */
    static Battle battle(BattleOutcome outcome, Subject subject, String opponent, int pair) {
        ContestantResult mine = outcome.result(subject.name()).orElseThrow(
                () -> new IllegalStateException("no result for " + subject.name() + ": " + outcome.results()));
        ContestantResult theirs = outcome.results().stream().filter(r -> r != mine).findFirst().orElseThrow();
        int total = mine.score() + theirs.score();
        double share = total == 0 ? 50 : 100.0 * mine.score() / total;
        int skipped = outcome.skippedTurns(subject.robotPrefix());
        BattleOutcome.Shots shots = outcome.shots(subject.name());
        BattleOutcome.Shots theirShots = outcome.shots().entrySet().stream()
                .filter(e -> !e.getKey().startsWith(subject.name())).map(Map.Entry::getValue).findFirst()
                .orElse(BattleOutcome.Shots.NONE);
        int allSkipped = outcome.skippedTurns().values().stream().mapToInt(Integer::intValue).sum();
        return new Battle(opponent, pair, subject.side(), outcome.seed(), share, mine.score(), theirs.score(),
                mine.survival(), mine.lastSurvivorBonus(), mine.bulletDamage(), mine.bulletDamageBonus(),
                mine.ramDamage(), mine.ramDamageBonus(), mine.firsts(), theirs.firsts(), shots.fired(), shots.hits(),
                theirShots.fired(), theirShots.hits(), skipped,
                allSkipped - skipped, outcome.problems().size(), outcome.startMismatches(), outcome.elapsedMillis());
    }

    /** In fixed mode: the opponent with the fewest pairs below the target, or −1 when all have reached it. */
    private static int leastPlayed(List<Stratum> strata, int target) {
        return leastPlayed(strata, new int[strata.size()], target);
    }

    /** The same, counting {@code pending[h]} pairs still being played against opponent h as played. */
    static int leastPlayed(List<Stratum> strata, int[] pending, int target) {
        int best = -1;
        for (int i = 0; i < strata.size(); i++) {
            int n = strata.get(i).size() + pending[i];
            if (n < target && (best < 0 || n < strata.get(best).size() + pending[best])) {
                best = i;
            }
        }
        return best;
    }

    /** Seed of a pair: depends only on the run seed, the opponent and the pair's index for that opponent. */
    static long pairSeed(long runSeed, String opponent, int pair) {
        return mix(mix(runSeed ^ opponent.hashCode()) + pair);
    }

    /** SplitMix64's finaliser. */
    static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static int total(List<Stratum> strata) {
        return strata.stream().mapToInt(Stratum::size).sum();
    }

    private void printProgress(List<Stratum> strata, Stratum s, int pair, Battle[] battles) {
        Estimate d = Sprt.estimate(strata, Metric.DIFFERENCE, x -> true);
        System.out.printf(Locale.ROOT,
                "%5d  %-28s #%-3d base %6.2f  cand %6.2f  Δ %+6.2f  |  est. Δ %+6.2f ± %5.2f  LLR %+6.2f%n",
                total(strata), s.opponent(), pair, measure.of(battles[0]),
                measure.of(battles[1]), measure.of(battles[1]) - measure.of(battles[0]), d.mean(), d.halfWidth(),
                Sprt.llr(d, bounds));
    }

    /**
     * The scoreboards (all, strong, then one per other named group), the per-opponent rows, noise and health of a
     * comparison, from its strata and its log.
     */
    static Summary summarize(Map<String, String> config, Map<String, String> environment, String metric,
                             Bounds bounds, List<Stratum> strata, CompareLog log) {
        Map<String, List<Battle[]>> pairsByOpponent = log.completePairs();
        List<OpponentRow> rows = strata.stream()
                .map(s -> row(s, pairsByOpponent.getOrDefault(s.opponent(), List.of())))
                .toList();
        List<Scoreboard> boards = new ArrayList<>();
        boards.add(scoreboard("all", strata, rows, x -> true));
        if (strata.stream().anyMatch(Stratum::strong)) {
            boards.add(scoreboard("strong", strata, rows, Stratum::strong));
        }
        // Every other named group of the set but [other], in the order of the set.
        strata.stream().map(Stratum::group).filter(g -> !g.isEmpty() && !g.equals("strong") && !g.equals("other"))
                .distinct().forEach(g -> boards.add(scoreboard(g, strata, rows, s -> s.group().equals(g))));

        Estimate d = Sprt.estimate(strata, Metric.DIFFERENCE, x -> true);
        int pairs = total(strata);
        double sigma = Math.sqrt(d.variance() * pairs);
        double va = Sprt.pooledVariance(strata, Metric.BASELINE);
        double vb = Sprt.pooledVariance(strata, Metric.CANDIDATE);
        double vd = Sprt.pooledVariance(strata, Metric.DIFFERENCE);
        Noise noise = new Noise(sigma, Math.sqrt(va), Math.sqrt(vd), (va + vb - vd) / (2 * Math.sqrt(va * vb)),
                Sprt.expectedPairs(sigma, bounds, bounds.delta0()),
                Sprt.expectedPairs(sigma, bounds, (bounds.delta0() + bounds.delta1()) / 2));

        int[] skipped = new int[3];
        int problems = 0;
        int mismatches = 0;
        long battleMillis = 0;
        for (Battle b : log.battles()) {
            skipped[b.side().ordinal()] += b.skippedTurns();
            skipped[2] += b.opponentSkippedTurns();
            problems += b.problems();
            mismatches += b.startMismatches();
            battleMillis += b.elapsedMillis();
        }
        Health health = new Health(skipped[0], skipped[1], skipped[2], problems, mismatches, battleMillis / 1000);

        Decision decision = Sprt.decide(strata, bounds);
        return new Summary(config, environment, metric, bounds.toString(), describe(decision, bounds),
                Sprt.llr(d, bounds), bounds.lower(), bounds.upper(), pairs, boards, rows, noise, health);
    }

    private static OpponentRow row(Stratum s, List<Battle[]> pairs) {
        double[] share = new double[2];
        double[] wins = new double[2];
        int[] fired = new int[2];
        int[] hits = new int[2];
        int[] firedAt = new int[2];
        int[] hitBy = new int[2];
        for (Battle[] pair : pairs) {
            for (int side = 0; side < 2; side++) {
                Battle b = pair[side];
                share[side] += b.share() / pairs.size();
                wins[side] += (b.share() > 50 ? 1 : b.share() == 50 ? 0.5 : 0) / pairs.size();
                fired[side] += b.fired();
                hits[side] += b.hits();
                firedAt[side] += b.opponentFired();
                hitBy[side] += b.opponentHits();
            }
        }
        boolean none = pairs.isEmpty();
        return new OpponentRow(s.opponent(), s.strong(), s.size(), s.mean(Metric.BASELINE),
                s.mean(Metric.CANDIDATE), s.mean(Metric.DIFFERENCE), Math.sqrt(s.variance(Metric.BASELINE)),
                Math.sqrt(s.variance(Metric.CANDIDATE)), Math.sqrt(s.variance(Metric.DIFFERENCE)), s.correlation(),
                none ? Double.NaN : share[0], none ? Double.NaN : share[1], none ? Double.NaN : 100 * wins[0],
                none ? Double.NaN : 100 * wins[1], rate(hits[0], fired[0]), rate(hits[1], fired[1]),
                rate(hitBy[0], firedAt[0]), rate(hitBy[1], firedAt[1]));
    }

    private static double rate(int hits, int fired) {
        return fired == 0 ? Double.NaN : 100.0 * hits / fired;
    }

    private static Scoreboard scoreboard(String name, List<Stratum> strata, List<OpponentRow> rows,
                                         Predicate<Stratum> include) {
        Estimate base = Sprt.estimate(strata, Metric.BASELINE, include);
        Estimate cand = Sprt.estimate(strata, Metric.CANDIDATE, include);
        Estimate diff = Sprt.estimate(strata, Metric.DIFFERENCE, include);
        List<OpponentRow> selected = new ArrayList<>();
        for (int i = 0; i < strata.size(); i++) {
            if (include.test(strata.get(i))) {
                selected.add(rows.get(i));
            }
        }
        return new Scoreboard(name, selected.size(), base.mean(), cand.mean(), diff.mean(), diff.halfWidth(),
                mean(selected, OpponentRow::shareBaseline), mean(selected, OpponentRow::shareCandidate),
                mean(selected, OpponentRow::pwinBaseline), mean(selected, OpponentRow::pwinCandidate),
                mean(selected, OpponentRow::hitRateBaseline), mean(selected, OpponentRow::hitRateCandidate),
                mean(selected, OpponentRow::hitByBaseline), mean(selected, OpponentRow::hitByCandidate));
    }

    /** Mean over the rows that have the value; NaN if none has. */
    private static double mean(List<OpponentRow> rows, ToDoubleFunction<OpponentRow> value) {
        return rows.stream().mapToDouble(value).filter(v -> !Double.isNaN(v)).average().orElse(Double.NaN);
    }

    static String describe(Decision decision, Bounds bounds) {
        return switch (decision) {
            case H1 -> String.format(Locale.ROOT, "H1: the candidate's gain is nearer %+.2f than %+.2f",
                    bounds.delta1(), bounds.delta0());
            case H0 -> String.format(Locale.ROOT, "H0: the candidate's gain is nearer %+.2f than %+.2f",
                    bounds.delta0(), bounds.delta1());
            case CONTINUE -> "undecided";
        };
    }

    static void printReport(Summary s, PrintStream out) {
        out.println();
        out.printf(Locale.ROOT, "%-30s %5s %7s %7s %7s %7s %7s %6s | %13s | %13s%n",
                "opponent (" + s.metric() + ")", "pairs", "base", "cand", "Δ", "sd(Δ)", "sd(base)", "ρ",
                "hit % b/c", "hit by % b/c");
        for (OpponentRow r : s.opponents()) {
            out.printf(Locale.ROOT,
                    "%-30s %5d %7.2f %7.2f %+7.2f %7.2f %7.2f %6.2f | %6.2f %6.2f | %6.2f %6.2f%n",
                    r.opponent() + (r.strong() ? " *" : ""), r.pairs(), r.baseline(), r.candidate(), r.difference(),
                    r.sdDifference(), r.sdBaseline(), r.correlation(), r.hitRateBaseline(), r.hitRateCandidate(),
                    r.hitByBaseline(), r.hitByCandidate());
        }
        out.println();
        for (Scoreboard b : s.scoreboards()) {
            out.printf(Locale.ROOT, "%-8s (%2d opponents)  %s base %6.2f  cand %6.2f  Δ %+6.2f ± %5.2f%n",
                    b.name(), b.opponents(), s.metric(), b.baseline(), b.candidate(), b.difference(), b.halfWidth());
            out.printf(Locale.ROOT, "%26s APS %6.2f / %6.2f   PWIN %5.1f / %5.1f   hit %% %5.2f / %5.2f   "
                            + "hit by %% %5.2f / %5.2f%n", "", b.apsBaseline(), b.apsCandidate(), b.pwinBaseline(),
                    b.pwinCandidate(), b.hitRateBaseline(), b.hitRateCandidate(), b.hitByBaseline(),
                    b.hitByCandidate());
        }
        Noise n = s.noise();
        out.printf(Locale.ROOT, "noise    σ per pair %.2f, within-opponent sd base %.2f, sd Δ %.2f, ρ %.2f; "
                        + "expected pairs %.0f at the bounds, %.0f midway%n", n.sigmaPair(), n.pooledSdBaseline(),
                n.pooledSdDifference(), n.pooledCorrelation(), n.expectedPairsAtBounds(), n.expectedPairsMidway());
        Health h = s.health();
        out.printf(Locale.ROOT, "health   skipped turns base %d, cand %d, opponents %d; problems %d; "
                        + "start mismatches %d; %d s in battles%n", h.skippedTurnsBaseline(),
                h.skippedTurnsCandidate(), h.skippedTurnsOpponents(), h.problems(), h.startMismatches(),
                h.battleSeconds());
        out.printf(Locale.ROOT, "result   %d pairs, LLR %+.2f in [%.2f, %.2f], bounds %s: %s%n", s.pairs(),
                s.llr(), s.llrLower(), s.llrUpper(), s.bounds(), s.decision());
    }

    static String cpuConstant(Path home) throws IOException {
        Path file = home.resolve("config/robocode.properties");
        if (!Files.exists(file)) {
            return "unknown";
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        return p.getProperty("robocode.cpu.constant", "unknown");
    }

    static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
