package cz.libors.harness;

import cz.libors.harness.recording.Recording;
import cz.libors.harness.recording.Recording.BulletTick;
import cz.libors.harness.recording.Recording.DebugEntry;
import cz.libors.harness.recording.Recording.RobotTick;
import cz.libors.harness.recording.TurnRecorder;

import java.io.IOException;
import java.lang.management.ManagementFactory;
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
 *   <li><b>integrity:</b> every live probe that reports its commands ({@code RandomProbe}'s {@code cmd}) reported
 *       fresh ones in every turn while the round was open, no robot skipped a turn, and there were no problems;</li>
 *   <li><b>coverage:</b> how often the edge cases occurred (wall hits, robot collisions, velocity reversals, bullet
 *       outcomes);</li>
 *   <li><b>determinism</b> (engine-model G-26, G-33): a seeded battle replays exactly, different seeds differ, and
 *       which sources of randomness a seed controls;</li>
 *   <li><b>engine facts the harness or the bot relies on:</b> fixed starts (G-41), the robot's own jar (G-42), robot
 *       data cleared before every battle (G-47), team messages (G-23), the order of a turn's events and what the
 *       handlers see in a team (G-52), statics per robot and battle (G-22), fire assist (G-51) and, each in a JVM of
 *       its own without debug mode, a robot thread that outlives its battle ({@link StrandedThreadCheck}, G-48) and
 *       the events a skipped turn clears ({@link SkippedTurnCheck}, G-50).</li>
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

    public static void main(String[] args) throws IOException, InterruptedException {
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
            checks.add(robotDataExperiment(runner, version, out, summaries));
            checks.add(teamMessageExperiment(runner, version, out, summaries));
            checks.addAll(teamEventsExperiment(runner, version, out, summaries));
            checks.add(fireAssistExperiment(runner, version, out, summaries));
            checks.add(ownJarCheck(runner, version));
            checks.add(rankExperiment(runner, version));
        }
        // Once this JVM's engine is closed, as each check starts one of its own on the same home.
        checks.add(childCheck("A robot thread that stops calling the API outlives its battle and keeps computing; the "
                + "runner sees it until it ends (G-48)", StrandedThreadCheck.class, STRANDED_THREAD_SECONDS, args[0],
                version));
        checks.add(childCheck("A skipped turn clears the events waiting for the robot but the critical ones; team "
                + "messages, bullet states and status events survive (G-50)", SkippedTurnCheck.class,
                SKIPPED_TURN_SECONDS, args[0], version));

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
     * Two battles in a row of {@code DataProbe}, which counts the rounds it started in its data file, against {@code
     * RandomProbe}. Within a battle the file must persist from round to round (so the probe really writes it), and
     * the second battle must start without it: {@link BattleRunner} clears the robots' data before every battle.
     */
    private static Check robotDataExperiment(BattleRunner runner, String version, Path out, List<Summary> summaries)
            throws IOException {
        BattleSetup setup = new BattleSetup("robot-data", 800, 600, EXPERIMENT_ROUNDS,
                List.of("cz.libors.probe.DataProbe " + version, "cz.libors.probe.RandomProbe " + version),
                EXPERIMENT_SEED);
        List<List<String>> counts = new ArrayList<>();
        boolean intact = true;
        for (int battle = 1; battle <= 2; battle++) {
            Summary s = record(runner, setup, out.resolve("determinism").resolve("robot-data-" + battle + ".rec.gz"));
            summaries.add(s);
            intact &= s.intact();
            List<String> perRound = new ArrayList<>(Collections.nCopies(setup.rounds(), "none"));
            List<String> errors = new ArrayList<>();
            for (DebugEntry d : Recording.read(Path.of(s.file())).debug()) {
                if (d.robot() == 0 && d.key().equals("data")) {
                    perRound.set(d.round(), d.value());
                } else if (d.robot() == 0 && d.key().equals("dataError")) {
                    errors.add("round " + d.round() + ": " + d.value());
                }
            }
            if (!errors.isEmpty()) {
                // The probe could not read or write its file; the counts alone would hide why.
                perRound.add("errors " + errors);
            }
            counts.add(perRound);
        }
        // "missing" would mean getDataFile no longer creates the file (G-47); without the clearing, "2" and "3".
        List<String> expected = List.of("empty", "1");
        return new Check("Robot data is kept from round to round and cleared before every battle; getDataFile "
                + "creates the file (G-47)", intact
                && counts.get(0).equals(expected) && counts.get(1).equals(expected),
                "the file before each round: first battle " + counts.get(0) + ", second " + counts.get(1));
    }

    /**
     * Team messages (engine-model G-23): a team of three {@code MessageProbe}s, which broadcast the turn they act in
     * and report what they received, against a {@code RandomProbe}. Every message must arrive in the turn after it was
     * sent, stamped with that turn, and every teammate's message of the turn before must be there. Only the turns a
     * probe acted in count: after the enemy's death the round runs on without them.
     */
    private static Check teamMessageExperiment(BattleRunner runner, String version, Path out,
                                               List<Summary> summaries) throws IOException {
        BattleSetup setup = new BattleSetup("team-messages", 800, 600, EXPERIMENT_ROUNDS,
                List.of("cz.libors.probe.MessageTeam " + version, "cz.libors.probe.RandomProbe " + version),
                EXPERIMENT_SEED);
        Summary s = record(runner, setup, out.resolve("determinism").resolve("team-messages.rec.gz"));
        summaries.add(s);
        Recording rec = Recording.read(Path.of(s.file()));
        Map<Integer, String> names = new HashMap<>();
        rec.robots().forEach(r -> names.put(r.index(), r.name()));
        // (round, turn, receiver) -> "sender:sentTurn" of every message received in that turn
        Map<Long, Set<String>> received = new HashMap<>();
        int messages = 0;
        int late = 0;
        for (DebugEntry d : rec.debug()) {
            if (!d.key().equals("msg")) {
                continue;
            }
            String[] parts = d.value().split(",");
            long turn = Long.parseLong(parts[0]);
            if (turn != d.turn()) {
                // Once the enemy is dead the round runs on, but the robots no longer act: their last value stays.
                continue;
            }
            Set<String> got = new HashSet<>();
            for (int i = 1; i < parts.length; i++) {
                int eventColon = parts[i].lastIndexOf(':');
                int sentColon = parts[i].lastIndexOf(':', eventColon - 1);
                long sent = Long.parseLong(parts[i].substring(sentColon + 1, eventColon));
                long stamped = Long.parseLong(parts[i].substring(eventColon + 1));
                messages++;
                if (sent != turn - 1 || stamped != turn) {
                    late++;
                }
                got.add(parts[i].substring(0, sentColon) + ":" + sent);
            }
            received.put(key(d.round(), d.turn(), d.robot()), got);
        }
        int missing = 0;
        for (DebugEntry d : rec.debug()) {
            if (!d.key().equals("msg")) {
                continue;
            }
            Set<String> got = received.get(key(d.round(), d.turn(), d.robot()));
            if (got == null) {
                continue;
            }
            for (Map.Entry<Integer, String> sender : names.entrySet()) {
                // Every teammate that acted (and so broadcast) in the turn before.
                if (sender.getKey() != d.robot()
                        && received.containsKey(key(d.round(), d.turn() - 1, sender.getKey()))
                        && !got.contains(sender.getValue() + ":" + (d.turn() - 1))) {
                    missing++;
                }
            }
        }
        return new Check("Team messages arrive in the next turn, every one of them (G-23)",
                s.intact() && messages > 0 && late == 0 && missing == 0, String.format(Locale.ROOT,
                "%d messages, %d not from the turn before, %d missing", messages, late, missing));
    }

    /**
     * The order of a turn's events and what the handlers see (engine-model G-52), and statics (G-22): two battles in a
     * row of {@code MessageTeam} against three {@code RandomProbe}s. In every turn a {@code MessageProbe} acted in, the
     * messages, deaths and scans it handled must come by priority, highest first (75, 70, 10), and {@code getOthers()}
     * in every handler must already leave out the robots that died in the turn, also in the message handlers that run
     * before the death's; some turns must hold both messages and a death. Each probe counts the rounds it started in a
     * static field: it must read 1 in the first round and 2 in the second, in both battles, so statics last the
     * battle's rounds and belong to one robot, not to its teammates of the same class or to the next battle.
     */
    private static List<Check> teamEventsExperiment(BattleRunner runner, String version, Path out,
                                                    List<Summary> summaries) throws IOException {
        List<String> robots = new ArrayList<>(List.of("cz.libors.probe.MessageTeam " + version));
        robots.addAll(Collections.nCopies(3, "cz.libors.probe.RandomProbe " + version));
        BattleSetup setup = new BattleSetup("team-events", 800, 600, EXPERIMENT_ROUNDS, robots, EXPERIMENT_SEED);
        Map<String, Integer> priority = Map.of("Message", 75, "RobotDeath", 70, "ScannedRobot", 10);
        boolean intact = true;
        int handled = 0;
        int outOfOrder = 0;
        int messagesAndDeath = 0;
        int othersWrong = 0;
        List<String> examples = new ArrayList<>();
        List<String> statics = new ArrayList<>();
        for (int battle = 1; battle <= 2; battle++) {
            Summary s = record(runner, setup, out.resolve("determinism").resolve("team-events-" + battle + ".rec.gz"));
            summaries.add(s);
            intact &= s.intact();
            Recording rec = Recording.read(Path.of(s.file()));
            Set<Integer> probes = rec.robots().stream()
                    .filter(r -> r.name().startsWith("cz.libors.probe.MessageProbe"))
                    .map(Recording.RobotInfo::index).collect(Collectors.toSet());
            Map<Long, Integer> alive = new HashMap<>();
            for (RobotTick t : rec.robotTicks()) {
                if (!t.state().equals("DEAD")) {
                    alive.merge(key(t.round(), t.turn(), 0), 1, Integer::sum);
                }
            }
            // (round, turn, robot) -> the types of the events handled in that turn, in the order the handlers ran
            Map<Long, List<String>> turns = new LinkedHashMap<>();
            Map<Integer, TreeMap<Integer, String>> counted = new TreeMap<>();
            for (DebugEntry d : rec.debug()) {
                if (!probes.contains(d.robot())) {
                    continue;
                }
                if (d.key().equals("statics")) {
                    counted.computeIfAbsent(d.robot(), k -> new TreeMap<>()).put(d.round(), d.value());
                    continue;
                }
                String[] f = d.value().split(",");
                if (!d.key().equals("ev") || Long.parseLong(f[1]) != d.turn()) {
                    // A robot that does not act any more shows its last properties.
                    continue;
                }
                handled++;
                turns.computeIfAbsent(key(d.round(), d.turn(), d.robot()), k -> new ArrayList<>()).add(f[0]);
                int others = alive.get(key(d.round(), d.turn(), 0)) - 1;
                if (Integer.parseInt(f[3]) != others) {
                    othersWrong++;
                    if (examples.size() < 3) {
                        examples.add("round " + d.round() + " turn " + d.turn() + " robot " + d.robot() + ": "
                                + d.value() + ", others alive " + others);
                    }
                }
            }
            for (List<String> types : turns.values()) {
                for (int i = 1; i < types.size(); i++) {
                    outOfOrder += priority.get(types.get(i - 1)) < priority.get(types.get(i)) ? 1 : 0;
                }
                messagesAndDeath += types.contains("Message") && types.contains("RobotDeath") ? 1 : 0;
            }
            counted.values().forEach(perRound -> statics.add(perRound.values().toString()));
        }
        Check order = new Check("A turn's events come by priority, team messages before deaths; getOthers() in the "
                + "handlers already leaves out the robots that died in the turn (G-52)", intact && handled > 0
                && outOfOrder == 0 && othersWrong == 0 && messagesAndDeath > 0, String.format(Locale.ROOT,
                "%d events handled, %d out of order, %d turns with messages and a death, getOthers() wrong %d%s",
                handled, outOfOrder, messagesAndDeath, othersWrong, examples.isEmpty() ? "" : " " + examples));
        List<String> expected = Collections.nCopies(6, List.of("1", "2").toString());
        Check perRobot = new Check("Statics last the rounds of one battle and belong to one robot: neither its "
                + "teammates of the same class nor the next battle see them (G-22)", intact && statics.equals(expected),
                "rounds counted in a static, per probe and battle: " + statics);
        return List.of(order, perRobot);
    }

    /**
     * Fire assist (engine-model G-51): {@code FireAssistProbe}, a plain {@code Robot} whose radar turns with its gun,
     * against two {@code RandomProbe}s. Its shots from {@code onScannedRobot} must leave along the scan's absolute
     * bearing as the probe computed it, bit for bit, also where its gun pointed elsewhere; its shots from {@code
     * run()} along its gun heading at the end of the turn it fired in (G-01).
     */
    private static Check fireAssistExperiment(BattleRunner runner, String version, Path out, List<Summary> summaries)
            throws IOException {
        BattleSetup setup = new BattleSetup("fire-assist", 800, 600, 3, List.of(
                "cz.libors.probe.FireAssistProbe " + version, "cz.libors.probe.RandomProbe " + version,
                "cz.libors.probe.RandomProbe " + version), EXPERIMENT_SEED);
        Summary s = record(runner, setup, out.resolve("determinism").resolve("fire-assist.rec.gz"));
        summaries.add(s);
        Recording rec = Recording.read(Path.of(s.file()));
        int probe = 0;
        Map<Long, RobotTick> ticks = new HashMap<>();
        rec.robotTicks().forEach(t -> ticks.put(key(t.round(), t.turn(), t.robot()), t));
        // (round, owner, bullet id) -> the bullet's first tick, the turn after the shot
        Map<Long, BulletTick> firstTicks = new HashMap<>();
        for (BulletTick b : rec.bulletTicks()) {
            firstTicks.merge(key(b.round(), b.bulletId(), b.owner()), b, (a, c) -> c.turn() < a.turn() ? c : a);
        }
        Map<Long, BulletTick> spawned = new HashMap<>();
        firstTicks.values().forEach(b -> spawned.put(key(b.round(), b.turn(), b.owner()), b));
        Map<Integer, Integer> lastTurn = new HashMap<>();
        rec.roundEnds().forEach(e -> lastTurn.put(e.round(), e.turns()));
        int assisted = 0;
        int offGun = 0;
        int plain = 0;
        int missing = 0;
        List<String> wrong = new ArrayList<>();
        for (DebugEntry d : rec.debug()) {
            boolean assist = d.key().equals("assistShot");
            if (d.robot() != probe || !assist && !d.key().equals("plainShot")) {
                continue;
            }
            String[] f = d.value().split(",");
            if (Long.parseLong(f[0]) != d.turn() || d.turn() >= lastTurn.get(d.round())) {
                continue;
            }
            BulletTick b = spawned.get(key(d.round(), d.turn() + 1, probe));
            if (b == null) {
                missing++;
                continue;
            }
            double gun = ticks.get(key(d.round(), d.turn(), probe)).gunHeading();
            double expected = assist ? Double.parseDouble(f[1]) : gun;
            if (Double.doubleToLongBits(b.heading()) != Double.doubleToLongBits(expected)) {
                wrong.add((assist ? "assisted" : "plain") + " round " + d.round() + " turn " + d.turn() + ": heading "
                        + b.heading() + ", expected " + expected + ", gun " + gun);
            } else if (assist) {
                assisted++;
                offGun += Double.doubleToLongBits(gun) != Double.doubleToLongBits(expected) ? 1 : 0;
            } else {
                plain++;
            }
        }
        return new Check("A plain Robot's shot from onScannedRobot leaves along the scan's bearing, not its gun "
                + "heading (fire assist, G-51)", s.intact() && wrong.isEmpty() && missing == 0 && offGun >= 20
                && plain >= 5, String.format(Locale.ROOT, "%d shots along the scan's bearing, %d of them off the "
                        + "gun heading; %d shots from run() along the gun heading; %d without a bullet; wrong %d%s",
                assisted, offGun, plain, missing, wrong.size(), wrong.isEmpty() ? ""
                        : " " + wrong.subList(0, Math.min(3, wrong.size()))));
    }

    /**
     * The team jar lists the robot under the same name and version as the robot's own jar (engine-model G-42);
     * {@link BattleRunner} must pick the robot's own jar.
     */
    /**
     * The results' rank is the contestant's position in the setup, not its place (G-31): a melee battle with
     * {@code sample.SittingDuck}, which never fires, listed first.
     */
    private static Check rankExperiment(BattleRunner runner, String version) throws IOException {
        String probe = "cz.libors.probe.RandomProbe " + version;
        List<String> robots = new ArrayList<>(List.of("sample.SittingDuck"));
        List<String> named = new ArrayList<>(List.of("sample.SittingDuck"));
        for (int i = 1; i <= 5; i++) {
            robots.add(probe);
            named.add(probe + " (" + i + ")");
        }
        BattleOutcome outcome = runner.run(BattleSetup.melee("ranks", robots, 3).withSeed(EXPERIMENT_SEED));
        int bySetup = 0;
        int byScoreDiffers = 0;
        for (ContestantResult r : outcome.results()) {
            bySetup += r.rank() == named.indexOf(r.name()) + 1 ? 1 : 0;
            byScoreDiffers += outcome.placeByScore(r.name()) != r.rank() ? 1 : 0;
        }
        return new Check("The results' rank is the position in the setup, not the place by score (G-31)",
                bySetup == named.size() && outcome.results().size() == named.size() && byScoreDiffers > 0,
                "rank = setup position for " + bySetup + " of " + named.size() + "; place by score differs for "
                        + byScoreDiffers + "; " + outcome.results().stream()
                        .map(r -> r.name() + " rank " + r.rank() + " score " + r.score()).toList());
    }

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

    /** How long the stranded-thread check's JVM may take: its battle, the 20 s spin and the JVM's start and end. */
    private static final int STRANDED_THREAD_SECONDS = 90;
    /** How long the skipped-turn check's JVM may take: a battle of a few seconds and the JVM's start and end. */
    private static final int SKIPPED_TURN_SECONDS = 120;

    /**
     * A check in a JVM of its own without the engine's debug mode, which prints {@code RESULT\tOK|FAIL\t<detail>}:
     * in debug mode the engine waits for a robot's turn instead of skipping it (G-17), and a stuck probe's thread
     * would also keep computing in this JVM until it ends.
     */
    private static Check childCheck(String label, Class<?> main, int seconds, String home, String version)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        // The parent's options, but not debug mode, agents or a debugger (a second JVM cannot share its port).
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> !a.startsWith("-Ddebug=") && !a.startsWith("-javaagent") && !a.startsWith("-agentlib")
                        && !a.startsWith("-Xrunjdwp") && !a.equals("-Xdebug"))
                .forEach(command::add);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), main.getName(), home, version));
        Path output = Files.createTempFile(main.getSimpleName(), ".txt");
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!p.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor();
        }
        List<String> lines = Files.readAllLines(output, java.nio.charset.StandardCharsets.UTF_8);
        Files.deleteIfExists(output);
        String last = lines.stream().filter(l -> l.startsWith("RESULT\t")).reduce((a, b) -> b).orElse("");
        String[] f = last.split("\t", 3);
        boolean passed = f.length == 3 && f[1].equals("OK") && p.exitValue() == 0;
        return new Check(label, passed, f.length == 3 ? f[2] : "no result (exit " + p.exitValue() + "): "
                + String.join(" | ",
                        lines.subList(Math.max(0, lines.size() - 3), lines.size())));
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
