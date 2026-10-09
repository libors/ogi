package cz.libors.harness;

import robocode.BattleResults;
import robocode.control.BattleSpecification;
import robocode.control.BattlefieldSpecification;
import robocode.control.RandomFactory;
import robocode.control.RobocodeEngine;
import robocode.control.RobotSpecification;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.BattleCompletedEvent;
import robocode.control.events.BattleErrorEvent;
import robocode.control.events.IBattleListener;
import robocode.control.events.RoundStartedEvent;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IDebugProperty;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.RobotState;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

/** Runs battles headless on a real Robocode installation through the control API. */
public final class BattleRunner implements AutoCloseable {

    private static final int MAX_PROBLEMS = 50;

    /**
     * The CPU constant of an idle machine, in ns, for robots' own turn times (P-04): the queue's executor passes it,
     * because its slots run with a raised constant; elsewhere the home's constant is the idle one.
     */
    public static final String IDLE_CONSTANT_PROPERTY = "ogi.cpuConstantIdle";

    /** The debug property in which our robots report their last turn's time, in µs (the bot's TurnClock). */
    public static final String TURN_TIME_PROPERTY = "cpu";

    private final Path home;
    private final RobocodeEngine engine;
    private final Collector collector = new Collector();
    private final Random seeds = new Random();

    public BattleRunner(Path robocodeHome) {
        RobocodeEngine.setLogMessagesEnabled(false);
        home = robocodeHome;
        engine = new RobocodeEngine(robocodeHome.toFile());
        engine.addBattleListener(collector);
        engine.setVisible(false);
    }

    /**
     * The idle machine's CPU constant in µs: the executor's, or else the home's, read after a battle because a fresh
     * home gets it only when the engine measures it at the start of its first battle.
     */
    private long idleConstantMicros() {
        long idle = Long.getLong(IDLE_CONSTANT_PROPERTY, 0);
        if (idle <= 0) {
            try {
                idle = cpuConstant(home).orElse(0);
            } catch (IOException e) {
                idle = 0;
            }
        }
        return idle > 0 ? idle / 1000 : Long.MAX_VALUE;
    }

    /**
     * Runs a battle synchronously. Every battle is seeded, with the setup's seed or with a fresh one that the outcome
     * reports, so any battle can be replayed.
     *
     * <p>The seed resets {@link RandomFactory}, the one random generator shared by the harness, the engine (start
     * positions, processing order) and robots calling {@code Utils.getRandom()}. It does not control
     * {@link Math#random()} (engine-model G-26, G-33).
     *
     * <p>Fixed starts go to the engine as its initial-positions string, which, unlike the specification's
     * {@code RobotSetup} array, can place every member of a team (engine-model G-41).
     *
     * <p>A battle with foreign robots runs only while Robocode's sandbox is active ({@link Sandbox}).
     *
     * @param listeners extra listeners for this battle only, e.g. a recorder
     */
    public BattleOutcome run(BattleSetup setup, IBattleListener... listeners) {
        Sandbox.requireFor(setup.robots(), Sandbox.active());
        RobotSpecification[] robots = resolve(setup.robots());
        long seed = setup.seed() != null ? setup.seed() : seeds.nextLong();
        RandomFactory.resetDeterministic(seed);
        collector.reset(setup.starts());
        BattleSpecification spec = new BattleSpecification(setup.rounds(),
                new BattlefieldSpecification(setup.width(), setup.height()), robots);
        String initialPositions = setup.starts() == null ? null : Starts.format(setup.starts());
        for (IBattleListener listener : listeners) {
            engine.addBattleListener(listener);
        }
        long start = System.nanoTime();
        try {
            engine.runBattle(spec, initialPositions, true);
        } finally {
            for (IBattleListener listener : listeners) {
                engine.removeBattleListener(listener);
            }
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        return new BattleOutcome(setup, seed, List.copyOf(collector.results), Map.copyOf(collector.skippedTurns),
                List.copyOf(collector.problems), collector.startMismatches, collector.shots(), elapsedMillis,
                collector.turnTimes(idleConstantMicros()));
    }

    /**
     * Whether a line of a robot's output is the engine's report of a skipped turn, {@code SYSTEM: <name> skipped turn
     * <turn>} ({@code RobotPeer}). Robots print their own statistics too, e.g. "skipped turns: 0" every round.
     */
    static boolean skippedTurn(String line) {
        return SKIPPED_TURN.matcher(line).find();
    }

    private static final java.util.regex.Pattern SKIPPED_TURN =
            java.util.regex.Pattern.compile("^SYSTEM: .+ skipped turn \\d+");

    /** Number of robots in a battle of these contestants, with team members counted one by one. */
    public int robotCount(List<String> names) {
        String selected = String.join(",", Arrays.stream(resolve(names))
                .map(RobotSpecification::getNameAndVersion).toList());
        return engine.getLocalRepository(selected).length;
    }

    public String robocodeVersion() {
        return engine.getVersion();
    }

    /**
     * Every robot and team in the repository, each as its name and its "name version". The engine rescans the
     * robots directory first, so jars copied there since the last battle are listed.
     */
    public List<List<String>> repository() {
        return Arrays.stream(engine.getLocalRepository())
                .map(r -> List.of(r.getName(), r.getNameAndVersion()))
                .distinct()
                .toList();
    }

    /**
     * The SHA-256 of the jar each robot and team would be loaded from in a battle, under each name a set may use for
     * it ("name version" and the bare name, resolved as battles resolve them); "builtin" for one from Robocode's own
     * robots directory. For the queue's battle cache.
     */
    public Map<String, String> repositoryJars() throws IOException {
        Map<Path, String> hashed = new HashMap<>();
        Map<String, String> jars = new TreeMap<>();
        for (List<String> entry : repository()) {
            for (String name : entry) {
                if (jars.containsKey(name)) {
                    continue;
                }
                Optional<Path> jar = jarFile(name);
                if (jar.isEmpty()) {
                    jars.put(name, "builtin");
                } else if (hashed.containsKey(jar.get())) {
                    jars.put(name, hashed.get(jar.get()));
                } else {
                    String sha = sha256(jar.get());
                    hashed.put(jar.get(), sha);
                    jars.put(name, sha);
                }
            }
        }
        return jars;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
                    digest.update(buffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The CPU constant in a Robocode home's configuration, in nanoseconds, if the engine has measured it. */
    public static OptionalLong cpuConstant(Path robocodeHome) throws IOException {
        Path file = robocodeHome.resolve("config/robocode.properties");
        if (!Files.exists(file)) {
            return OptionalLong.empty();
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        String value = p.getProperty("robocode.cpu.constant");
        return value == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value.trim()));
    }

    /** The jar a robot or team is loaded from, if it comes from a jar. */
    public Optional<Path> jarFile(String name) {
        return jarPath(resolve(List.of(name))[0]);
    }

    /** The jars of every repository entry listed under this name. */
    public List<Path> listedJars(String name) {
        return Arrays.stream(engine.getLocalRepository())
                .filter(r -> name.equals(r.getNameAndVersion()) || name.equals(r.getName()))
                .flatMap(r -> jarPath(r).stream())
                .toList();
    }

    /**
     * Looks robots up by exact "name version" (jars) or by name (unversioned, e.g. the sample bots). A team jar's
     * members are listed under the same name as the robot's own jar (engine-model G-42), so a robot from the jar named
     * after it, {@code <name>_<version>.jar}, wins.
     */
    private RobotSpecification[] resolve(List<String> names) {
        RobotSpecification[] all = engine.getLocalRepository();
        List<RobotSpecification> selected = new ArrayList<>();
        for (String name : names) {
            List<RobotSpecification> matches = Arrays.stream(all)
                    .filter(r -> name.equals(r.getNameAndVersion()) || name.equals(r.getName()))
                    .toList();
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("Robot not found in repository: " + name);
            }
            String ownJar = name.replace(' ', '_') + ".jar";
            selected.add(matches.stream()
                    .filter(r -> jarPath(r).map(p -> p.getFileName().toString().equals(ownJar)).orElse(false))
                    .findFirst()
                    .orElse(matches.get(0)));
        }
        return selected.toArray(RobotSpecification[]::new);
    }

    /**
     * The jar of a robot or team. {@code getJarFile()} is never null: it is the repository root's URL wrapped in a
     * {@code File}, {@code jar:file:/…/X.jar!} for a jar (engine-model G-42). On Windows the {@code File} turns the
     * URL's slashes into backslashes, which a URI does not accept.
     */
    private static Optional<Path> jarPath(RobotSpecification robot) {
        String root = robot.getJarFile().getPath();
        if (!root.startsWith("jar:") || !root.endsWith("!")) {
            return Optional.empty();
        }
        String url = root.substring("jar:".length(), root.length() - 1).replace('\\', '/');
        return Optional.of(Path.of(URI.create(url)));
    }

    @Override
    public void close() {
        engine.close();
    }

    private static final class Collector extends BattleAdaptor {

        /** Per robot name: the turn times it reported, in µs. */
        private final Map<String, long[]> turnTimes = new LinkedHashMap<>();
        private final Map<String, Integer> turnCounts = new LinkedHashMap<>();
        private final List<ContestantResult> results = new ArrayList<>();
        private final Map<String, Integer> skippedTurns = new LinkedHashMap<>();
        private final List<String> problems = new ArrayList<>();
        private List<BattleSetup.Start> starts;
        private int startMismatches;
        /** Per contestant name: bullets fired and hits on other contestants. */
        private final Map<String, int[]> shots = new LinkedHashMap<>();
        private final Set<Long> bulletsSeen = new HashSet<>();
        private final Set<Long> hitsSeen = new HashSet<>();
        /** The round in which one contestant was left, or -1. */
        private int decidedRound = -1;

        Map<String, BattleOutcome.TurnTimes> turnTimes(long idleConstantMicros) {
            Map<String, BattleOutcome.TurnTimes> result = new LinkedHashMap<>();
            turnTimes.forEach((name, times) -> {
                int n = turnCounts.get(name);
                long max = 0;
                int overHalf = 0;
                int over = 0;
                for (int i = 0; i < n; i++) {
                    max = Math.max(max, times[i]);
                    overHalf += times[i] > idleConstantMicros / 2 ? 1 : 0;
                    over += times[i] > idleConstantMicros ? 1 : 0;
                }
                result.put(name, new BattleOutcome.TurnTimes(n, max, overHalf, over));
            });
            return result;
        }

        void reset(List<BattleSetup.Start> expectedStarts) {
            turnTimes.clear();
            turnCounts.clear();
            results.clear();
            skippedTurns.clear();
            problems.clear();
            starts = expectedStarts;
            startMismatches = 0;
            shots.clear();
            bulletsSeen.clear();
            hitsSeen.clear();
            decidedRound = -1;
        }

        Map<String, BattleOutcome.Shots> shots() {
            Map<String, BattleOutcome.Shots> copy = new LinkedHashMap<>();
            shots.forEach((name, s) -> copy.put(name, new BattleOutcome.Shots(s[0], s[1])));
            return copy;
        }

        @Override
        public void onRoundStarted(RoundStartedEvent event) {
            if (starts == null) {
                return;
            }
            for (IRobotSnapshot robot : event.getStartSnapshot().getRobots()) {
                int index = robot.getRobotIndex();
                if (index < starts.size()) {
                    BattleSetup.Start s = starts.get(index);
                    if (robot.getX() != s.x() || robot.getY() != s.y()
                            || robot.getBodyHeading() != Math.toRadians(s.headingDegrees())) {
                        startMismatches++;
                    }
                }
            }
        }

        @Override
        public void onTurnEnded(TurnEndedEvent event) {
            int round = event.getTurnSnapshot().getRound();
            int turn = event.getTurnSnapshot().getTurn();
            countShots(round, event.getTurnSnapshot().getRobots(), event.getTurnSnapshot().getBullets());
            for (IRobotSnapshot robot : event.getTurnSnapshot().getRobots()) {
                countTurnTime(robot);
                String output = robot.getOutputStreamSnapshot();
                if (output == null || output.isEmpty()) {
                    continue;
                }
                for (String line : output.split("\\R")) {
                    if (skippedTurn(line)) {
                        skippedTurns.merge(robot.getName(), 1, Integer::sum);
                    } else if (line.contains("Exception") || line.contains("Error")) {
                        addProblem(robot.getName() + " [round " + round + ", turn " + turn + "]: " + line.trim());
                    }
                }
            }
        }

        /**
         * Counts each bullet once when it first appears and once when it hits a robot of another contestant; a
         * contestant's name is its team's, or the robot's own outside a team. Bullet id 0 is no shot: a dying robot's
         * explosion, and the energy a dying leader takes from its teammates (engine {@code RobotPeer.kill}).
         *
         * <p>Only bullets that end while the round is open count. Once one contestant is left, the bullets still in
         * the air are taken back and later ones are ignored: they can no longer hit an enemy, and their number grows
         * with the flight time, so they would tie the hit rate to the distance.
         */
        private void countShots(int round, IRobotSnapshot[] robots, IBulletSnapshot[] bullets) {
            if (round == decidedRound) {
                return;
            }
            for (IBulletSnapshot b : bullets) {
                int owner = b.getOwnerIndex();
                if (owner < 0 || owner >= robots.length || b.isExplosion() || b.getBulletId() == 0) {
                    continue;
                }
                long key = ((long) round << 48) | ((long) owner << 32) | (b.getBulletId() & 0xffffffffL);
                int[] mine = shots.computeIfAbsent(robots[owner].getTeamName(), k -> new int[2]);
                if (bulletsSeen.add(key)) {
                    mine[0]++;
                }
                int victim = b.getVictimIndex();
                if (b.getState() == BulletState.HIT_VICTIM && victim >= 0 && victim < robots.length
                        && robots[victim].getContestantIndex() != robots[owner].getContestantIndex()
                        && hitsSeen.add(key)) {
                    mine[1]++;
                }
            }
            if (contestantsAlive(robots) <= 1) {
                decidedRound = round;
                for (IBulletSnapshot b : bullets) {
                    int owner = b.getOwnerIndex();
                    if (owner >= 0 && owner < robots.length && !b.isExplosion() && b.getBulletId() != 0
                            && (b.getState() == BulletState.FIRED || b.getState() == BulletState.MOVING)) {
                        shots.get(robots[owner].getTeamName())[0]--;
                    }
                }
            }
        }

        private void countTurnTime(IRobotSnapshot robot) {
            IDebugProperty[] properties = robot.getDebugProperties();
            if (properties == null) {
                return;
            }
            for (IDebugProperty p : properties) {
                if (TURN_TIME_PROPERTY.equals(p.getKey())) {
                    long micros;
                    try {
                        micros = Long.parseLong(p.getValue());
                    } catch (NumberFormatException e) {
                        return;
                    }
                    String name = robot.getName();
                    int n = turnCounts.merge(name, 1, Integer::sum);
                    long[] times = turnTimes.computeIfAbsent(name, k -> new long[1024]);
                    if (n > times.length) {
                        times = java.util.Arrays.copyOf(times, 2 * times.length);
                        turnTimes.put(name, times);
                    }
                    times[n - 1] = micros;
                }
            }
        }

        private static int contestantsAlive(IRobotSnapshot[] robots) {
            Set<Integer> alive = new HashSet<>();
            for (IRobotSnapshot r : robots) {
                if (r.getState() != RobotState.DEAD) {
                    alive.add(r.getContestantIndex());
                }
            }
            return alive.size();
        }

        @Override
        public void onBattleError(BattleErrorEvent event) {
            addProblem("engine: " + event.getError());
        }

        @Override
        public void onBattleCompleted(BattleCompletedEvent event) {
            for (BattleResults r : event.getSortedResults()) {
                results.add(ContestantResult.from(r));
            }
        }

        private void addProblem(String problem) {
            if (problems.size() < MAX_PROBLEMS) {
                problems.add(problem);
            }
        }
    }
}
