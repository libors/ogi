package cz.libors.harness;

import cz.libors.harness.recording.Recording;
import cz.libors.harness.recording.Recording.BulletTick;
import cz.libors.harness.recording.Recording.DebugEntry;
import cz.libors.harness.recording.Recording.RobotTick;
import cz.libors.harness.recording.TurnRecorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Records probe battles turn by turn as ground truth for the engine-model tests, and checks the recordings:
 * <ul>
 *   <li><b>integrity:</b> every live probe reported fresh commands in every turn while the round was open, and no
 *       robot skipped a turn;</li>
 *   <li><b>coverage:</b> how often the edge cases occurred (wall hits, robot collisions, velocity reversals, bullet
 *       outcomes);</li>
 *   <li><b>determinism</b> (engine-model G-26, G-33): a seeded battle replays exactly, different seeds differ, and
 *       which sources of randomness a seed controls.</li>
 * </ul>
 *
 * <p>Usage: {@code ProbeRecordings <robocodeHome> <probeVersion> <outputDir>}
 */
public final class ProbeRecordings {

    private static final List<Long> SEEDS = List.of(1L, 2L, 3L);
    private static final long EXPERIMENT_SEED = 42;
    private static final int EXPERIMENT_ROUNDS = 2;

    /** A battle of identical probes. */
    private record Scenario(String name, int width, int height, int rounds, String probe, int count) {

        BattleSetup setup(String version, long seed) {
            String robot = "cz.libors.probe." + probe + " " + version;
            return new BattleSetup(name, width, height, rounds, Collections.nCopies(count, robot), seed);
        }
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("duel", 800, 600, 3, "RandomProbe", 2),
            new Scenario("crowd", 400, 400, 3, "RandomProbe", 6),
            new Scenario("melee", 1000, 1000, 2, "RandomProbe", 10),
            new Scenario("inactive", 800, 600, 1, "PassiveProbe", 2),
            new Scenario("watch-duel", 800, 600, 3, "WatchingProbe", 2),
            new Scenario("watch-melee", 1000, 1000, 2, "WatchingProbe", 8));

    /** How often the edge cases occurred in a recording. */
    record Coverage(int robotTurns, int wallHits, int robotHits, int velocitySignFlips, int stops, int bulletsFired,
                    int bulletsHitRobot, int bulletsHitBullet, int bulletsHitWall, int deaths, int disabledTurns,
                    Map<String, Integer> events) {

        Coverage plus(Coverage o) {
            Map<String, Integer> merged = new TreeMap<>(events);
            o.events.forEach((k, v) -> merged.merge(k, v, Integer::sum));
            return new Coverage(robotTurns + o.robotTurns, wallHits + o.wallHits, robotHits + o.robotHits,
                    velocitySignFlips + o.velocitySignFlips, stops + o.stops, bulletsFired + o.bulletsFired,
                    bulletsHitRobot + o.bulletsHitRobot, bulletsHitBullet + o.bulletsHitBullet,
                    bulletsHitWall + o.bulletsHitWall, deaths + o.deaths, disabledTurns + o.disabledTurns, merged);
        }
    }

    /**
     * @param staleCommands live probe turns, while two or more robots were alive, without a {@code cmd} property of
     *                      that turn
     */
    record Summary(String file, String label, long seed, String digest, int rounds, int turns, int staleCommands,
                   int skippedTurns, List<String> problems, Coverage coverage, long elapsedMillis) {

        boolean intact() {
            return turns > 0 && staleCommands == 0 && skippedTurns == 0 && problems.isEmpty()
                    && !coverage.events().containsKey("SkippedTurn");
        }
    }

    record Check(String label, boolean passed, String detail) {
    }

    private ProbeRecordings() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("Usage: ProbeRecordings <robocodeHome> <probeVersion> <outputDir>");
            System.exit(2);
        }
        Path home = Path.of(args[0]);
        String version = args[1];
        Path out = Path.of(args[2]);

        List<Summary> summaries = new ArrayList<>();
        List<Check> checks = new ArrayList<>();
        Map<String, Coverage> coverage = new LinkedHashMap<>();
        try (BattleRunner runner = new BattleRunner(home)) {
            for (Scenario scenario : SCENARIOS) {
                List<Summary> recorded = new ArrayList<>();
                for (long seed : SEEDS) {
                    Summary s = record(runner, scenario.setup(version, seed),
                            out.resolve(scenario.name() + "-s" + seed + ".rec.gz"));
                    recorded.add(s);
                    coverage.merge(scenario.name(), s.coverage(), Coverage::plus);
                    checks.add(new Check(scenario.name() + " seed " + seed + ": recording intact", s.intact(),
                            integrityDetail(s)));
                }
                long distinct = recorded.stream().map(Summary::digest).distinct().count();
                checks.add(new Check(scenario.name() + ": different seeds give different recordings",
                        distinct == SEEDS.size(), distinct + " distinct of " + SEEDS.size()));

                Summary original = recorded.get(0);
                Summary replay = record(runner, scenario.setup(version, original.seed()),
                        out.resolve("replay").resolve(scenario.name() + "-s" + original.seed() + ".rec.gz"));
                checks.add(new Check(scenario.name() + " seed " + original.seed() + ": replay is identical",
                        replay.intact() && replay.digest().equals(original.digest()),
                        digestDetail(original.digest(), replay.digest())));
                summaries.addAll(recorded);
                summaries.add(replay);
            }

            checks.add(replayExperiment(runner, out, probeDuel("utils-random", "UtilsRandomProbe").setup(version,
                    EXPERIMENT_SEED), true, "Utils.getRandom() in robots is seeded: replay is identical (G-33)",
                    summaries));
            checks.add(replayExperiment(runner, out, probeDuel("math-random", "MathRandomProbe").setup(version,
                    EXPERIMENT_SEED), false, "Math.random() is not seeded: replay differs (G-26)", summaries));
            BattleSetup ogi = new BattleSetup("ogi", 800, 600, EXPERIMENT_ROUNDS,
                    List.of("cz.libors.Ogi " + version, "cz.libors.probe.RandomProbe " + version), EXPERIMENT_SEED);
            checks.add(replayExperiment(runner, out, ogi, true,
                    "Ogi draws only from its own seeded generator: replay is identical", summaries));
            checks.add(sharedGeneratorExperiment(runner, version, out, summaries));
            checks.add(fixedStartsExperiment(runner, version, out, summaries));
            checks.add(ownJarCheck(runner, version));
        }

        Files.createDirectories(out);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("java", System.getProperty("java.vm.vendor") + " " + System.getProperty("java.version"));
        report.put("checks", checks);
        report.put("coverage", coverage);
        report.put("recordings", summaries);
        Path summaryFile = out.resolve("summary.json");
        Files.writeString(summaryFile, Json.write(report));

        System.out.println();
        coverage.forEach((name, c) -> System.out.println(formatCoverage(name, c)));
        System.out.println();
        for (Check c : checks) {
            System.out.printf(Locale.ROOT, "%-4s %s  [%s]%n", c.passed() ? "OK" : "FAIL", c.label(), c.detail());
        }
        long failed = checks.stream().filter(c -> !c.passed()).count();
        System.out.printf(Locale.ROOT, "%n%d/%d checks passed. Details: %s%n", checks.size() - failed, checks.size(),
                summaryFile);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static Scenario probeDuel(String name, String probe) {
        return new Scenario(name, 800, 600, EXPERIMENT_ROUNDS, probe, 2);
    }

    /** Runs the same seeded battle twice and checks whether the recordings are identical. */
    private static Check replayExperiment(BattleRunner runner, Path out, BattleSetup setup, boolean expectIdentical,
                                          String label, List<Summary> summaries) throws IOException {
        Path dir = out.resolve("determinism");
        Summary first = record(runner, setup, dir.resolve(setup.label() + "-1.rec.gz"));
        Summary second = record(runner, setup, dir.resolve(setup.label() + "-2.rec.gz"));
        summaries.add(first);
        summaries.add(second);
        boolean identical = first.digest().equals(second.digest());
        return new Check(label, first.intact() && second.intact() && identical == expectIdentical,
                digestDetail(first.digest(), second.digest()));
    }

    /**
     * Runs one seed with {@code RandomProbe} and with {@code UtilsDrawProbe}, which behaves the same but also draws
     * from {@code Utils.getRandom()}. If robots share the engine's generator, the first round starts at the same
     * positions and the second round, drawn after the robots' extra draws, elsewhere.
     */
    private static Check sharedGeneratorExperiment(BattleRunner runner, String version, Path out,
                                                   List<Summary> summaries) throws IOException {
        Path dir = out.resolve("determinism");
        Summary plain = record(runner, probeDuel("shared-plain", "RandomProbe").setup(version, EXPERIMENT_SEED),
                dir.resolve("shared-plain.rec.gz"));
        Summary drawing = record(runner, probeDuel("shared-draw", "UtilsDrawProbe").setup(version, EXPERIMENT_SEED),
                dir.resolve("shared-draw.rec.gz"));
        summaries.add(plain);
        summaries.add(drawing);
        Recording a = Recording.read(Path.of(plain.file()));
        Recording b = Recording.read(Path.of(drawing.file()));
        boolean firstSame = startPositions(a, 0).equals(startPositions(b, 0));
        boolean secondSame = startPositions(a, 1).equals(startPositions(b, 1));
        return new Check("Utils.getRandom() in robots draws from the engine's generator: round 1 starts the same, "
                + "round 2 elsewhere (G-33)", plain.intact() && drawing.intact() && firstSame && !secondSame,
                "round 1 start " + (firstSame ? "same" : "different") + ", round 2 start "
                        + (secondSame ? "same" : "different"));
    }

    /**
     * Fixed starts (engine-model G-41) in a crowded battle with a start for every robot. Robot 1 is asked to start on
     * robot 0, and robot 2's heading prints with an exponent, which the engine's parser misreads. A start must be used
     * exactly unless its box overlaps a robot already placed in this round, or a robot not yet placed, at the spot
     * where that robot ended the previous round; then the engine must draw another spot.
     */
    private static Check fixedStartsExperiment(BattleRunner runner, String version, Path out,
                                               List<Summary> summaries) throws IOException {
        int count = 8;
        List<BattleSetup.Start> starts = new ArrayList<>(Starts.random(EXPERIMENT_SEED, count, 400, 400));
        starts.set(1, new BattleSetup.Start(starts.get(0).x(), starts.get(0).y(), 90));
        BattleSetup.Start third = starts.get(2);
        starts.set(2, new BattleSetup.Start(third.x(), third.y(), 1.0E-4));
        BattleSetup setup = new BattleSetup("fixed-starts", 400, 400, 8,
                Collections.nCopies(count, "cz.libors.probe.RandomProbe " + version), EXPERIMENT_SEED)
                .withStarts(starts);
        Summary s = record(runner, setup, out.resolve("determinism").resolve("fixed-starts.rec.gz"));
        summaries.add(s);
        Recording rec = Recording.read(Path.of(s.file()));

        Map<Long, RobotTick> first = new HashMap<>();
        Map<Long, RobotTick> last = new HashMap<>();
        for (RobotTick t : rec.robotTicks()) {
            long k = key(t.round(), 0, t.robot());
            if (t.turn() == 0) {
                first.put(k, t);
            }
            last.merge(k, t, (a, b) -> b.turn() > a.turn() ? b : a);
        }
        int used = 0;
        int blockedByPlaced = 0;
        int blockedByPrevious = 0;
        int wrong = 0;
        for (int round = 0; round < setup.rounds(); round++) {
            for (int i = 0; i < count; i++) {
                BattleSetup.Start want = starts.get(i);
                boolean placedInTheWay = false;
                boolean previousInTheWay = false;
                for (int j = 0; j < count; j++) {
                    RobotTick other = j < i ? first.get(key(round, 0, j))
                            : j > i && round > 0 ? last.get(key(round - 1, 0, j)) : null;
                    if (other != null && Starts.overlaps(want.x(), want.y(), other.x(), other.y())) {
                        placedInTheWay |= j < i;
                        previousInTheWay |= j > i;
                    }
                }
                // "1.0E-4" loses its sign and exponent: the engine reads 1.04 degrees.
                double heading = Math.toRadians(i == 2 ? 1.04 : want.headingDegrees());
                RobotTick got = first.get(key(round, 0, i));
                boolean exact = got.x() == want.x() && got.y() == want.y() && got.bodyHeading() == heading;
                if (exact == (placedInTheWay || previousInTheWay)) {
                    wrong++;
                } else if (exact) {
                    used++;
                } else if (placedInTheWay) {
                    blockedByPlaced++;
                } else {
                    blockedByPrevious++;
                }
            }
        }
        return new Check("Fixed starts are used in every round unless a robot is in the way, also one from the "
                + "previous round (G-41)", s.intact() && wrong == 0 && used > 0 && blockedByPlaced > 0
                && blockedByPrevious > 0, String.format(Locale.ROOT,
                "used %d, blocked by a placed robot %d, by a previous-round position %d, unexpected %d", used,
                blockedByPlaced, blockedByPrevious, wrong));
    }

    /**
     * The team jar lists the robot under the same name and version as the robot's own jar (engine-model G-42);
     * {@link BattleRunner} must pick the robot's own jar.
     */
    private static Check ownJarCheck(BattleRunner runner, String version) {
        String name = "cz.libors.Ogi " + version;
        Set<String> listed = runner.listedJars(name).stream().map(p -> p.getFileName().toString())
                .collect(Collectors.toCollection(TreeSet::new));
        String picked = runner.jarFile(name).map(p -> p.getFileName().toString()).orElse("none");
        String own = "cz.libors.Ogi_" + version + ".jar";
        return new Check(name + " is listed by the robot jar and the team jar; the runner uses the robot jar (G-42)",
                listed.equals(Set.of(own, "cz.libors.OgiTeam_" + version + ".jar")) && picked.equals(own),
                "listed " + listed + ", picked " + picked);
    }

    private static List<List<Double>> startPositions(Recording rec, int round) {
        return rec.robotTicks().stream()
                .filter(t -> t.round() == round && t.turn() == 0)
                .map(t -> List.of(t.x(), t.y(), t.bodyHeading()))
                .toList();
    }

    private static Summary record(BattleRunner runner, BattleSetup setup, Path file) throws IOException {
        TurnRecorder recorder = TurnRecorder.open(file, setup);
        BattleOutcome outcome;
        try (recorder) {
            outcome = runner.run(setup, recorder);
        }
        Summary s = summarize(file, outcome, recorder.digest(), Recording.read(file));
        System.out.printf(Locale.ROOT,
                "%-14s seed %-3d %s  turns %6d  stale %d  skipped %d  problems %d  %s  (%.1f s)%n",
                setup.label(), setup.seed(), file.getFileName(), s.turns(), s.staleCommands(), s.skippedTurns(),
                s.problems().size(), s.digest().substring(0, 12), s.elapsedMillis() / 1000.0);
        return s;
    }

    private static Summary summarize(Path file, BattleOutcome outcome, String digest, Recording rec) {
        Set<Long> freshCommands = new HashSet<>();
        Set<Integer> probes = new HashSet<>();
        Map<String, Integer> events = new TreeMap<>();
        for (DebugEntry d : rec.debug()) {
            int comma = d.value().indexOf(',');
            if (d.key().equals("cmd")) {
                probes.add(d.robot());
            }
            if (d.key().equals("cmd") && comma > 0 && Long.parseLong(d.value().substring(0, comma)) == d.turn()) {
                freshCommands.add(key(d.round(), d.turn(), d.robot()));
            } else if (d.key().equals("ev") && comma > 0) {
                events.merge(d.value().substring(0, comma), 1, Integer::sum);
            }
        }
        Map<Long, Integer> alive = new HashMap<>();
        for (RobotTick t : rec.robotTicks()) {
            if (!t.state().equals("DEAD")) {
                alive.merge(key(t.round(), t.turn(), 0), 1, Integer::sum);
            }
        }

        int stale = 0;
        int robotTurns = 0;
        int wallHits = 0;
        int robotHits = 0;
        int signFlips = 0;
        int stops = 0;
        int disabled = 0;
        Set<Long> dead = new HashSet<>();
        Map<Long, Double> lastVelocity = new HashMap<>();
        for (RobotTick t : rec.robotTicks()) {
            long robotKey = key(t.round(), 0, t.robot());
            if (t.state().equals("DEAD")) {
                dead.add(robotKey);
                continue;
            }
            robotTurns++;
            boolean roundOpen = alive.get(key(t.round(), t.turn(), 0)) > 1;
            if (roundOpen && probes.contains(t.robot())
                    && !freshCommands.contains(key(t.round(), t.turn(), t.robot()))) {
                stale++;
            }
            switch (t.state()) {
                case "HIT_WALL" -> wallHits++;
                case "HIT_ROBOT" -> robotHits++;
                default -> {
                }
            }
            if (t.energy() == 0) {
                disabled++;
            }
            Double last = lastVelocity.put(robotKey, t.velocity());
            if (last != null && last * t.velocity() < 0) {
                signFlips++;
            } else if (last != null && last != 0 && t.velocity() == 0) {
                stops++;
            }
        }

        Set<Long> fired = new HashSet<>();
        Map<String, Set<Long>> outcomes = new HashMap<>();
        for (BulletTick b : rec.bulletTicks()) {
            long bulletKey = key(b.round(), b.bulletId(), b.owner());
            fired.add(bulletKey);
            outcomes.computeIfAbsent(b.state(), k -> new HashSet<>()).add(bulletKey);
        }

        Coverage coverage = new Coverage(robotTurns, wallHits, robotHits, signFlips, stops, fired.size(),
                count(outcomes, "HIT_VICTIM"), count(outcomes, "HIT_BULLET"), count(outcomes, "HIT_WALL"),
                dead.size(), disabled, events);
        int turns = rec.roundEnds().stream().mapToInt(Recording.RoundEnd::turns).sum();
        return new Summary(file.toString(), outcome.setup().label(), outcome.seed(), digest, rec.roundEnds().size(),
                turns, stale, outcome.skippedTurns().values().stream().mapToInt(Integer::intValue).sum(),
                outcome.problems(), coverage, outcome.elapsedMillis());
    }

    private static long key(int round, int turn, int robot) {
        return ((long) round << 40) | ((long) turn << 8) | robot;
    }

    private static int count(Map<String, Set<Long>> outcomes, String state) {
        return outcomes.getOrDefault(state, Set.of()).size();
    }

    private static String integrityDetail(Summary s) {
        return String.format(Locale.ROOT, "turns %d, stale commands %d, skipped turns %d, problems %d", s.turns(),
                s.staleCommands(), s.skippedTurns(), s.problems().size());
    }

    private static String digestDetail(String a, String b) {
        return a.substring(0, 12) + (a.equals(b) ? " = " : " ≠ ") + b.substring(0, 12);
    }

    private static String formatCoverage(String name, Coverage c) {
        return String.format(Locale.ROOT,
                "%-6s robot turns %7d  wall hits %5d  robot hits %5d  sign flips %4d  stops %5d  bullets %5d "
                        + "(robot %d, bullet %d, wall %d)  deaths %d  disabled turns %d%n       events %s",
                name, c.robotTurns(), c.wallHits(), c.robotHits(), c.velocitySignFlips(), c.stops(), c.bulletsFired(),
                c.bulletsHitRobot(), c.bulletsHitBullet(), c.bulletsHitWall(), c.deaths(), c.disabledTurns(),
                c.events());
    }
}
