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
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.RobotState;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/** Runs battles headless on a real Robocode installation through the control API. */
public final class BattleRunner implements AutoCloseable {

    private static final int MAX_PROBLEMS = 50;

    private final RobocodeEngine engine;
    private final Collector collector = new Collector();
    private final Random seeds = new Random();

    public BattleRunner(Path robocodeHome) {
        RobocodeEngine.setLogMessagesEnabled(false);
        engine = new RobocodeEngine(robocodeHome.toFile());
        engine.addBattleListener(collector);
        engine.setVisible(false);
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
                List.copyOf(collector.problems), collector.startMismatches, collector.shots(), elapsedMillis);
    }

    /** Number of robots in a battle of these contestants, with team members counted one by one. */
    public int robotCount(List<String> names) {
        String selected = String.join(",", Arrays.stream(resolve(names))
                .map(RobotSpecification::getNameAndVersion).toList());
        return engine.getLocalRepository(selected).length;
    }

    public String robocodeVersion() {
        return engine.getVersion();
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

        void reset(List<BattleSetup.Start> expectedStarts) {
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
                String output = robot.getOutputStreamSnapshot();
                if (output == null || output.isEmpty()) {
                    continue;
                }
                for (String line : output.split("\\R")) {
                    if (line.contains("skipped turn")) {
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
