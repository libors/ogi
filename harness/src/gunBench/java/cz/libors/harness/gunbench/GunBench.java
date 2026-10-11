package cz.libors.harness.gunbench;

import cz.libors.ogi.gun.GunParameters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The offline gun bench: replays the gun logs of recorded battles ({@code recordGunLogs}) with some gun parameters
 * and reports the hit rates per opponent. With the bot's parameters every replayed decision must equal the logged
 * one, and the fired hit rate should match the harness's count of the same battles.
 *
 * <p>Usage: {@code GunBench logs=<dir>,... [robot=<name prefix>] [selection=<tsv>] [weights=w,...]
 * [neighbourScale=x] [maxNeighbours=n] [virtualWeight=x] [minTries=x] [tune=<passes>] [maxShielded=x]
 * [splitSeed=n]}
 *
 * <p>A logs directory holds {@code *.rec.gz} gun logs and the {@code battles.tsv} index that {@code RecordBattles}
 * writes. Exits with 1 if a replay with the bot's parameters differs from the log.
 *
 * <p>{@code selection} is a tab-separated file with the columns {@code stratum} and {@code name}; the report then
 * gives a mean per stratum. {@code tune} runs a coordinate search from the given parameters on half of the opponents
 * and reports the result on the other half, which the search never saw (see {@link #tune}).
 */
public final class GunBench {

    private static final List<String> NAMES = List.of("w0 flight time", "w1 lateral velocity",
            "w2 advancing velocity", "w3 acceleration", "w4 since reversal", "w5 since deceleration", "w6 wall ahead",
            "w7 wall behind", "w8 displacement", "w9 curve", "neighbourScale", "maxNeighbours", "virtualWeight",
            "minTries");

    /** One opponent's battles. */
    private record Row(String opponent, int battles, GunReplay.Tally tally, long harnessFired, long harnessHits) {

        double harnessRate() {
            return harnessFired == 0 ? Double.NaN : 100.0 * harnessHits / harnessFired;
        }
    }

    private GunBench() {
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
        if (!options.containsKey("logs")) {
            usage("missing logs");
        }
        GunParameters parameters = parameters(options);
        List<Path> files = new ArrayList<>();
        Map<String, long[]> harnessShots = new HashMap<>();
        for (String dir : options.get("logs").split(",")) {
            Path logs = Path.of(dir.trim());
            harnessShots.putAll(readIndex(logs));
            try (Stream<Path> list = Files.list(logs)) {
                list.filter(p -> p.getFileName().toString().endsWith(".rec.gz")).sorted().forEach(files::add);
            }
        }
        if (files.isEmpty()) {
            usage("no *.rec.gz in " + options.get("logs"));
        }
        Map<String, String> strata = options.containsKey("selection")
                ? readSelection(Path.of(options.get("selection"))) : Map.of();
        long start = System.nanoTime();
        List<GunBattle> battles = files.parallelStream().map(f -> {
            try {
                return GunBattle.read(f, options.get("robot"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).toList();
        long loaded = System.nanoTime();

        if (options.containsKey("tune")) {
            System.out.printf(Locale.ROOT, "%d battles read in %.1f s%n", battles.size(), (loaded - start) / 1e9);
            tuneAndReport(battles, parameters, strata, Integer.parseInt(options.get("tune")),
                    Double.parseDouble(options.getOrDefault("maxShielded", "0.15")),
                    Long.parseLong(options.getOrDefault("splitSeed", "1")));
            System.exit(0);
        }

        List<GunReplay.Tally> tallies = battles.parallelStream().map(b -> GunReplay.replay(b, parameters)).toList();
        long replayed = System.nanoTime();

        Map<String, List<Integer>> byOpponent = new TreeMap<>();
        for (int i = 0; i < battles.size(); i++) {
            byOpponent.computeIfAbsent(battles.get(i).opponent(), k -> new ArrayList<>()).add(i);
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : byOpponent.entrySet()) {
            GunReplay.Tally tally = GunReplay.Tally.ZERO;
            long fired = 0;
            long hits = 0;
            for (int i : e.getValue()) {
                tally = tally.plus(tallies.get(i));
                long[] shots = harnessShots.get(key(battles.get(i).file()));
                if (shots != null) {
                    fired += shots[0];
                    hits += shots[1];
                }
            }
            rows.add(new Row(e.getKey(), e.getValue().size(), tally, fired, hits));
        }
        print(parameters, rows, battles.size(), (loaded - start) / 1e9, (replayed - loaded) / 1e9);
        if (!strata.isEmpty()) {
            Map<String, GunReplay.Tally> tallyByOpponent = new TreeMap<>();
            rows.forEach(r -> tallyByOpponent.put(r.opponent(), r.tally()));
            System.out.println(groups(tallyByOpponent, strata).entrySet().stream()
                    .map(g -> String.format(Locale.ROOT, "%s (%d) %.2f", g.getKey(), g.getValue().size(),
                            meanFired(g.getValue(), tallyByOpponent)))
                    .collect(Collectors.joining(", ", "fired % per stratum: ", "")));
        }

        long differing = rows.stream().mapToLong(r -> r.tally().differing()).sum();
        long orphans = rows.stream().mapToLong(r -> r.tally().orphans()).sum();
        if (orphans > 0) {
            System.out.println("Passed waves without their plan in the log: " + orphans);
        }
        if (parameters.equals(GunParameters.DEFAULT)) {
            System.out.println(differing == 0 && orphans == 0
                    ? "Parity: every replayed decision equals the logged one."
                    : "PARITY FAILED: " + differing + " replayed decisions differ from the log, " + orphans
                            + " passed waves without their plan (a recording that lost a turn's debug properties).");
            System.exit(differing == 0 && orphans == 0 ? 0 : 1);
        }
        System.exit(0);
    }

    private static void print(GunParameters parameters, List<Row> rows, int battles, double loadSeconds,
                              double replaySeconds) {
        System.out.println("Parameters: " + parameters);
        System.out.printf(Locale.ROOT, "%d battles, read in %.1f s, replayed in %.2f s%n%n", battles, loadSeconds,
                replaySeconds);
        System.out.printf(Locale.ROOT, "%-34s %7s %8s %8s %9s %9s %9s %9s %15s %8s %8s%n", "opponent", "battles",
                "shots", "gf tries", "gf hit %", "gf real %", "fired %", "harness %", "bullets/harness", "shielded",
                "differ");
        for (Row r : rows) {
            GunReplay.Tally t = r.tally();
            System.out.printf(Locale.ROOT, "%-34s %7d %8d %8d %9.2f %9.2f %9.2f %9.2f %15s %8d %8d%n", r.opponent(),
                    r.battles(), t.shots(), t.guessFactorTries(), t.guessFactorRate(), t.realGuessFactorRate(),
                    t.firedRate(), r.harnessRate(), t.firedTries() + "/" + r.harnessFired(), t.shielded(),
                    t.differing());
        }
        System.out.printf(Locale.ROOT, "%-34s %7s %8s %8s %9.2f %9.2f %9.2f %9.2f%n", "mean over opponents", "", "",
                "", mean(rows, r -> r.tally().guessFactorRate()), mean(rows, r -> r.tally().realGuessFactorRate()),
                mean(rows, r -> r.tally().firedRate()), mean(rows, Row::harnessRate));
    }

    private static double mean(List<Row> rows, ToDoubleFunction<Row> value) {
        return rows.stream().mapToDouble(value).filter(v -> !Double.isNaN(v)).average().orElse(Double.NaN);
    }

    /** Per opponent, the tally of its battles. */
    static Map<String, GunReplay.Tally> evaluate(List<GunBattle> battles, GunParameters parameters) {
        List<GunReplay.Tally> tallies = battles.parallelStream().map(b -> GunReplay.replay(b, parameters)).toList();
        Map<String, GunReplay.Tally> byOpponent = new TreeMap<>();
        for (int i = 0; i < battles.size(); i++) {
            byOpponent.merge(battles.get(i).opponent(), tallies.get(i), GunReplay.Tally::plus);
        }
        return byOpponent;
    }

    /** The mean over opponents of the fired hit rate: what the search maximises. */
    static double objective(List<GunBattle> battles, GunParameters parameters) {
        return evaluate(battles, parameters).values().stream().mapToDouble(GunReplay.Tally::firedRate)
                .filter(v -> !Double.isNaN(v)).average().orElse(Double.NaN);
    }

    /**
     * Tunes on half of the opponents and reports both halves. Left out first: opponents that shoot down more than
     * {@code maxShielded} of our bullets (they aim at our aim, so the bench cannot judge a new one against them) and
     * opponents we fire no bullet at. The rest is split per stratum in a shuffled order ({@code splitSeed}), every
     * other one into the training half; both battles of an opponent stay in its half.
     */
    private static void tuneAndReport(List<GunBattle> battles, GunParameters start, Map<String, String> strata,
                                      int passes, double maxShielded, long splitSeed) {
        Map<String, GunReplay.Tally> first = evaluate(battles, start);
        Set<String> leftOut = new HashSet<>();
        for (Map.Entry<String, GunReplay.Tally> e : first.entrySet()) {
            GunReplay.Tally t = e.getValue();
            double shielded = (double) t.shielded() / Math.max(1, t.shielded() + t.firedTries());
            if (shielded > maxShielded) {
                System.out.printf(Locale.ROOT, "left out, %.0f %% of our bullets shot down: %s%n", 100 * shielded,
                        e.getKey());
                leftOut.add(e.getKey());
            } else if (t.firedTries() == 0) {
                System.out.println("left out, no bullet fired: " + e.getKey());
                leftOut.add(e.getKey());
            }
        }
        Map<String, List<String>> byStratum = new TreeMap<>();
        for (String opponent : first.keySet()) {
            if (!leftOut.contains(opponent)) {
                byStratum.computeIfAbsent(strata.getOrDefault(opponent, ""), k -> new ArrayList<>()).add(opponent);
            }
        }
        Random random = new Random(splitSeed);
        Set<String> trainOpponents = new HashSet<>();
        for (List<String> names : byStratum.values()) {
            Collections.shuffle(names, random);
            for (int i = 0; i < names.size(); i += 2) {
                trainOpponents.add(names.get(i));
            }
        }
        List<GunBattle> train = battles.stream().filter(b -> trainOpponents.contains(b.opponent())).toList();
        List<GunBattle> test = battles.stream()
                .filter(b -> !leftOut.contains(b.opponent()) && !trainOpponents.contains(b.opponent())).toList();
        System.out.printf(Locale.ROOT, "Tuning on %d opponents (%d battles), testing on %d (%d battles)%n",
                trainOpponents.size(), train.size(), first.size() - leftOut.size() - trainOpponents.size(),
                test.size());
        System.out.println("training half: " + String.join("; ", new TreeSet<>(trainOpponents)));
        if (train.isEmpty()) {
            throw new IllegalArgumentException("nothing to tune on: no opponent with a bullet fired in the training "
                    + "half");
        }

        long t0 = System.nanoTime();
        GunParameters tuned = tune(train, start, passes);
        System.out.printf(Locale.ROOT, "search took %.0f s%n%n", (System.nanoTime() - t0) / 1e9);
        System.out.println("Start: " + start);
        System.out.println("Tuned: " + tuned);
        compare("train", train, start, tuned, strata);
        compare("test ", test, start, tuned, strata);
        double[] w = tuned.weights();
        System.out.println();
        System.out.println("-Pweights=" + Arrays.stream(w).mapToObj(GunBench::fmt).collect(Collectors.joining(","))
                + " -PneighbourScale=" + fmt(tuned.neighbourScale()) + " -PmaxNeighbours=" + tuned.maxNeighbours()
                + " -PvirtualWeight=" + fmt(tuned.virtualWeight()) + " -PminTries=" + fmt(tuned.minTries()));
    }

    /**
     * The mean fired hit rate per stratum with both parameter sets, and the mean of the per-opponent differences
     * with its 95 % interval over opponents.
     */
    private static void compare(String label, List<GunBattle> battles, GunParameters a, GunParameters b,
                                Map<String, String> strata) {
        Map<String, GunReplay.Tally> before = evaluate(battles, a);
        Map<String, GunReplay.Tally> after = evaluate(battles, b);
        StringBuilder line = new StringBuilder(label + ":");
        for (Map.Entry<String, List<String>> g : groups(before, strata).entrySet()) {
            double[] d = g.getValue().stream()
                    .mapToDouble(o -> after.get(o).firedRate() - before.get(o).firedRate())
                    .filter(v -> !Double.isNaN(v)).toArray();
            double mean = Arrays.stream(d).average().orElse(Double.NaN);
            double sd = Math.sqrt(Arrays.stream(d).map(v -> (v - mean) * (v - mean)).sum() / Math.max(1, d.length - 1));
            long better = Arrays.stream(d).filter(v -> v > 0).count();
            long worse = Arrays.stream(d).filter(v -> v < 0).count();
            line.append(String.format(Locale.ROOT, "  %s (%d) %.2f -> %.2f, %+.2f ± %.2f, %d up %d down",
                    g.getKey(), d.length, meanFired(g.getValue(), before), meanFired(g.getValue(), after), mean,
                    1.96 * sd / Math.sqrt(Math.max(1, d.length)), better, worse));
        }
        System.out.println(line);
    }

    /** All opponents, then those of each stratum in the selection's order. */
    private static Map<String, List<String>> groups(Map<String, GunReplay.Tally> byOpponent,
                                                    Map<String, String> strata) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("all", new ArrayList<>(byOpponent.keySet()));
        for (String stratum : strata.values().stream().distinct().toList()) {
            List<String> members = byOpponent.keySet().stream().filter(o -> stratum.equals(strata.get(o))).toList();
            if (!members.isEmpty()) {
                groups.put(stratum, members);
            }
        }
        return groups;
    }

    private static double meanFired(List<String> opponents, Map<String, GunReplay.Tally> byOpponent) {
        return opponents.stream().mapToDouble(o -> byOpponent.get(o).firedRate()).filter(v -> !Double.isNaN(v))
                .average().orElse(Double.NaN);
    }

    /**
     * Coordinate search: each parameter in turn tries a few multiples of its value (the weights, the virtual weight
     * and the tries also 0) and keeps the best if it raises the objective by more than 0.01 points. After a pass
     * without a gain the steps get finer; after a fine pass without a gain, or {@code passes} passes, it stops.
     */
    static GunParameters tune(List<GunBattle> train, GunParameters start, int passes) {
        double[] x = vector(start);
        double best = objective(train, start);
        System.out.printf(Locale.ROOT, "start: %.3f%n", best);
        boolean fine = false;
        for (int pass = 1; pass <= passes; pass++) {
            boolean improved = false;
            for (int i = 0; i < x.length; i++) {
                double current = x[i];
                // The best of all candidates, taken if it beats the current value by more than 0.01 points.
                double[] bestY = null;
                double bestV = best + 0.01;
                for (double candidate : candidates(i, current, fine)) {
                    double[] y = x.clone();
                    y[i] = candidate;
                    GunParameters p;
                    try {
                        p = parameters(y);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    double v = objective(train, p);
                    if (v > bestV) {
                        bestV = v;
                        bestY = y;
                    }
                }
                if (bestY != null) {
                    best = bestV;
                    x = bestY;
                    improved = true;
                }
                if (x[i] != current) {
                    System.out.printf(Locale.ROOT, "pass %d%s: %s %s -> %s, %.3f%n", pass, fine ? " (fine)" : "",
                            NAMES.get(i), fmt(current), fmt(x[i]), best);
                }
            }
            if (!improved) {
                if (fine) {
                    break;
                }
                fine = true;
                System.out.printf(Locale.ROOT, "pass %d: no gain, finer steps%n", pass);
            }
        }
        return parameters(x);
    }

    private static double[] candidates(int i, double v, boolean fine) {
        String name = NAMES.get(i);
        double[] factors = fine ? new double[] {0.85, 1.18} : new double[] {0.5, 0.7, 1.4, 2};
        if (name.equals("maxNeighbours")) {
            return Arrays.stream(factors).map(f -> Math.max(1, Math.round(v * f))).filter(c -> c != v).distinct()
                    .toArray();
        }
        if (v == 0) {
            return fine ? new double[] {0.1, 0.3} : new double[] {0.05, 0.2, 0.5, 1, 2};
        }
        List<Double> c = new ArrayList<>();
        for (double f : factors) {
            c.add(v * f);
        }
        if (!fine && (name.startsWith("w") || name.equals("virtualWeight") || name.equals("minTries"))) {
            // A weight may also switch its feature off; virtual waves and the tries before switching may count nothing.
            c.add(0.0);
        }
        if (!fine && name.equals("virtualWeight") && v != 1) {
            c.add(1.0);
        }
        return c.stream().mapToDouble(Double::doubleValue).distinct().toArray();
    }

    /** Every digit a double needs: the printed options must reproduce the tuned parameters exactly. */
    private static String fmt(double v) {
        return Double.toString(v);
    }

    private static double[] vector(GunParameters p) {
        double[] x = Arrays.copyOf(p.weights(), NAMES.size());
        x[10] = p.neighbourScale();
        x[11] = p.maxNeighbours();
        x[12] = p.virtualWeight();
        x[13] = p.minTries();
        return x;
    }

    private static GunParameters parameters(double[] x) {
        return new GunParameters(Arrays.copyOf(x, 10), x[10], (int) Math.round(x[11]), x[12], x[13]);
    }

    /** The index's last line per recording: bullets fired and hits as the harness counted them. */
    private static Map<String, long[]> readIndex(Path logs) throws IOException {
        Path index = logs.resolve("battles.tsv");
        Map<String, long[]> shots = new HashMap<>();
        if (!Files.exists(index)) {
            return shots;
        }
        List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\t");
            shots.put(key(logs.resolve(f[0])), new long[] {Long.parseLong(f[4]), Long.parseLong(f[5])});
        }
        return shots;
    }

    private static String key(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    private static Map<String, String> readSelection(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> header = Arrays.asList(lines.get(0).split("\t"));
        int stratum = header.indexOf("stratum");
        int name = header.indexOf("name");
        if (stratum < 0 || name < 0) {
            throw new IOException(file + ": needs the columns stratum and name");
        }
        Map<String, String> strata = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\t");
            strata.put(f[name], f[stratum]);
        }
        return strata;
    }

    private static GunParameters parameters(Map<String, String> options) {
        GunParameters d = GunParameters.DEFAULT;
        double[] weights = options.containsKey("weights")
                ? Arrays.stream(options.get("weights").split(",")).mapToDouble(s -> Double.parseDouble(s.trim()))
                .toArray()
                : d.weights();
        return new GunParameters(weights,
                Double.parseDouble(options.getOrDefault("neighbourScale", String.valueOf(d.neighbourScale()))),
                Integer.parseInt(options.getOrDefault("maxNeighbours", String.valueOf(d.maxNeighbours()))),
                Double.parseDouble(options.getOrDefault("virtualWeight", String.valueOf(d.virtualWeight()))),
                Double.parseDouble(options.getOrDefault("minTries", String.valueOf(d.minTries()))));
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("Usage: GunBench logs=<dir>,... [robot=<name prefix>] [selection=<tsv>] [weights=w,...] "
                + "[neighbourScale=x] [maxNeighbours=n] [virtualWeight=x] [minTries=x] [tune=<passes>] "
                + "[maxShielded=x] [splitSeed=n]");
        System.exit(2);
    }
}
