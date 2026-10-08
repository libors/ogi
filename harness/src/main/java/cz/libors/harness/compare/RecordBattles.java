package cz.libors.harness.compare;

import cz.libors.harness.BattleOutcome;
import cz.libors.harness.BattleRunner;
import cz.libors.harness.BattleSetup;
import cz.libors.harness.Starts;
import cz.libors.harness.compare.OpponentSet.Format;
import cz.libors.harness.recording.TurnRecorder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Records the battles of a comparison turn by turn: each version against each opponent on the pair seeds that
 * {@link Compare} uses with the same run seed, so a pair from a comparison log can be replayed and examined.
 *
 * <p>Usage: {@code RecordBattles <robocodeHome> robot=<class> versions=<v>,<v> opponents=<file> out=<dir>
 * [only=<opponent>,...] [pairs=n] [seed=n] [rounds=n] [starts=random|fixed]}
 *
 * <p>Writes {@code <out>/<version>/<opponent>_p<pair>.rec.gz}, one recording per battle (format in
 * {@link TurnRecorder}).
 */
public final class RecordBattles {

    private static final Map<String, String> DEFAULTS = Map.of(
            "only", "",
            "pairs", "1",
            "seed", "1",
            "rounds", "0",
            "starts", "random");

    private static final List<String> REQUIRED = List.of("robot", "versions", "opponents", "out");

    private RecordBattles() {
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
        OpponentSet set = OpponentSet.read(Path.of(options.get("opponents")));
        Format format = set.format();
        List<String> only = options.get("only").isEmpty() ? List.of() : List.of(options.get("only").split(","));
        List<String> opponents = set.opponents().stream().map(OpponentSet.Opponent::name)
                .filter(o -> only.isEmpty() || only.contains(o)).toList();
        int rounds = Integer.parseInt(options.get("rounds")) > 0 ? Integer.parseInt(options.get("rounds"))
                : format.rounds;
        boolean fixedStarts = options.get("starts").equals("fixed");
        long runSeed = Long.parseLong(options.get("seed"));
        int pairs = Integer.parseInt(options.get("pairs"));
        Path out = Path.of(options.get("out"));
        try (BattleRunner runner = new BattleRunner(Path.of(args[0]))) {
            for (String opponent : opponents) {
                for (int pair = 0; pair < pairs; pair++) {
                    long seed = Compare.pairSeed(runSeed, opponent, pair);
                    for (String version : Arrays.asList(options.get("versions").split(","))) {
                        List<String> robots = List.of(options.get("robot") + " " + version, opponent);
                        BattleSetup setup = new BattleSetup(format.label + " vs " + opponent, format.width,
                                format.height, rounds, robots, seed);
                        if (fixedStarts) {
                            setup = setup.withStarts(Starts.random(Compare.mix(seed), runner.robotCount(robots),
                                    format.width, format.height));
                        }
                        Path file = out.resolve(version).resolve(fileName(opponent) + "_p" + pair + ".rec.gz");
                        BattleOutcome outcome;
                        try (TurnRecorder recorder = TurnRecorder.open(file, setup)) {
                            outcome = runner.run(setup, recorder);
                        }
                        System.out.printf(Locale.ROOT, "%-10s %-34s pair %d  seed %20d  problems %d  (%.1f s)%n",
                                version, opponent, pair, seed, outcome.problems().size(),
                                outcome.elapsedMillis() / 1000.0);
                    }
                }
            }
        }
        System.exit(0);
    }

    private static String fileName(String robot) {
        return robot.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("Usage: RecordBattles <robocodeHome> robot=<class> versions=<v>,<v> opponents=<file> "
                + "out=<dir> [only=<opponent>,...] [pairs=n] [seed=n] [rounds=n] [starts=random|fixed]");
        System.exit(2);
    }
}
