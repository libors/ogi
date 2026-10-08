package cz.libors.harness;

import robocode.BattleResults;
import robocode.control.BattleSpecification;
import robocode.control.BattlefieldSpecification;
import robocode.control.RobocodeEngine;
import robocode.control.RobotSpecification;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.BattleCompletedEvent;
import robocode.control.events.BattleErrorEvent;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.IRobotSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs battles headless on a real Robocode installation through the control API. */
public final class BattleRunner implements AutoCloseable {

    private static final int MAX_PROBLEMS = 50;

    private final RobocodeEngine engine;
    private final Recorder recorder = new Recorder();

    public BattleRunner(Path robocodeHome) {
        RobocodeEngine.setLogMessagesEnabled(false);
        engine = new RobocodeEngine(robocodeHome.toFile());
        engine.addBattleListener(recorder);
        engine.setVisible(false);
    }

    public BattleOutcome run(BattleSetup setup) {
        RobotSpecification[] robots = resolve(setup.robots());
        recorder.reset();
        BattleSpecification spec = new BattleSpecification(setup.rounds(),
                new BattlefieldSpecification(setup.width(), setup.height()), robots);
        long start = System.nanoTime();
        engine.runBattle(spec, true);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        return new BattleOutcome(setup, List.copyOf(recorder.results), Map.copyOf(recorder.skippedTurns),
                List.copyOf(recorder.problems), elapsedMillis);
    }

    /** Looks robots up by exact "name version" (jars) or by name (unversioned, e.g. the sample bots). */
    private RobotSpecification[] resolve(List<String> names) {
        RobotSpecification[] all = engine.getLocalRepository();
        List<RobotSpecification> selected = new ArrayList<>();
        for (String name : names) {
            RobotSpecification match = Arrays.stream(all)
                    .filter(r -> name.equals(r.getNameAndVersion()) || name.equals(r.getName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Robot not found in repository: " + name));
            selected.add(match);
        }
        return selected.toArray(RobotSpecification[]::new);
    }

    @Override
    public void close() {
        engine.close();
    }

    private static final class Recorder extends BattleAdaptor {

        private final List<ContestantResult> results = new ArrayList<>();
        private final Map<String, Integer> skippedTurns = new LinkedHashMap<>();
        private final List<String> problems = new ArrayList<>();

        void reset() {
            results.clear();
            skippedTurns.clear();
            problems.clear();
        }

        @Override
        public void onTurnEnded(TurnEndedEvent event) {
            int round = event.getTurnSnapshot().getRound();
            int turn = event.getTurnSnapshot().getTurn();
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
