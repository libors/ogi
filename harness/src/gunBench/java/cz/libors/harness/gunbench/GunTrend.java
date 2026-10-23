package cz.libors.harness.gunbench;

import cz.libors.ogi.gun.GunLog;
import cz.libors.ogi.gun.GunParameters;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 *
 * <p>Usage: {@code GunTrend logs=<dir>,... [robot=<name prefix>] [rounds=10]}: compares the first and the last
 * {@code rounds} rounds of each battle and prints the opponents sorted by the change, with its standard error. In a
 * team battle every robot of ours that logged counts ({@link GunBattle#readAll}).
 *
 * <ul>
 *   <li>Only the plans made while one enemy lived count ({@link GunReplay.Scope#ONE_ENEMY}): in a melee or team
 *       battle the melee gun aims the bullets before, and this gun's waves at the closest enemy only teach it. In a
 *       1v1 battle that is every plan.</li>
 *   <li>A battle with fewer than twice {@code rounds} rounds is refused: its first and last rounds would overlap,
 *       and every change would read 0.</li>
 *   <li>The replay must decide as the bot did (the same parity as the gun bench's); logs of another gun are
 *       refused, since their trend would be the replayed gun's, not the logged one's.</li>
 * </ul>
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
        // A team battle holds a log of each of our robots, each with a gun of its own: their tries add up.
        List<GunBattle> battles = files.parallelStream().flatMap(f -> {
            try {
                return GunBattle.readAll(f, prefix).stream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).toList();
        if (battles.stream().noneMatch(b -> b.events().stream().anyMatch(GunLog.LoggedShot.class::isInstance))) {
            throw new IllegalArgumentException("no plans (" + GunLog.SHOT + ") in the logs of " + logs
                    + ": logs of a melee or team set hold the melee gun's keys, for the melee bench");
        }
        long[] parity = new long[2];
        int[] shortest = {Integer.MAX_VALUE};
        battles.parallelStream().map(battle -> {
            List<GunReplay.Tally> perRound = GunReplay.replayRounds(battle, GunParameters.DEFAULT,
                    GunReplay.Scope.ONE_ENEMY);
            long[] t = new long[7];
            int count = perRound.size();
            t[4] = count;
            for (GunReplay.Tally tally : perRound) {
                t[5] += tally.differing();
                t[6] += tally.orphans();
            }
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
        }).toList().forEach(e -> {
            long[] sum = byOpponent.computeIfAbsent(e.getKey(), k -> new long[4]);
            for (int i = 0; i < 4; i++) {
                sum[i] += e.getValue()[i];
            }
            shortest[0] = Math.min(shortest[0], (int) e.getValue()[4]);
            parity[0] += e.getValue()[5];
            parity[1] += e.getValue()[6];
        });
        if (shortest[0] < 2 * n) {
            throw new IllegalArgumentException("a battle has only " + shortest[0] + " rounds, so its first and last "
                    + n + " overlap; pass rounds=" + Math.max(1, shortest[0] / 2) + " or less");
        }
        if (parity[0] > 0) {
            System.out.println("PARITY FAILED: " + parity[0] + " replayed decisions differ from the log; these are "
                    + "logs of another gun, whose trend the replay cannot show.");
            System.exit(1);
        }
        if (parity[1] > 0) {
            System.out.println("Passed waves without their plan in the log: " + parity[1]
                    + " (a recording that lost a turn's debug properties).");
        }
        List<Row> rows = new ArrayList<>();
        byOpponent.forEach((o, t) -> rows.add(new Row(o, t[0], t[1], t[2], t[3])));
        rows.sort(Comparator.comparingDouble(r -> r.change() / Math.max(r.error(), 1e-9)));
        System.out.printf(Locale.ROOT, "Fired hit rate in the first %d rounds against the last %d, %d battles, %d logs "
                + "of our robots%n%n", n, n, files.size(), battles.size());
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
