package cz.libors.harness.meleebench;

import cz.libors.ogi.gun.MeleeGun;
import cz.libors.ogi.gun.MeleeGunParameters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The offline melee bench: replays the melee gun's logs of recorded melee battles ({@code submitGunLogs} against a
 * melee set) with other melee gun parameters and scores the bullets the bot fired on the melee gun's aim along the
 * replayed aims, against every robot's recorded positions. With the parameters the logging bot played with, every
 * replayed aim must equal the logged one.
 *
 * <p>Usage: {@code MeleeBench logs=<dir>,... [robot=<name prefix>] [base=<parameters>] [otherLogs=<dir>,...]
 * [<parameter>=<value>]... [powers=p,...] [tune=<passes>] [splitSeed=n] [fixed=<parameter>,...]}
 *
 * <ul>
 *   <li>{@code base}: the parameters the logging bot played with, as {@code key=value} pairs separated by
 *       {@code ;} over {@link MeleeGunParameters#DEFAULT}, or over {@link MeleeGunParameters#TEAM} for logs of team
 *       battles, as the bot plays them; the parity check holds them to the logs.</li>
 *   <li>The parameter options ({@link #NAMES}; {@code weights} takes up to {@link MeleeGun#FEATURES} values
 *       separated by commas, those not given weigh 0; the switches take true or false) give the variant, over the
 *       base. Without any, the variant is the base.</li>
 *   <li>{@code otherLogs}: logs of a bot that played with the variant, on the same draws. The bench then measures
 *       both ways: the variant's gain on the base's logs is effect + bias, the base's gain on the variant's logs is
 *       −effect + bias.</li>
 *   <li>{@code tune}: a coordinate search from the variant on half of the battles, reported on both halves;
 *       {@code fixed} names parameters it leaves as they are (the option names, {@code weights} for all
 *       {@link MeleeGun#FEATURES}).</li>
 *   <li>{@code powers}: instead, the variant's shots at these powers, per power and distance band.</li>
 * </ul>
 * Exits with 1 if more than one in 1,000 replayed aims of the base parameters differ from the logs.
 */
public final class MeleeBench {

    private static final List<String> FEATURE_NAMES = List.of("w0 speed", "w1 acceleration", "w2 turn rate",
            "w3 wall ahead", "w4 closest distance", "w5 closest cos", "w6 closest sin", "w7 enemies",
            "w8 since reversal", "w9 since deceleration", "w10 travel", "w11 centre distance");
    /** The option of each parameter after the weights, in {@link #NAMES}' order. */
    private static final List<String> OPTIONS = List.of("neighbours", "wallRange", "neighbourRange",
            "distanceSoftening", "enemyDistancePower", "kernelWidth", "maxAge", "horizon", "maxRecords", "everyRound",
            "reachingOnly", "singleEnemy");
    /** The weights by feature, then the other parameters as {@link #OPTIONS} names them. */
    static final List<String> NAMES = java.util.stream.Stream.concat(FEATURE_NAMES.stream(), OPTIONS.stream())
            .toList();
    private static final int F = MeleeGun.FEATURES;
    /** Integer parameters, by index in {@link #NAMES}. */
    private static final List<Integer> INTEGERS = List.of(F, F + 6, F + 7, F + 8);
    /** Switches, by index in {@link #NAMES}: 1 for true. */
    private static final List<Integer> SWITCHES = List.of(F + 9, F + 10, F + 11);

    /**
     * Aims that may differ from the log beyond the tolerance, per aim. On logs from another machine a last-bit
     * difference can tip a nearly tied peak of the density (3 of 157,114 aims on the Windows worker's logs replayed on
     * the Mac, 2026-10-15). Logs recorded without debug mode lose a turn's log now and then even where no skipped turn
     * was reported, and the replay goes on without that scan: 58 of 127,929 aims (2026-10-16).
     */
    private static final double MAX_DIFFERING = 1e-3;

    private MeleeBench() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new LinkedHashMap<>(Map.of("robot", "cz.libors.Ogi"));
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
        long start = System.nanoTime();
        Logs logs = Logs.read(options.get("logs"), options.get("robot"));
        Logs other = options.containsKey("otherLogs") ? Logs.read(options.get("otherLogs"), options.get("robot"))
                : null;
        System.out.printf(Locale.ROOT, "%d battles read in %.1f s%s%n", logs.battles.size(),
                (System.nanoTime() - start) / 1e9, other == null ? "" : ", and " + other.battles.size() + " other");
        boolean team = team(other == null ? logs.battles
                : Stream.concat(logs.battles.stream(), other.battles.stream()).toList());
        MeleeGunParameters base = parameters(team ? MeleeGunParameters.TEAM : MeleeGunParameters.DEFAULT,
                overrides(options.getOrDefault("base", "")));
        MeleeGunParameters variant = parameters(base, options);
        System.out.println("Base:    " + base + (team ? " (team battles: over TEAM)" : ""));
        System.out.println("Variant: " + variant);

        if (options.containsKey("powers")) {
            powers(logs.battles, variant, Arrays.stream(options.get("powers").split(","))
                    .mapToDouble(v -> Double.parseDouble(v.trim())).toArray());
            System.exit(0);
        }
        if (options.containsKey("tune")) {
            java.util.Set<Integer> fixed = new java.util.HashSet<>();
            for (String name : options.getOrDefault("fixed", "").split(",")) {
                if (name.isBlank()) {
                    continue;
                }
                int i = name.trim().equals("weights") ? -1 : OPTIONS.indexOf(name.trim());
                if (i < 0 && !name.trim().equals("weights")) {
                    usage("fixed takes parameter names (" + String.join(", ", OPTIONS) + ", weights): " + name);
                }
                if (i < 0) {
                    for (int k = 0; k < MeleeGun.FEATURES; k++) {
                        fixed.add(k);
                    }
                } else {
                    fixed.add(MeleeGun.FEATURES + i);
                }
            }
            tune(logs.battles, base, variant, Integer.parseInt(options.get("tune")),
                    Long.parseLong(options.getOrDefault("splitSeed", "1")), fixed);
            System.exit(0);
        }

        boolean ok = report("logs", logs, base, variant);
        if (other != null) {
            ok &= report("other logs (the variant's)", other, variant, base);
            Paired a = paired(logs.battles, base, variant);
            Paired b = paired(other.battles, variant, base);
            double effect = (a.difference - b.difference) / 2;
            double bias = (a.difference + b.difference) / 2;
            double se = Math.sqrt(a.se * a.se + b.se * b.se) / 2;
            System.out.printf(Locale.ROOT, "%nBoth ways: the variant's gain on the base's logs %+.2f ± %.2f, the base's "
                            + "gain on the variant's logs %+.2f ± %.2f%n  effect %+.2f ± %.2f points of fired %%, "
                            + "bias %+.2f ± %.2f (95 %%)%n", a.difference, 1.96 * a.se, b.difference, 1.96 * b.se,
                    effect, 1.96 * se, bias, 1.96 * se);
        }
        System.exit(ok ? 0 : 1);
    }

    /**
     * Whether the logs are of team battles, in which the logging robot has teammates and the bot aims with
     * {@link MeleeGunParameters#TEAM} instead of {@link MeleeGunParameters#DEFAULT}; the base starts from those.
     * Logs of both kinds at once are refused: no one base holds for both.
     */
    private static boolean team(List<MeleeBattle> battles) {
        long team = battles.stream().filter(b -> !b.teammates().isEmpty()).count();
        if (team > 0 && team < battles.size()) {
            usage(team + " of " + battles.size() + " logs are of team battles, the rest of melee battles: the bot "
                    + "plays them with other parameters, replay them apart");
        }
        return team > 0;
    }

    /**
     * The battles of some logs directories, the harness's shot counts from their indexes, and the battles in which
     * the logging robot skipped turns (their log can lack a turn).
     */
    private record Logs(List<MeleeBattle> battles, long harnessFired, long harnessHits, java.util.Set<Path> skipped) {

        static Logs read(String dirs, String robot) throws IOException {
            List<Path> files = new ArrayList<>();
            long fired = 0;
            long hits = 0;
            java.util.Set<Path> skipped = new java.util.HashSet<>();
            for (String dir : dirs.split(",")) {
                Path logs = Path.of(dir.trim());
                try (Stream<Path> list = Files.list(logs)) {
                    list.filter(p -> p.getFileName().toString().endsWith(".rec.gz")).sorted().forEach(files::add);
                }
                Path index = logs.resolve("battles.tsv");
                if (Files.exists(index)) {
                    List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
                    Map<String, String[]> last = new HashMap<>();
                    for (String line : lines.subList(1, lines.size())) {
                        String[] f = line.split("\t");
                        last.put(f[0], f);
                    }
                    for (String[] f : last.values()) {
                        fired += Long.parseLong(f[4]);
                        hits += Long.parseLong(f[5]);
                        if (Long.parseLong(f[6]) > 0) {
                            skipped.add(logs.resolve(f[0]).toAbsolutePath().normalize());
                        }
                    }
                }
            }
            if (files.isEmpty()) {
                usage("no *.rec.gz in " + dirs);
            }
            // A team battle gives one log per robot of ours, each replayed with a memory of its own, as it played.
            List<MeleeBattle> battles = files.parallelStream().flatMap(f -> {
                try {
                    return MeleeBattle.readAll(f, robot).stream();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).toList();
            return new Logs(battles, fired, hits, skipped);
        }
    }

    /** Prints the base's and the variant's tallies on some logs; false if the base's replay differs from them. */
    private static boolean report(String label, Logs logs, MeleeGunParameters base, MeleeGunParameters variant) {
        long t0 = System.nanoTime();
        List<MeleeReplay.Tally> b = logs.battles.parallelStream().map(x -> MeleeReplay.replay(x, base, true, true))
                .toList();
        long t1 = System.nanoTime();
        MeleeReplay.Tally baseTally = b.stream().reduce(MeleeReplay.Tally.ZERO, MeleeReplay.Tally::plus);
        MeleeReplay.Tally checked = MeleeReplay.Tally.ZERO;
        for (int i = 0; i < b.size(); i++) {
            if (!logs.skipped.contains(logs.battles.get(i).file().toAbsolutePath().normalize())) {
                checked = checked.plus(b.get(i));
            }
        }
        System.out.printf(Locale.ROOT, "%n%s: %d battles, replayed in %.1f s%n", label, logs.battles.size(),
                (t1 - t0) / 1e9);
        System.out.printf(Locale.ROOT, "%-8s %8s %9s %9s %7s %9s %7s %8s%n", "", "shots", "fired %", "dmg/shot",
                "no aim", "shot down", "open", "differ");
        print("base", baseTally);
        if (!variant.equals(base)) {
            long t2 = System.nanoTime();
            MeleeReplay.Tally v = logs.battles.parallelStream().map(x -> MeleeReplay.replay(x, variant, false, false))
                    .reduce(MeleeReplay.Tally.ZERO, MeleeReplay.Tally::plus);
            long t3 = System.nanoTime();
            print("variant", v);
            System.out.printf(Locale.ROOT, "replay time variant / base: %.2f (the gun's work, roughly)%n",
                    (double) (t3 - t2) / Math.max(1, t1 - t0));
            Paired p = paired(logs.battles, base, variant);
            System.out.printf(Locale.ROOT, "variant - base: %+.2f ± %.2f points of fired %% (95 %%, battles as "
                    + "clusters)%n", p.difference, 1.96 * p.se);
        }
        System.out.printf(Locale.ROOT, "Hits on the robot aimed at: %.1f %% of the base's hits%n",
                100.0 * baseTally.hitsAimed() / Math.max(1, baseTally.hits()));
        System.out.printf(Locale.ROOT, "Real bullets: %d flown along their real heading, %.2f %% hit in the engine "
                        + "(the harness: %.2f %% of all %d bullets, the duel at the end included); %d end otherwise "
                        + "here than in the engine%n", baseTally.realChecked(), baseTally.realRate(),
                logs.harnessFired == 0 ? Double.NaN : 100.0 * logs.harnessHits / logs.harnessFired,
                logs.harnessFired, baseTally.realDiffering());
        long aims = checked.shots() + checked.shotDown() + checked.open();
        boolean parity = checked.differing() <= aims * MAX_DIFFERING;
        String left = logs.skipped.isEmpty() ? "" : " (" + logs.skipped.size() + " battles with skipped turns of the "
                + "logging robot left out, " + (baseTally.differing() - checked.differing()) + " aims apart there)";
        System.out.println((parity ? "Parity: the replayed aims of the base equal the logged ones within "
                + MeleeReplay.PARITY_TOLERANCE + " rad, " + checked.differing() + " of " + aims + " apart"
                : "PARITY FAILED: " + checked.differing() + " of " + aims + " replayed aims of the base differ from "
                        + "the logs (did the logging bot play with the base parameters?)") + left + ".");
        return parity;
    }

    private static void print(String label, MeleeReplay.Tally t) {
        System.out.printf(Locale.ROOT, "%-8s %8d %9.2f %9.3f %7d %9d %7d %8d%n", label, t.shots(), t.firedRate(),
                t.damagePerShot(), t.noAim(), t.shotDown(), t.open(), t.differing());
    }

    /** The difference of two parameter sets' pooled fired rates on the same battles, and its standard error. */
    record Paired(double base, double variant, double difference, double se) {
    }

    static Paired paired(List<MeleeBattle> battles, MeleeGunParameters base, MeleeGunParameters variant) {
        List<MeleeReplay.Tally[]> t = battles.parallelStream().map(b -> new MeleeReplay.Tally[] {
                MeleeReplay.replay(b, base, false, false), MeleeReplay.replay(b, variant, false, false)}).toList();
        // The robots of one team battle fought the same enemies: one cluster.
        Map<Path, MeleeReplay.Tally[]> byFile = new LinkedHashMap<>();
        for (int i = 0; i < battles.size(); i++) {
            byFile.merge(battles.get(i).file(), t.get(i),
                    (a, b) -> new MeleeReplay.Tally[] {a[0].plus(b[0]), a[1].plus(b[1])});
        }
        return paired(new ArrayList<>(byFile.values()));
    }

    /**
     * Pooled rates R = Σh / Σs per side, their difference and its standard error with the battles as clusters: the
     * linearised residuals (h − R·s) / Σs of both sides, differenced per battle.
     */
    private static Paired paired(List<MeleeReplay.Tally[]> t) {
        double[] s = new double[2];
        double[] h = new double[2];
        for (MeleeReplay.Tally[] pair : t) {
            for (int k = 0; k < 2; k++) {
                s[k] += pair[k].shots();
                h[k] += pair[k].hits();
            }
        }
        double r0 = h[0] / s[0];
        double r1 = h[1] / s[1];
        double sum = 0;
        for (MeleeReplay.Tally[] pair : t) {
            double d = (pair[1].hits() - r1 * pair[1].shots()) / s[1] - (pair[0].hits() - r0 * pair[0].shots()) / s[0];
            sum += d * d;
        }
        int n = t.size();
        double se = n < 2 ? Double.NaN : Math.sqrt(sum * n / (n - 1));
        return new Paired(100 * r0, 100 * r1, 100 * (r1 - r0), 100 * se);
    }

    /**
     * The same shots at other powers: per power and distance band the hit rate, the damage per bullet and per turn of
     * the gun's heat (a bullet heats the gun by 1 + p/5, which cools by 0.1 a turn), and the energy a bullet nets
     * (3p back per hit, p paid). The battles' course stays as recorded: fewer shots at a higher power, the energy and
     * the kills are not replayed.
     */
    private static void powers(List<MeleeBattle> battles, MeleeGunParameters p, double[] powers) {
        long[][][] sum = new long[powers.length][MeleeReplay.DISTANCE_BANDS.length + 1][2];
        for (long[][][] c : battles.parallelStream().map(b -> MeleeReplay.powers(b, p, powers)).toList()) {
            for (int k = 0; k < powers.length; k++) {
                for (int band = 0; band < sum[k].length; band++) {
                    sum[k][band][0] += c[k][band][0];
                    sum[k][band][1] += c[k][band][1];
                }
            }
        }
        List<String> bands = new ArrayList<>();
        double lo = 0;
        for (double hi : MeleeReplay.DISTANCE_BANDS) {
            bands.add((int) lo + "-" + (int) hi);
            lo = hi;
        }
        bands.add(">" + (int) lo);
        bands.add("all");
        System.out.printf(Locale.ROOT, "%n%-6s %-8s %8s %8s %9s %10s %10s%n", "power", "band", "shots", "hit %",
                "dmg/shot", "dmg/turn", "net energy");
        for (int k = 0; k < powers.length; k++) {
            double power = powers[k];
            double damage = cz.libors.ogi.physics.BulletMotion.damage(power);
            long allShots = 0;
            long allHits = 0;
            for (int band = 0; band <= sum[k].length; band++) {
                long shots = band < sum[k].length ? sum[k][band][0] : allShots;
                long hits = band < sum[k].length ? sum[k][band][1] : allHits;
                if (band < sum[k].length) {
                    allShots += shots;
                    allHits += hits;
                }
                double rate = shots == 0 ? Double.NaN : (double) hits / shots;
                System.out.printf(Locale.ROOT, "%-6s %-8s %8d %8.2f %9.3f %10.4f %10.3f%n", fmt(power),
                        bands.get(band), shots, 100 * rate, rate * damage, rate * damage / (10 + 2 * power),
                        rate * 3 * power - power);
            }
        }
    }

    /** The pooled fired rate of {@code p} over {@code battles}: what the search maximises. */
    static double objective(List<MeleeBattle> battles, MeleeGunParameters p) {
        return battles.parallelStream().map(b -> MeleeReplay.replay(b, p, false, false))
                .reduce(MeleeReplay.Tally.ZERO, MeleeReplay.Tally::plus).firedRate();
    }

    /**
     * Tunes on half of the battles and reports both halves against the base. The recorded battles are shuffled
     * ({@code splitSeed}) and every other one goes to the training half, with every log it holds (a team battle's
     * five robots stay together).
     */
    private static void tune(List<MeleeBattle> battles, MeleeGunParameters base, MeleeGunParameters start,
                             int passes, long splitSeed, java.util.Set<Integer> fixed) {
        List<Path> files = new ArrayList<>(battles.stream().map(MeleeBattle::file).distinct().toList());
        files.sort((a, b) -> a.toString().compareTo(b.toString()));
        Collections.shuffle(files, new Random(splitSeed));
        java.util.Set<Path> training = new java.util.HashSet<>();
        for (int i = 0; i < files.size(); i += 2) {
            training.add(files.get(i));
        }
        List<MeleeBattle> train = new ArrayList<>();
        List<MeleeBattle> test = new ArrayList<>();
        for (MeleeBattle b : battles) {
            (training.contains(b.file()) ? train : test).add(b);
        }
        System.out.printf(Locale.ROOT, "Tuning on %d battles, testing on %d%n", train.size(), test.size());
        long t0 = System.nanoTime();
        double[] x = vector(start);
        double best = objective(train, start);
        System.out.printf(Locale.ROOT, "start: %.3f%n", best);
        boolean fine = false;
        for (int pass = 1; pass <= passes; pass++) {
            boolean improved = false;
            for (int i = 0; i < x.length; i++) {
                if (fixed.contains(i)) {
                    continue;
                }
                double current = x[i];
                double[] bestY = null;
                double bestV = best + 0.01;
                for (double candidate : candidates(i, current, fine)) {
                    double[] y = x.clone();
                    y[i] = candidate;
                    MeleeGunParameters p;
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
        MeleeGunParameters tuned = parameters(x);
        System.out.printf(Locale.ROOT, "search took %.0f s%n%n", (System.nanoTime() - t0) / 1e9);
        System.out.println("Tuned: " + tuned);
        Paired onTrain = paired(train, base, tuned);
        Paired onTest = paired(test, base, tuned);
        System.out.printf(Locale.ROOT, "train: base %.2f, tuned %.2f, %+.2f ± %.2f (95 %%)%n", onTrain.base,
                onTrain.variant, onTrain.difference, 1.96 * onTrain.se);
        System.out.printf(Locale.ROOT, "test:  base %.2f, tuned %.2f, %+.2f ± %.2f (95 %%)%n", onTest.base,
                onTest.variant, onTest.difference, 1.96 * onTest.se);
        System.out.println();
        System.out.println(options(tuned));
    }

    private static double[] candidates(int i, double v, boolean fine) {
        double[] factors = fine ? new double[] {0.85, 1.18} : new double[] {0.5, 0.7, 1.4, 2};
        if (SWITCHES.contains(i)) {
            return fine ? new double[0] : new double[] {1 - v};
        }
        if (INTEGERS.contains(i)) {
            int min = i == F + 6 ? 0 : 1;
            return Arrays.stream(factors).map(f -> Math.max(min, Math.round(v * f))).filter(c -> c != v).distinct()
                    .toArray();
        }
        boolean mayBeZero = i < MeleeGun.FEATURES || NAMES.get(i).equals("enemyDistancePower");
        if (v == 0) {
            return fine ? new double[] {0.1, 0.3} : new double[] {0.05, 0.2, 0.5, 1, 2};
        }
        List<Double> c = new ArrayList<>();
        for (double f : factors) {
            c.add(v * f);
        }
        if (!fine && mayBeZero) {
            // A weight may switch its feature off, and the enemy's distance may not count at all.
            c.add(0.0);
        }
        return c.stream().mapToDouble(Double::doubleValue).distinct().toArray();
    }

    /** Every digit a double needs: the printed options must reproduce the parameters exactly. */
    private static String fmt(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e9 ? Long.toString((long) v) : Double.toString(v);
    }

    static double[] vector(MeleeGunParameters p) {
        double[] x = Arrays.copyOf(p.weights(), NAMES.size());
        x[F] = p.neighbours();
        x[F + 1] = p.wallRange();
        x[F + 2] = p.neighbourRange();
        x[F + 3] = p.distanceSoftening();
        x[F + 4] = p.enemyDistancePower();
        x[F + 5] = p.kernelWidth();
        x[F + 6] = p.maxAge();
        x[F + 7] = p.horizon();
        x[F + 8] = p.maxRecords();
        x[F + 9] = p.everyRound() ? 1 : 0;
        x[F + 10] = p.reachingOnly() ? 1 : 0;
        x[F + 11] = p.singleEnemy() ? 1 : 0;
        return x;
    }

    static MeleeGunParameters parameters(double[] x) {
        return new MeleeGunParameters(Arrays.copyOf(x, F), (int) Math.round(x[F]), x[F + 1], x[F + 2], x[F + 3],
                x[F + 4], x[F + 5], (int) Math.round(x[F + 6]), (int) Math.round(x[F + 7]), (int) Math.round(x[F + 8]),
                x[F + 9] != 0, x[F + 10] != 0, x[F + 11] != 0);
    }

    /** The options that give {@code p}, every digit printed. */
    static String options(MeleeGunParameters p) {
        double[] x = vector(p);
        StringBuilder b = new StringBuilder("-Pweights=" + Arrays.stream(p.weights()).mapToObj(MeleeBench::fmt)
                .collect(Collectors.joining(",")));
        for (int i = 0; i < OPTIONS.size(); i++) {
            double v = x[MeleeGun.FEATURES + i];
            b.append(" -P").append(OPTIONS.get(i)).append('=')
                    .append(SWITCHES.contains(MeleeGun.FEATURES + i) ? Boolean.toString(v != 0) : fmt(v));
        }
        return b.toString();
    }

    /** {@code key=value;key=value} as options. */
    private static Map<String, String> overrides(String spec) {
        Map<String, String> m = new HashMap<>();
        for (String part : spec.split(";")) {
            if (part.isBlank()) {
                continue;
            }
            int eq = part.indexOf('=');
            if (eq <= 0) {
                usage("not key=value in base: " + part);
            }
            m.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return m;
    }

    /** {@code from}, with the parameters that {@code options} name. */
    static MeleeGunParameters parameters(MeleeGunParameters from, Map<String, String> options) {
        double[] x = vector(from);
        if (options.containsKey("weights")) {
            double[] w = Arrays.stream(options.get("weights").split(",")).mapToDouble(s -> Double.parseDouble(s.trim()))
                    .toArray();
            if (w.length > MeleeGun.FEATURES) {
                usage("weights takes up to " + MeleeGun.FEATURES + " values: " + options.get("weights"));
            }
            // Features added later weigh 0 unless given, so older command lines keep their meaning.
            Arrays.fill(x, 0, MeleeGun.FEATURES, 0);
            System.arraycopy(w, 0, x, 0, w.length);
        }
        for (int i = 0; i < OPTIONS.size(); i++) {
            String value = options.get(OPTIONS.get(i));
            if (value != null) {
                x[MeleeGun.FEATURES + i] = SWITCHES.contains(MeleeGun.FEATURES + i) ? switchValue(value)
                        : Double.parseDouble(value);
            }
        }
        return parameters(x);
    }

    private static double switchValue(String value) {
        return switch (value) {
            case "true", "1" -> 1;
            case "false", "0" -> 0;
            default -> {
                usage("a switch is true or false: " + value);
                yield 0;
            }
        };
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("Usage: MeleeBench logs=<dir>,... [robot=<name prefix>] [base=<key=value;...>] "
                + "[otherLogs=<dir>,...] [weights=w,...] [" + String.join("=x] [", OPTIONS) + "=x] [powers=p,...] "
                + "[tune=<passes>] [splitSeed=n] [fixed=<parameter>,...]");
        System.exit(2);
    }
}
