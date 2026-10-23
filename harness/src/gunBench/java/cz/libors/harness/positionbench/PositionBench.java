package cz.libors.harness.positionbench;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.move.MeleeMovement;
import cz.libors.ogi.move.MeleeMovementParameters;
import cz.libors.ogi.move.Move;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.RobotBox;
import cz.libors.ogi.physics.RobotMotion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * The offline position bench: replays the melee movement's logged decisions of recorded team battles with
 * other {@link MeleeMovementParameters}, and scores each destination chosen by what driving there would have met in
 * the next turns of the recorded battle.
 *
 * <p>A decision is replayed exactly: the logged candidates, their waves' damage and the robots as the bot saw them go
 * through the bot's own {@link MeleeMovement#choose}; with the parameters the bot played with, every choice must be
 * the logged one. Where a variant chooses another destination, both are scored over a horizon of turns, driving
 * straight there by the engine's rules ({@link RobotMotion}) and standing:
 * <ul>
 *   <li><b>bullets in the air</b> at the decision, the enemies' and our teammates': exact, from the recording; a bullet
 *       that hit this robot flies on beyond it;</li>
 *   <li><b>the enemies' later bullets</b>, fired when they were fired: the chance that this robot was the target (the
 *       enemy team's share of shots at the closest of our robots, from the recordings, if it is the closest there;
 *       the rest split over the others) times the enemies' hit rate at that distance (from the recordings) times the
 *       damage;</li>
 *   <li><b>our robot's own bullets</b>, fired when it fired them, at the enemy it aimed at: our hit rate at that
 *       distance times the damage.</li>
 * </ul>
 * The recorded enemies reacted to our recorded positions; the bench does not move them.
 *
 * <p>Usage: {@code PositionBench recordings=<dir>,... [robot=<name prefix>] [base=<k=v;...>]
 * [variants=<k=v;...>|<k=v;...>...] [horizon=30]}; parameter names are those of {@link MeleeMovementParameters}.
 */
public final class PositionBench {

    private static final double[] BANDS = {0, 200, 300, 400, 500, 600, 700, 800, 1000, Double.POSITIVE_INFINITY};
    /** A bullet whose two nearest bearings are this close is not told to whom it was aimed. */
    private static final double AMBIGUOUS = 0.15;

    private PositionBench() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>(Map.of("robot", "cz.libors.OgiGunLog", "horizon", "30"));
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("not key=value: " + arg);
            }
            options.put(arg.substring(0, eq), arg.substring(eq + 1));
        }
        MeleeMovementParameters base = parameters(MeleeMovementParameters.DEFAULT, options.getOrDefault("base", ""));
        List<MeleeMovementParameters> variants = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String v : options.getOrDefault("variants", "").split("\\|")) {
            if (!v.isBlank()) {
                variants.add(parameters(base, v));
                labels.add(v.trim());
            }
        }
        int horizon = Integer.parseInt(options.get("horizon"));
        long start = System.nanoTime();
        List<PositionBattle> battles = read(options.get("recordings"), options.get("robot"));
        int decisions = battles.stream().flatMap(b -> b.rounds().stream()).mapToInt(r -> r.decisions().size()).sum();
        System.out.printf(Locale.ROOT, "%d battles, %d decisions, read in %.1f s; horizon %d turns%n", battles.size(),
                decisions, (System.nanoTime() - start) / 1e9, horizon);
        if (decisions == 0) {
            // A parity over no decisions would pass. Only OgiGunLog in a team battle logs them, and a recording keeps
            // them only in debug mode.
            System.err.println("No decisions (meleemove) in the recordings: record OgiGunLogTeam against a team set "
                    + "with -PgunLog=true -Pdebug=true");
            System.exit(1);
        }
        Model model = Model.estimate(battles);
        model.print();

        boolean parity = parity(battles, base);
        check(battles, base, model, horizon);
        for (int i = 0; i < variants.size(); i++) {
            report(labels.get(i), battles, base, variants.get(i), model, horizon);
        }
        System.exit(parity ? 0 : 1);
    }

    // ---------------------------------------------------------------------------------------------- the choice

    /** Each decision's destination under the parameters, {x, y}, in the order of the round's decisions. */
    static double[][] replay(PositionBattle battle, PositionBattle.Round round, MeleeMovementParameters p) {
        Map<Integer, MeleeMovement> movements = new HashMap<>();
        Field field = new Field(battle.width(), battle.height());
        double[][] chosen = new double[round.decisions().size()][];
        for (int i = 0; i < chosen.length; i++) {
            PositionBattle.Decision d = round.decisions().get(i);
            MeleeMovement m = movements.computeIfAbsent(d.robot(), r -> new MeleeMovement(field, new Random(0), p));
            m.gangDefence(d.gangDefence());
            m.remember(d.turn(), d.x(), d.y());
            double[] best = m.choose(d.x(), d.y(), d.energy(), d.robots(), d.candidates(), new LoggedWaves(d), null);
            if (Double.isNaN(best[0])) {
                best = new double[] {field.width() / 2, field.height() / 2};
            }
            chosen[i] = best;
        }
        return chosen;
    }

    /** The waves' damage as logged for each candidate. */
    private record LoggedWaves(PositionBattle.Decision d) implements MeleeMovement.WaveSource {
        @Override
        public double damage(double x, double y) {
            double[][] c = d.candidates();
            for (int i = 0; i < c.length; i++) {
                if (c[i][0] == x && c[i][1] == y) {
                    return d.waves()[i];
                }
            }
            throw new IllegalStateException("not a logged candidate: " + x + ", " + y);
        }
    }

    private static boolean parity(List<PositionBattle> battles, MeleeMovementParameters base) {
        long n = 0;
        long differ = 0;
        String first = null;
        for (PositionBattle b : battles) {
            for (PositionBattle.Round r : b.rounds()) {
                double[][] chosen = replay(b, r, base);
                for (int i = 0; i < chosen.length; i++) {
                    PositionBattle.Decision d = r.decisions().get(i);
                    n++;
                    if (chosen[i][0] != d.chosenX() || chosen[i][1] != d.chosenY()) {
                        differ++;
                        if (first == null) {
                            first = String.format(Locale.ROOT, "%s round %d turn %d robot %d: %s instead of %.6f, %.6f",
                                    b.file().getFileName(), r.round(), d.turn(), d.robot(), Arrays.toString(chosen[i]),
                                    d.chosenX(), d.chosenY());
                        }
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "%nParity: the base parameters chose the logged destination in %d of %d "
                + "decisions%s%n", n - differ, n, first == null ? "" : "; first difference: " + first);
        return differ == 0;
    }

    /**
     * The outcome model against what happened: for the logged destinations, the damage the bench gives the bullets in
     * the air and what they really did to the robot within the horizon (the robot decides anew every turn, so its real
     * way drifts from the straight one as the horizon grows).
     */
    private static void check(List<PositionBattle> battles, MeleeMovementParameters base, Model model, int horizon) {
        double predicted = 0;
        double real = 0;
        double later = 0;
        double realLater = 0;
        long n = 0;
        for (PositionBattle b : battles) {
            for (PositionBattle.Round r : b.rounds()) {
                double[][] chosen = replay(b, r, base);
                for (int i = 0; i < chosen.length; i++) {
                    PositionBattle.Decision d = r.decisions().get(i);
                    double[] o = outcome(b, r, d, chosen[i][0], chosen[i][1], model, horizon);
                    predicted += o[0];
                    later += o[1];
                    n++;
                    for (PositionBattle.Bullet bullet : r.bullets()) {
                        if ("HIT_VICTIM".equals(bullet.end()) && bullet.victim() == d.robot()
                                && !b.ours().contains(bullet.owner()) && bullet.endTurn() > d.turn()
                                && bullet.endTurn() <= d.turn() + horizon) {
                            if (bullet.firstTurn() <= d.turn()) {
                                real += BulletMotion.damage(bullet.power());
                            } else {
                                realLater += BulletMotion.damage(bullet.power());
                            }
                        }
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "%nCheck, the logged destinations: per decision, bullets in the air %.4f "
                        + "(really %.4f), later bullets %.4f (really %.4f, those that hit within the horizon)%n",
                predicted / n, real / n, later / n, realLater / n);
    }

    // --------------------------------------------------------------------------------------------- the outcome

    /**
     * What driving straight to ({@code dx}, {@code dy}) from the decision's turn would have met in the next
     * {@code horizon} turns: {taken from bullets in the air, taken from later bullets (expected), dealt (expected),
     * friendly fire from bullets in the air}.
     */
    static double[] outcome(PositionBattle battle, PositionBattle.Round round, PositionBattle.Decision d, double dx,
                            double dy, Model model, int horizon) {
        int t = d.turn();
        int robot = d.robot();
        double[] s = round.states()[t][robot];
        if (s == null) {
            return new double[4];
        }
        EngineRobot r = new EngineRobot();
        r.x = s[0];
        r.y = s[1];
        r.bodyHeading = s[2];
        r.velocity = s[3];
        r.energy = s[4];
        double[][] path = new double[horizon + 1][];
        path[0] = new double[] {r.x, r.y};
        for (int k = 1; k <= horizon; k++) {
            Move cmd = Move.to(r.x, r.y, Angles.fromRobocode(r.bodyHeading), dx, dy);
            r.distanceRemaining = cmd.distance();
            r.bodyTurnRemaining = cmd.rightTurn();
            RobotMotion.move(r, battle.width(), battle.height(), battle.coolingRate());
            path[k] = new double[] {r.x, r.y};
        }
        double inAir = 0;
        double friendly = 0;
        double later = 0;
        double dealt = 0;
        for (PositionBattle.Bullet b : round.bullets()) {
            boolean enemy = !battle.ours().contains(b.owner());
            if (b.firstTurn() <= t && (b.end() == null || b.endTurn() > t)) {
                double hit = inAirHit(battle, b, robot, t, path, horizon);
                if (enemy) {
                    inAir += hit;
                } else if (b.owner() != robot) {
                    friendly += hit;
                }
            } else if (b.firstTurn() > t && b.firstTurn() <= t + horizon) {
                int f = b.firstTurn();
                if (enemy) {
                    later += model.expectedOnUs(battle, round, b, robot, path[Math.max(0, f - 2 - t)]);
                } else if (b.owner() == robot) {
                    dealt += model.expectedByUs(battle, round, b, path[Math.max(0, f - 1 - t)]);
                }
            }
        }
        return new double[] {inAir, later, dealt, friendly};
    }

    /**
     * The damage of a bullet in the air at turn {@code t} on our robot driving {@code path}: by the engine's rule, the
     * bullet's move in turn k against the box where the robot stood at the end of turn k − 1. The bullet keeps its
     * recorded way while it lasts; one that hit this robot flies on.
     */
    private static double inAirHit(PositionBattle battle, PositionBattle.Bullet b, int robot, int t, double[][] path,
                                   int horizon) {
        boolean flies = "HIT_VICTIM".equals(b.end()) && b.victim() == robot;
        double speed = BulletMotion.speed(b.power());
        double[] previous = b.at(t);
        if (previous == null) {
            return 0;
        }
        for (int k = 1; k <= horizon; k++) {
            int turn = t + k;
            double[] now;
            if (b.end() != null && turn >= b.endTurn()) {
                if (!flies) {
                    // It hit a wall, a bullet or another robot in this turn: only its last move remains. The engine
                    // checks the walls before the robots, so a bullet that ended in a wall hits no robot in that move.
                    if (turn > b.endTurn() || "HIT_WALL".equals(b.end())) {
                        return 0;
                    }
                    now = b.at(turn);
                } else {
                    now = new double[] {previous[0] + speed * Math.sin(b.heading()),
                            previous[1] + speed * Math.cos(b.heading())};
                    if (now[0] <= BulletMotion.RADIUS || now[1] <= BulletMotion.RADIUS
                            || now[0] >= battle.width() - BulletMotion.RADIUS
                            || now[1] >= battle.height() - BulletMotion.RADIUS) {
                        return 0;
                    }
                }
            } else {
                now = b.at(turn);
                if (now == null) {
                    return 0;
                }
            }
            RobotBox box = new RobotBox(path[k - 1][0], path[k - 1][1]);
            if (box.intersectsLine(previous[0], previous[1], now[0], now[1])) {
                return BulletMotion.damage(b.power());
            }
            previous = now;
        }
        return 0;
    }

    /** Hit rates by distance and the enemies' share of shots at our closest robot, from the recordings. */
    static final class Model {
        final double[][] them = new double[BANDS.length - 1][2];
        final double[][] us = new double[BANDS.length - 1][2];
        final Map<String, double[]> closest = new TreeMap<>();

        static Model estimate(List<PositionBattle> battles) {
            Model m = new Model();
            for (PositionBattle battle : battles) {
                for (PositionBattle.Round round : battle.rounds()) {
                    for (PositionBattle.Bullet b : round.bullets()) {
                        int[] aimed = aimed(battle, round, b);
                        if (aimed == null) {
                            continue;
                        }
                        boolean enemy = !battle.ours().contains(b.owner());
                        double[] origin = round.states()[b.firstTurn() - 1][b.owner()];
                        double[] target = round.states()[b.firstTurn() - 2][aimed[0]];
                        int band = band(Math.hypot(target[0] - origin[0], target[1] - origin[1]));
                        double[] cell = (enemy ? m.them : m.us)[band];
                        cell[0]++;
                        cell[1] += "HIT_VICTIM".equals(b.end()) && b.victim() == aimed[0] ? 1 : 0;
                        if (enemy && aimed[2] >= 2) {
                            double[] c = m.closest.computeIfAbsent(battle.opponent(), k -> new double[2]);
                            c[0]++;
                            c[1] += aimed[1] == 1 ? 1 : 0;
                        }
                    }
                }
            }
            return m;
        }

        /**
         * The robot a bullet was aimed at: the opposing robot whose bearing at f − 2 from the shooter's position at
         * f − 1 is nearest its heading; {robot, its rank by distance (1 = closest), opposing robots alive}, or null if
         * that cannot be told.
         */
        static int[] aimed(PositionBattle battle, PositionBattle.Round round, PositionBattle.Bullet b) {
            int f = b.firstTurn();
            if (f < 2 || !round.alive(f - 1, b.owner())) {
                return null;
            }
            double[] o = round.states()[f - 1][b.owner()];
            boolean enemy = !battle.ours().contains(b.owner());
            double best = Double.POSITIVE_INFINITY;
            double second = Double.POSITIVE_INFINITY;
            int aimed = -1;
            double aimedDistance = 0;
            List<Double> distances = new ArrayList<>();
            for (int i = 0; i < round.states()[f - 2].length; i++) {
                double[] s = round.states()[f - 2][i];
                if (s == null || battle.ours().contains(i) != enemy) {
                    continue;
                }
                double d = Math.hypot(s[0] - o[0], s[1] - o[1]);
                distances.add(d);
                double diff = Math.abs(Angles.normalizeRelative(b.heading() - Math.atan2(s[0] - o[0], s[1] - o[1])));
                if (diff < best) {
                    second = best;
                    best = diff;
                    aimed = i;
                    aimedDistance = d;
                } else if (diff < second) {
                    second = diff;
                }
            }
            if (aimed < 0) {
                return null;
            }
            double escape = Math.asin(Math.min(1, 8 / BulletMotion.speed(b.power())))
                    + Math.atan(RobotMotion.HALF_SIZE / Math.max(aimedDistance, 1));
            if (best > escape || second - best < AMBIGUOUS) {
                return null;
            }
            final double ad = aimedDistance;
            int rank = 1 + (int) distances.stream().filter(d -> d < ad).count();
            return new int[] {aimed, rank, distances.size()};
        }

        double theirHit(double distance) {
            double[] c = them[band(distance)];
            return (c[1] + 1) / (c[0] + 10);
        }

        double ourHit(double distance) {
            double[] c = us[band(distance)];
            return (c[1] + 1) / (c[0] + 10);
        }

        double closestShare(String opponent) {
            double[] c = closest.get(opponent);
            return c == null ? 0.7 : (c[1] + 1) / (c[0] + 2);
        }

        /**
         * An enemy bullet fired after the decision, against our robot standing at {@code at} when the enemy aimed
         * (f − 2): the chance it was aimed at us there times the enemies' hit rate at that distance times its damage.
         */
        double expectedOnUs(PositionBattle battle, PositionBattle.Round round, PositionBattle.Bullet b, int robot,
                            double[] at) {
            int f = b.firstTurn();
            if (!round.alive(f - 1, b.owner())) {
                return 0;
            }
            double[] o = round.states()[f - 1][b.owner()];
            double ours = Math.hypot(at[0] - o[0], at[1] - o[1]);
            int alive = 1;
            boolean closest = true;
            for (int i : battle.ours()) {
                if (i != robot && round.alive(f - 2, i)) {
                    alive++;
                    double[] s = round.states()[f - 2][i];
                    if (Math.hypot(s[0] - o[0], s[1] - o[1]) < ours) {
                        closest = false;
                    }
                }
            }
            double share = closestShare(battle.opponent());
            double chance = alive == 1 ? 1 : closest ? share : (1 - share) / (alive - 1);
            return chance * theirHit(ours) * BulletMotion.damage(b.power());
        }

        /** One of our robot's later bullets, fired from {@code from} at the enemy it aimed at. */
        double expectedByUs(PositionBattle battle, PositionBattle.Round round, PositionBattle.Bullet b, double[] from) {
            int[] aimed = aimed(battle, round, b);
            if (aimed == null) {
                return 0;
            }
            double[] e = round.states()[b.firstTurn() - 2][aimed[0]];
            return ourHit(Math.hypot(e[0] - from[0], e[1] - from[1])) * BulletMotion.damage(b.power());
        }

        void print() {
            System.out.println("\nModel: hit rate on the aimed robot by distance (bullets told), theirs | ours");
            for (int i = 0; i + 1 < BANDS.length; i++) {
                System.out.printf(Locale.ROOT, "  %6.0f-%-6s %7.0f %5.1f %%  | %7.0f %5.1f %%%n", BANDS[i],
                        Double.isInfinite(BANDS[i + 1]) ? "" : String.format(Locale.ROOT, "%.0f", BANDS[i + 1]),
                        them[i][0], 100 * them[i][1] / Math.max(1, them[i][0]), us[i][0],
                        100 * us[i][1] / Math.max(1, us[i][0]));
            }
            System.out.println("  their shots at the closest of our robots:");
            closest.forEach((k, v) -> System.out.printf(Locale.ROOT, "    %-34s %5.1f %% of %.0f%n", k,
                    100 * v[1] / v[0], v[0]));
        }
    }

    private static int band(double d) {
        for (int i = 0; i + 1 < BANDS.length; i++) {
            if (d < BANDS[i + 1]) {
                return i;
            }
        }
        return BANDS.length - 2;
    }

    // ---------------------------------------------------------------------------------------------- the report

    private static void report(String label, List<PositionBattle> battles, MeleeMovementParameters base,
                               MeleeMovementParameters variant, Model model, int horizon) {
        // Per battle: decisions, changed, and the sums of variant − base for taken in the air, taken later, dealt,
        // friendly fire.
        List<double[]> perBattle = new ArrayList<>();
        Map<String, List<double[]>> perOpponent = new TreeMap<>();
        for (PositionBattle b : battles) {
            double[] sums = new double[6];
            for (PositionBattle.Round r : b.rounds()) {
                double[][] old = replay(b, r, base);
                double[][] now = replay(b, r, variant);
                for (int i = 0; i < old.length; i++) {
                    sums[0]++;
                    if (old[i][0] == now[i][0] && old[i][1] == now[i][1]) {
                        continue;
                    }
                    sums[1]++;
                    PositionBattle.Decision d = r.decisions().get(i);
                    double[] o = outcome(b, r, d, old[i][0], old[i][1], model, horizon);
                    double[] n = outcome(b, r, d, now[i][0], now[i][1], model, horizon);
                    for (int k = 0; k < 4; k++) {
                        sums[2 + k] += n[k] - o[k];
                    }
                }
            }
            perBattle.add(sums);
            perOpponent.computeIfAbsent(b.opponent(), k -> new ArrayList<>()).add(sums);
        }
        System.out.printf(Locale.ROOT, "%nVariant %s%n  %s%n", label, variant);
        print("all", perBattle);
        perOpponent.forEach((k, v) -> print(k, v));
    }

    /**
     * Per decision, variant − base: damage taken from bullets in the air, from later bullets (expected), friendly
     * fire, dealt (expected), and net (dealt − taken − friendly), with the 95 % interval over the battles as clusters.
     */
    private static void print(String name, List<double[]> battles) {
        double decisions = battles.stream().mapToDouble(s -> s[0]).sum();
        double changed = battles.stream().mapToDouble(s -> s[1]).sum();
        double[] mean = new double[5];
        double[] se = new double[5];
        for (int k = 0; k < 5; k++) {
            final int q = k;
            double[] per = battles.stream().mapToDouble(s -> value(s, q) / Math.max(1, s[0])).toArray();
            double m = Arrays.stream(per).average().orElse(0);
            double v = Arrays.stream(per).map(x -> (x - m) * (x - m)).sum() / Math.max(1, per.length - 1);
            mean[k] = m;
            se[k] = per.length < 2 ? Double.NaN : Math.sqrt(v / per.length);
        }
        System.out.printf(Locale.ROOT, "  %-34s changed %5.1f %%  in air %+.4f  later %+.4f  friendly %+.4f  "
                        + "dealt %+.4f  net %+.4f ± %.4f%n", name.length() > 34 ? name.substring(0, 34) : name,
                100 * changed / Math.max(1, decisions), mean[0], mean[1], mean[2], mean[3], mean[4], 1.96 * se[4]);
    }

    /** 0 in the air, 1 later, 2 friendly, 3 dealt, 4 net. */
    private static double value(double[] s, int k) {
        return switch (k) {
            case 0 -> s[2];
            case 1 -> s[3];
            case 2 -> s[5];
            case 3 -> s[4];
            default -> s[4] - s[2] - s[3] - s[5];
        };
    }

    // ------------------------------------------------------------------------------------------------ the input

    private static List<PositionBattle> read(String dirs, String robot) throws Exception {
        if (dirs == null) {
            throw new IllegalArgumentException("missing recordings=<dir>,...");
        }
        List<Path> files = new ArrayList<>();
        for (String dir : dirs.split(",")) {
            try (Stream<Path> list = Files.list(Path.of(dir.trim()))) {
                list.filter(p -> p.getFileName().toString().endsWith(".rec.gz")).sorted().forEach(files::add);
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        try {
            List<Future<PositionBattle>> futures = new ArrayList<>();
            for (Path f : files) {
                futures.add(pool.submit(() -> {
                    try {
                        return PositionBattle.read(f, robot);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));
            }
            List<PositionBattle> battles = new ArrayList<>();
            for (Future<PositionBattle> f : futures) {
                battles.add(f.get());
            }
            return battles;
        } finally {
            pool.shutdown();
        }
    }

    /** {@code base} with the {@code key=value} pairs (separated by {@code ;}) of {@code overrides}. */
    static MeleeMovementParameters parameters(MeleeMovementParameters base, String overrides) throws Exception {
        Map<String, String> values = new HashMap<>();
        for (String kv : overrides.split(";")) {
            if (!kv.isBlank()) {
                String[] p = kv.split("=", 2);
                if (p.length < 2) {
                    throw new IllegalArgumentException("a parameter is name=value, not " + kv.trim());
                }
                values.put(p[0].trim(), p[1].trim());
            }
        }
        RecordComponent[] components = MeleeMovementParameters.class.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent c = components[i];
            types[i] = c.getType();
            String v = values.remove(c.getName());
            args[i] = v == null ? c.getAccessor().invoke(base)
                    : c.getType() == int.class ? Integer.parseInt(v)
                    : c.getType() == boolean.class ? switchValue(c.getName(), v) : Double.parseDouble(v);
        }
        if (!values.isEmpty()) {
            throw new IllegalArgumentException("unknown parameters " + values.keySet() + "; known: "
                    + Arrays.stream(components).map(RecordComponent::getName).toList());
        }
        return MeleeMovementParameters.class.getDeclaredConstructor(types).newInstance(args);
    }

    /** A switch's value, as the melee bench reads its switches: a typo must not silently mean false. */
    private static boolean switchValue(String name, String v) {
        return switch (v) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw new IllegalArgumentException(name + " is true or false, not " + v);
        };
    }
}
