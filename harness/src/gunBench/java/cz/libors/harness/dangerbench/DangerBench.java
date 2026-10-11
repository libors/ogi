package cz.libors.harness.dangerbench;

import cz.libors.ogi.move.DangerParameters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The offline danger bench: replays the enemy bullets of recorded battles ({@code recordBattles} or
 * {@code submitRecordings}, full recordings) through the surfer's danger model with some parameters, and reports how
 * much of each profile lay where the bullet really went.
 *
 * <p>Usage: {@code DangerBench recordings=<dir>,... [robot=<name prefix>] [selection=<tsv>] [preset=default|histogram]
 * [weights=w,...] [neighbourScale=x] [maxNeighbours=n] [bandwidth=x] [knnShare=x] [knnRamp=x] [priorWeight=x]
 * [simplePrior=x] [simpleBandwidth=x] [temper=x] [decay=x] [share=x] [flatPrior=x] [flatWindow=n] [tune=<passes>]}
 *
 * <p>{@code selection} is a tab-separated file with the columns {@code stratum} and {@code name}; the report then
 * gives a mean per stratum. {@code tune} runs a coordinate search from the given parameters on the recordings of pair
 * 0 and reports the result on the others (other seeds).
 */
public final class DangerBench {

    private static final List<String> NAMES = List.of("w0", "w1", "w2", "w3", "w4", "w5", "w6", "w7", "w8", "w9",
            "neighbourScale", "maxNeighbours", "bandwidth", "knnShare", "knnRamp", "priorWeight", "simplePrior",
            "simpleBandwidth", "temper", "decay", "share", "flatPrior", "flatWindow");

    private DangerBench() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new HashMap<>(Map.of("robot", "cz.libors.Ogi"));
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq <= 0) {
                usage("not key=value: " + arg);
            }
            options.put(arg.substring(0, eq), arg.substring(eq + 1));
        }
        if (!options.containsKey("recordings")) {
            usage("missing recordings");
        }
        List<Path> files = new ArrayList<>();
        for (String dir : options.get("recordings").split(",")) {
            try (Stream<Path> list = Files.list(Path.of(dir.trim()))) {
                list.filter(p -> p.getFileName().toString().endsWith(".rec.gz")).sorted().forEach(files::add);
            }
        }
        if (files.isEmpty()) {
            usage("no *.rec.gz in " + options.get("recordings"));
        }
        Map<String, String> strata = options.containsKey("selection")
                ? readSelection(Path.of(options.get("selection"))) : Map.of();
        DangerParameters parameters = parameters(options);

        long start = System.nanoTime();
        List<DangerBattle> battles = files.parallelStream().map(f -> {
            try {
                return DangerBattle.read(f, options.get("robot"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).toList();
        System.out.printf(Locale.ROOT, "%d battles read in %.1f s%n", battles.size(), (System.nanoTime() - start) / 1e9);

        if (options.containsKey("tune")) {
            List<DangerBattle> train = battles.stream().filter(b -> pair(b) == 0).toList();
            List<DangerBattle> test = battles.stream().filter(b -> pair(b) != 0).toList();
            System.out.printf(Locale.ROOT, "Tuning on %d battles (pair 0), testing on %d%n", train.size(), test.size());
            DangerParameters tuned = tune(train, parameters, Integer.parseInt(options.get("tune")));
            System.out.println();
            for (DangerParameters p : List.of(DangerParameters.HISTOGRAM, parameters, tuned)) {
                System.out.println("Parameters: " + p);
                summary("  train", train, p, strata);
                summary("  test ", test, p, strata);
            }
            return;
        }
        Map<String, DangerReplay.Tally> byOpponent = evaluate(battles, parameters);
        System.out.println("Parameters: " + parameters);
        System.out.printf(Locale.ROOT, "%-40s %-6s %7s %7s %9s %8s %8s%n", "opponent", "stratum", "shots", "learned",
                "mean ln", "mass %", "hits %");
        for (Map.Entry<String, DangerReplay.Tally> e : byOpponent.entrySet()) {
            DangerReplay.Tally t = e.getValue();
            System.out.printf(Locale.ROOT, "%-40s %-6s %7d %7d %9.3f %8.2f %8.2f%n", e.getKey(),
                    strata.getOrDefault(e.getKey(), ""), t.shots(), t.learned(), t.meanLog(), 100 * t.meanMass(),
                    100 * t.meanHitMass());
        }
        summary("", battles, parameters, strata);
    }

    /** Per opponent, the tallies of its battles. */
    static Map<String, DangerReplay.Tally> evaluate(List<DangerBattle> battles, DangerParameters parameters) {
        List<DangerReplay.Tally> tallies = battles.parallelStream().map(b -> DangerReplay.replay(b, parameters))
                .toList();
        Map<String, DangerReplay.Tally> byOpponent = new TreeMap<>();
        for (int i = 0; i < battles.size(); i++) {
            byOpponent.merge(battles.get(i).opponent(), tallies.get(i), DangerReplay.Tally::plus);
        }
        return byOpponent;
    }

    /**
     * The mean over opponents of the mean log share per bullet: what the search maximises. Opponents without a
     * bullet do not count; one whose bullets score NaN makes the whole objective NaN, so parameters that break the
     * model cannot win by leaving opponents out.
     */
    static double objective(List<DangerBattle> battles, DangerParameters parameters) {
        return evaluate(battles, parameters).values().stream().filter(t -> t.shots() > 0)
                .mapToDouble(DangerReplay.Tally::meanLog).average().orElse(Double.NaN);
    }

    private static void summary(String label, List<DangerBattle> battles, DangerParameters parameters,
                                Map<String, String> strata) {
        Map<String, DangerReplay.Tally> byOpponent = evaluate(battles, parameters);
        Map<String, List<DangerReplay.Tally>> groups = new LinkedHashMap<>();
        groups.put("all", new ArrayList<>(byOpponent.values()));
        for (Map.Entry<String, DangerReplay.Tally> e : byOpponent.entrySet()) {
            String stratum = strata.get(e.getKey());
            if (stratum != null) {
                groups.computeIfAbsent(stratum, k -> new ArrayList<>()).add(e.getValue());
            }
        }
        StringBuilder line = new StringBuilder(label.isEmpty() ? "mean over opponents:" : label + ":");
        for (Map.Entry<String, List<DangerReplay.Tally>> g : groups.entrySet()) {
            line.append(String.format(Locale.ROOT, "  %s (%d) ln %.3f, mass %.2f %%, hits %.2f %%", g.getKey(),
                    g.getValue().size(), mean(g.getValue(), DangerReplay.Tally::meanLog),
                    100 * mean(g.getValue(), DangerReplay.Tally::meanMass),
                    100 * mean(g.getValue(), DangerReplay.Tally::meanHitMass)));
        }
        System.out.println(line);
    }

    private static double mean(List<DangerReplay.Tally> tallies,
                               java.util.function.ToDoubleFunction<DangerReplay.Tally> value) {
        return tallies.stream().mapToDouble(value).filter(v -> !Double.isNaN(v)).average().orElse(Double.NaN);
    }

    /**
     * Coordinate search: each parameter in turn tries a few multiples of its value (the weights also 0, the share
     * a few steps), and keeps the best if it raises the objective. Stops after {@code passes} passes or a pass without
     * a gain.
     */
    static DangerParameters tune(List<DangerBattle> train, DangerParameters start, int passes) {
        double[] x = vector(start);
        double best = objective(train, start);
        System.out.printf(Locale.ROOT, "start: %.4f%n", best);
        for (int pass = 1; pass <= passes; pass++) {
            boolean improved = false;
            for (int i = 0; i < x.length; i++) {
                double current = x[i];
                for (double candidate : candidates(i, current)) {
                    double[] y = x.clone();
                    y[i] = candidate;
                    DangerParameters p;
                    try {
                        p = parameters(y);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    double v = objective(train, p);
                    if (v > best + 1e-4) {
                        best = v;
                        x = y;
                        improved = true;
                    }
                }
                if (x[i] != current) {
                    System.out.printf(Locale.ROOT, "pass %d: %s %s -> %s, %.4f%n", pass, NAMES.get(i),
                            fmt(current), fmt(x[i]), best);
                }
            }
            if (!improved) {
                break;
            }
        }
        return parameters(x);
    }

    private static double[] candidates(int i, double v) {
        String name = NAMES.get(i);
        if (name.equals("decay")) {
            return Arrays.stream(new double[] {v - 0.1, v - 0.03, v + 0.03, v + 0.1})
                    .map(c -> Math.max(0, Math.min(1, c))).filter(c -> c != v).distinct().toArray();
        }
        if (name.equals("knnShare") || name.equals("share")) {
            return Arrays.stream(new double[] {v - 0.2, v - 0.1, v + 0.1, v + 0.2})
                    .map(c -> Math.max(0, Math.min(1, c))).filter(c -> c != v).distinct().toArray();
        }
        if (name.equals("maxNeighbours") || name.equals("flatWindow")) {
            return Arrays.stream(new double[] {v / 2, v * 0.7, v * 1.4, v * 2}).map(c -> Math.max(1, Math.round(c)))
                    .filter(c -> c != v).distinct().toArray();
        }
        double[] steps = v == 0 ? new double[] {0.01, 0.1, 0.5, 1, 2}
                : new double[] {v * 0.5, v * 0.7, v * 1.4, v * 2};
        if (name.startsWith("w") && v != 0) {
            // A weight may also switch its feature off.
            steps = Arrays.copyOf(steps, steps.length + 1);
        }
        return steps;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4g", v);
    }

    private static double[] vector(DangerParameters p) {
        double[] x = Arrays.copyOf(p.weights(), NAMES.size());
        x[10] = p.neighbourScale();
        x[11] = p.maxNeighbours();
        x[12] = p.bandwidth();
        x[13] = p.knnShare();
        x[14] = p.knnRamp();
        x[15] = p.priorWeight();
        x[16] = p.simplePrior();
        x[17] = p.simpleBandwidth();
        x[18] = p.temper();
        x[19] = p.decay();
        x[20] = p.share();
        x[21] = p.flatPrior();
        x[22] = p.flatWindow();
        return x;
    }

    private static DangerParameters parameters(double[] x) {
        return new DangerParameters(Arrays.copyOf(x, 10), x[10], (int) Math.round(x[11]), x[12], x[13], x[14], x[15],
                x[16], x[17], x[18], x[19], x[20], x[21], (int) Math.round(x[22]));
    }

    /** The pair of a recording named {@code <opponent>_p<pair>.rec.gz}. */
    private static int pair(DangerBattle b) {
        String name = b.file().getFileName().toString();
        int at = name.lastIndexOf("_p");
        return Integer.parseInt(name.substring(at + 2, name.length() - ".rec.gz".length()));
    }

    private static Map<String, String> readSelection(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> header = Arrays.asList(lines.get(0).split("\t"));
        int stratum = header.indexOf("stratum");
        int name = header.indexOf("name");
        if (stratum < 0 || name < 0) {
            throw new IOException(file + ": needs the columns stratum and name");
        }
        Map<String, String> strata = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\t");
            strata.put(f[name], f[stratum]);
        }
        return strata;
    }

    private static DangerParameters parameters(Map<String, String> options) {
        DangerParameters d = switch (options.getOrDefault("preset", "default")) {
            case "default" -> DangerParameters.DEFAULT;
            case "histogram" -> DangerParameters.HISTOGRAM;
            default -> throw new IllegalArgumentException("unknown preset: " + options.get("preset"));
        };
        double[] weights = options.containsKey("weights")
                ? Arrays.stream(options.get("weights").split(",")).mapToDouble(s -> Double.parseDouble(s.trim()))
                .toArray()
                : d.weights();
        return new DangerParameters(weights,
                Double.parseDouble(options.getOrDefault("neighbourScale", String.valueOf(d.neighbourScale()))),
                Integer.parseInt(options.getOrDefault("maxNeighbours", String.valueOf(d.maxNeighbours()))),
                Double.parseDouble(options.getOrDefault("bandwidth", String.valueOf(d.bandwidth()))),
                Double.parseDouble(options.getOrDefault("knnShare", String.valueOf(d.knnShare()))),
                Double.parseDouble(options.getOrDefault("knnRamp", String.valueOf(d.knnRamp()))),
                Double.parseDouble(options.getOrDefault("priorWeight", String.valueOf(d.priorWeight()))),
                Double.parseDouble(options.getOrDefault("simplePrior", String.valueOf(d.simplePrior()))),
                Double.parseDouble(options.getOrDefault("simpleBandwidth", String.valueOf(d.simpleBandwidth()))),
                Double.parseDouble(options.getOrDefault("temper", String.valueOf(d.temper()))),
                Double.parseDouble(options.getOrDefault("decay", String.valueOf(d.decay()))),
                Double.parseDouble(options.getOrDefault("share", String.valueOf(d.share()))),
                Double.parseDouble(options.getOrDefault("flatPrior", String.valueOf(d.flatPrior()))),
                Integer.parseInt(options.getOrDefault("flatWindow", String.valueOf(d.flatWindow()))));
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("Usage: DangerBench recordings=<dir>,... [robot=<name prefix>] [selection=<tsv>] "
                + "[preset=default|histogram] [weights=w,...] [neighbourScale=x] [maxNeighbours=n] [bandwidth=x] "
                + "[knnShare=x] [knnRamp=x] [priorWeight=x] [simplePrior=x] [simpleBandwidth=x] [temper=x] [decay=x] "
                + "[share=x] [flatPrior=x] [flatWindow=n] [tune=<passes>]");
        System.exit(2);
    }
}
