package cz.libors.harness.gunbench;

import cz.libors.ogi.gun.GunParameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

/**
 * The offline gun bench: replays the gun logs of recorded battles ({@code recordGunLogs}) with some gun parameters
 * and reports the hit rates per opponent. With the bot's parameters every replayed decision must equal the logged
 * one, and the fired hit rate should match the harness's count of the same battles.
 *
 * <p>Usage: {@code GunBench logs=<dir>,... [robot=<name prefix>] [weights=w,...] [neighbourScale=x]
 * [maxNeighbours=n] [virtualWeight=x] [minTries=x]}
 *
 * <p>A logs directory holds {@code *.rec.gz} gun logs and the {@code battles.tsv} index that {@code RecordBattles}
 * writes. Exits with 1 if a replay with the bot's parameters differs from the log.
 */
public final class GunBench {

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
        long start = System.nanoTime();
        List<GunBattle> battles = new ArrayList<>();
        for (Path file : files) {
            battles.add(GunBattle.read(file, options.get("robot")));
        }
        long loaded = System.nanoTime();
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

        long differing = rows.stream().mapToLong(r -> r.tally().differing()).sum();
        long orphans = rows.stream().mapToLong(r -> r.tally().orphans()).sum();
        if (orphans > 0) {
            System.out.println("Passed waves without their plan in the log: " + orphans);
        }
        if (parameters.equals(GunParameters.DEFAULT)) {
            System.out.println(differing == 0 && orphans == 0
                    ? "Parity: every replayed decision equals the logged one."
                    : "PARITY FAILED: " + differing + " replayed decisions differ from the log.");
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
        System.err.println("Usage: GunBench logs=<dir>,... [robot=<name prefix>] [weights=w,...] "
                + "[neighbourScale=x] [maxNeighbours=n] [virtualWeight=x] [minTries=x]");
        System.exit(2);
    }
}
