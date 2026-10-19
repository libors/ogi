package cz.libors.harness.compare;

import cz.libors.harness.BattleOutcome;
import cz.libors.harness.BattleRunner;
import cz.libors.harness.BattleSetup;
import cz.libors.harness.Starts;
import cz.libors.harness.compare.OpponentSet.Format;
import cz.libors.harness.recording.TurnRecorder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Records the battles of a comparison turn by turn: each version against each opponent on the pair seeds that
 * {@link Compare} uses with the same run seed, so a pair from a comparison log can be replayed and examined. Like
 * {@link Compare}, it plays {@code robot}, or {@code team} against a team set.
 *
 * <p>Usage: {@code RecordBattles <robocodeHome> robot=<class> versions=<v>,<v> opponents=<file> out=<dir>
 * [team=<class>] [only=<opponent>,...] [pairs=n] [seed=n] [rounds=n] [starts=random|fixed]
 * [keys=<debug key>,...]}, against a melee set {@code [draws=n]} instead of {@code only} and {@code pairs}: the draws
 * of {@link MeleeJob}, recorded into {@code <out>/<version>/draw<d>.rec.gz}, with "melee draw d" as the opponent in
 * the index. An option the set's format does not use is refused ({@link #check}); of a key given twice, the last
 * value counts.
 *
 * <p>Writes {@code <out>/<version>/<opponent>_p<pair>.rec.gz}, one recording per battle (format in
 * {@link TurnRecorder}); with {@code keys}, only those debug properties. Each battle also appends a line to
 * {@code <out>/<version>/battles.tsv}: the recording's file name, the opponent, the pair, the seed, the robot's
 * bullets fired and hits as {@link BattleOutcome#shots} counts them, its skipped turns and the number of problems
 * (a team's: its robots'). A battle recorded again appends a new line; the last one for a file describes it.
 */
public final class RecordBattles {

    private static final Map<String, String> DEFAULTS = Map.of(
            "only", "",
            "pairs", "1",
            "seed", "1",
            "rounds", "0",
            "starts", "random",
            "keys", "",
            "draws", "1");

    private static final List<String> REQUIRED = List.of("robot", "versions", "opponents", "out");

    private RecordBattles() {
    }

    public static void main(String[] args) throws IOException {
        // The last value of a key counts, so a caller can override one the task passed.
        Map<String, String> given = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq <= 0) {
                usage("not key=value: " + args[i]);
            }
            given.put(args[i].substring(0, eq), args[i].substring(eq + 1));
        }
        Map<String, String> options = new HashMap<>(DEFAULTS);
        options.putAll(given);
        if (args.length < 1 || !options.keySet().containsAll(REQUIRED)) {
            usage("missing one of " + REQUIRED);
        }
        OpponentSet set = OpponentSet.read(Path.of(options.get("opponents")));
        try {
            check(given, set);
        } catch (IllegalArgumentException e) {
            usage(e.getMessage());
        }
        Format format = set.format();
        String subjectClass = subjectClass(format, options);
        List<String> only = options.get("only").isEmpty() ? List.of()
                : Arrays.stream(options.get("only").split(",")).map(String::trim).toList();
        List<String> names = set.opponents().stream().map(OpponentSet.Opponent::name).toList();
        List<String> unknown = only.stream().filter(o -> !names.contains(o)).toList();
        if (!unknown.isEmpty()) {
            usage("not in " + set.name() + " (name and version as the set lists them): " + unknown);
        }
        List<String> opponents = names.stream().filter(o -> only.isEmpty() || only.contains(o)).toList();
        int rounds = Integer.parseInt(options.get("rounds")) > 0 ? Integer.parseInt(options.get("rounds"))
                : format.rounds;
        boolean fixedStarts = switch (options.get("starts")) {
            case "fixed" -> true;
            case "random" -> false;
            default -> {
                usage("starts: fixed or random");
                yield false;
            }
        };
        long runSeed = Long.parseLong(options.get("seed"));
        int pairs = Integer.parseInt(options.get("pairs"));
        Path out = Path.of(options.get("out"));
        Set<String> keys = options.get("keys").isEmpty() ? null
                : Set.copyOf(Arrays.stream(options.get("keys").split(",")).map(String::trim).toList());
        if (format == Format.MELEE) {
            recordDraws(Path.of(args[0]), set, options, rounds, runSeed, out, keys);
            System.exit(0);
        }
        try (BattleRunner runner = new BattleRunner(Path.of(args[0]))) {
            for (String opponent : opponents) {
                for (int pair = 0; pair < pairs; pair++) {
                    long seed = Compare.pairSeed(runSeed, opponent, pair);
                    for (String version : Arrays.asList(options.get("versions").split(","))) {
                        List<String> robots = List.of(subjectClass + " " + version, opponent);
                        BattleSetup setup = new BattleSetup(format.label + " vs " + opponent, format.width,
                                format.height, rounds, robots, seed);
                        if (fixedStarts) {
                            setup = setup.withStarts(Starts.random(Compare.mix(seed), runner.robotCount(robots),
                                    format.width, format.height));
                        }
                        Path file = out.resolve(version).resolve(fileName(opponent) + "_p" + pair + ".rec.gz");
                        BattleOutcome outcome;
                        try (TurnRecorder recorder = TurnRecorder.open(file, setup, keys)) {
                            outcome = runner.run(setup, recorder);
                        }
                        index(file, opponent, pair, seed, outcome, robots.get(0));
                        System.out.printf(Locale.ROOT, "%-10s %-34s pair %d  seed %20d  problems %d  (%.1f s)%n",
                                version, opponent, pair, seed, outcome.problems().size(),
                                outcome.elapsedMillis() / 1000.0);
                    }
                }
            }
        }
        System.exit(0);
    }

    /**
     * Refuses an option the opponent set's format does not use, as {@link RecordJob#check} does, rather than ignore
     * it: {@code pairs}, {@code only} and fixed starts against a melee set, {@code draws} against the others; and a
     * team set without its {@code team}.
     */
    static void check(Map<String, String> given, OpponentSet set) {
        if (set.format() == Format.MELEE) {
            if (given.containsKey("pairs")) {
                throw new IllegalArgumentException("a melee set has draws, not opponents: use draws=n");
            }
            if (!given.getOrDefault("only", "").isEmpty() || "fixed".equals(given.get("starts"))) {
                throw new IllegalArgumentException(set.name() + " is a melee set: random starts and draws only");
            }
        } else if (given.containsKey("draws")) {
            throw new IllegalArgumentException("draws are for melee sets; " + set.name() + " has pairs");
        }
        if (set.format() == Format.TEAM && given.get("team") == null) {
            throw new IllegalArgumentException(set.name() + " is a team set: give our team as team=<class>");
        }
    }

    /** The subject's class: {@code team} against a team set, else {@code robot}, as {@link Compare} plays them. */
    static String subjectClass(Format format, Map<String, String> options) {
        return format == Format.TEAM ? options.get("team") : options.get("robot");
    }

    /** Draws 0 to {@code draws} − 1 of a melee set, each version at the draw's place among its nine opponents. */
    private static void recordDraws(Path home, OpponentSet set, Map<String, String> options, int rounds, long runSeed,
                                    Path out, Set<String> keys) throws IOException {
        MeleeDraws draws = new MeleeDraws(set);
        int count = Integer.parseInt(options.get("draws"));
        try (BattleRunner runner = new BattleRunner(home)) {
            for (int d = 0; d < count; d++) {
                MeleeDraws.Draw draw = draws.draw(runSeed, d);
                for (String version : Arrays.asList(options.get("versions").split(","))) {
                    String subject = options.get("robot") + " " + version;
                    BattleSetup setup = new BattleSetup("melee draw " + d, Format.MELEE.width, Format.MELEE.height,
                            rounds, draw.robots(subject), draw.seed());
                    Path file = out.resolve(version).resolve(RecordJob.drawFileName(d));
                    BattleOutcome outcome;
                    try (TurnRecorder recorder = TurnRecorder.open(file, setup, keys)) {
                        outcome = runner.run(setup, recorder);
                    }
                    index(file, "melee draw " + d, d, draw.seed(), outcome, subject);
                    System.out.printf(Locale.ROOT, "%-10s melee draw %-4d seed %20d  problems %d  (%.1f s)%n",
                            version, d, draw.seed(), outcome.problems().size(), outcome.elapsedMillis() / 1000.0);
                }
            }
        }
    }

    private static void index(Path file, String opponent, int pair, long seed, BattleOutcome outcome, String robot)
            throws IOException {
        Path index = file.getParent().resolve("battles.tsv");
        String header = Files.exists(index) ? ""
                : "file\topponent\tpair\tseed\tfired\thits\tskippedTurns\tproblems\n";
        BattleOutcome.Shots shots = outcome.shots(robot);
        // A team's robots are named after their own class, not after the team.
        RecordJob.Own own = RecordJob.Own.of(outcome, RecordJob.robotsOf(Files.readAllBytes(file), robot));
        String line = String.join("\t", file.getFileName().toString(), opponent, String.valueOf(pair),
                String.valueOf(seed), String.valueOf(shots.fired()), String.valueOf(shots.hits()),
                String.valueOf(own.skippedTurns()), String.valueOf(own.problems())) + "\n";
        Files.writeString(index, header + line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    static String fileName(String robot) {
        return robot.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("Usage: RecordBattles <robocodeHome> robot=<class> versions=<v>,<v> opponents=<file> "
                + "out=<dir> [team=<class>] [only=<opponent>,...] [pairs=n] [seed=n] [rounds=n] "
                + "[starts=random|fixed] [keys=<debug key>,...] [draws=n]");
        System.exit(2);
    }
}
