package cz.libors.harness.gunbench;

import cz.libors.ogi.gun.GunParameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * How the gun's hit rate changes during a battle, per opponent: the fired hit rate (the chosen gun, waves with a
 * bullet) in the first rounds against the last rounds, replayed with the bot's parameters. Our gun learns, so against
 * a mover that does not react to it the rate rises or stays; a mover that learns to dodge it pulls the rate down.
 * D-022 splits the opponents by this measure.
 *
 * <p>Usage: {@code GunTrend logs=<dir>,... [robot=<name prefix>] [rounds=10]}: compares the first and the last
 * {@code rounds} rounds of each battle and prints the opponents sorted by the change, with its standard error.
 */
public final class GunTrend {

    private GunTrend() {
    }

    private record Row(String opponent, long earlyTries, long earlyHits, long lateTries, long lateHits) {

        double early() {
            return 100.0 * earlyHits / Math.max(1, earlyTries);
        }

        double late() {
            return 100.0 * lateHits / Math.max(1, lateTries);
        }

        double change() {
            return late() - early();
        }

        /** Standard error of the change, from two independent binomial proportions, in points. */
        double error() {
            double p1 = early() / 100;
            double p2 = late() / 100;
            return 100 * Math.sqrt(p1 * (1 - p1) / Math.max(1, earlyTries) + p2 * (1 - p2) / Math.max(1, lateTries));
        }
    }

    public static void main(String[] args) throws IOException {
        String logs = null;
        String robot = "cz.libors.Ogi";
        int rounds = 10;
        for (String arg : args) {
            int eq = arg.indexOf('=');
            switch (arg.substring(0, Math.max(eq, 0))) {
                case "logs" -> logs = arg.substring(eq + 1);
                case "robot" -> robot = arg.substring(eq + 1);
                case "rounds" -> rounds = Integer.parseInt(arg.substring(eq + 1));
                default -> throw new IllegalArgumentException("Usage: GunTrend logs=<dir>,... [robot=..] [rounds=n]");
            }
        }
        if (logs == null) {
            throw new IllegalArgumentException("Usage: GunTrend logs=<dir>,... [robot=..] [rounds=n]");
        }
        List<Path> files = new ArrayList<>();
        for (String dir : logs.split(",")) {
            try (Stream<Path> list = Files.list(Path.of(dir.trim()))) {
                list.filter(p -> p.getFileName().toString().endsWith(".rec.gz")).sorted().forEach(files::add);
            }
        }
        String prefix = robot;
        int n = rounds;
        Map<String, long[]> byOpponent = new TreeMap<>();
        files.parallelStream().map(f -> {
            try {
                GunBattle battle = GunBattle.read(f, prefix);
                List<GunReplay.Tally> perRound = GunReplay.replayRounds(battle, GunParameters.DEFAULT);
                long[] t = new long[4];
                int count = perRound.size();
                for (int r = 0; r < count; r++) {
                    GunReplay.Tally tally = perRound.get(r);
                    if (r < n) {
                        t[0] += tally.firedTries();
                        t[1] += tally.firedHits();
                    }
                    if (r >= count - n) {
                        t[2] += tally.firedTries();
                        t[3] += tally.firedHits();
                    }
                }
                return Map.entry(battle.opponent(), t);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).toList().forEach(e -> {
            long[] sum = byOpponent.computeIfAbsent(e.getKey(), k -> new long[4]);
            for (int i = 0; i < 4; i++) {
                sum[i] += e.getValue()[i];
            }
        });
        List<Row> rows = new ArrayList<>();
        byOpponent.forEach((o, t) -> rows.add(new Row(o, t[0], t[1], t[2], t[3])));
        rows.sort(Comparator.comparingDouble(r -> r.change() / Math.max(r.error(), 1e-9)));
        System.out.printf(Locale.ROOT, "Fired hit rate in the first %d rounds against the last %d, %d battles%n%n", n,
                n, files.size());
        System.out.printf(Locale.ROOT, "%-40s %10s %10s %8s %6s %6s%n", "opponent", "early %", "late %", "change",
                "± se", "z");
        for (Row r : rows) {
            System.out.printf(Locale.ROOT, "%-40s %5.1f %4d %5.1f %4d %+8.1f %6.1f %+6.1f%n", r.opponent(), r.early(),
                    r.earlyTries(), r.late(), r.lateTries(), r.change(), r.error(), r.change() / r.error());
        }
        double median = rows.stream().mapToDouble(Row::change).sorted().skip(rows.size() / 2).findFirst()
                .orElse(Double.NaN);
        System.out.printf(Locale.ROOT, "%nmedian change %+.1f points over %d opponents%n", median, rows.size());
    }
}
