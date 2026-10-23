package cz.libors.harness;

import org.junit.jupiter.api.Test;

import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IDebugProperty;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.RobotState;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleRunnerTest {

    @Test
    void onlyTheEnginesLineIsASkippedTurn() {
        assertTrue(BattleRunner.skippedTurn("SYSTEM: Ogi skipped turn 43"));
        assertTrue(BattleRunner.skippedTurn("SYSTEM: kc.mega.BeepBoop skipped turn 1201"));
        assertTrue(BattleRunner.skippedTurn("SYSTEM: Ogi (2) skipped turn 7"));
        assertFalse(BattleRunner.skippedTurn("skipped turns: 0"));
        assertFalse(BattleRunner.skippedTurn("Round 3: skipped turn count 0, hits 12"));
        assertFalse(BattleRunner.skippedTurn("I skipped turn 5 on purpose"));
    }

    @Test
    void anOpponentThatThrowsEveryTurnDoesNotCrowdOutOurProblems() {
        BattleRunner.Collector collector = new BattleRunner.Collector();
        for (int turn = 0; turn < 500; turn++) {
            collector.addProblem("vuen.Bakery 2.51", "vuen.Bakery 2.51 [round 0, turn " + turn + "]: NullPointerException");
        }
        collector.addProblem("cz.libors.Ogi 1", "cz.libors.Ogi 1 [round 3, turn 40]: IllegalStateException");
        List<String> problems = collector.problems();
        assertEquals(1, problems.stream().filter(p -> p.startsWith("cz.libors.Ogi 1 [")).count());
        assertEquals(BattleRunner.MAX_PROBLEMS, problems.stream().filter(p -> p.startsWith("vuen.Bakery 2.51 [")).count());
        assertTrue(problems.contains("vuen.Bakery 2.51: 450 more problem lines left out"));
    }

    /** A robot of a turn snapshot: its contestant, the name the results use for it, and whether it is dead. */
    private static IRobotSnapshot robot(int contestant, String contestantName, boolean dead) {
        Map<String, Object> values = Map.of("getContestantIndex", contestant, "getTeamName", contestantName,
                "getState", dead ? RobotState.DEAD : RobotState.ACTIVE);
        return (IRobotSnapshot) Proxy.newProxyInstance(IRobotSnapshot.class.getClassLoader(),
                new Class<?>[] {IRobotSnapshot.class}, (proxy, method, args) -> values.get(method.getName()));
    }

    private static IBulletSnapshot bullet(int owner, int id, BulletState state, int victim) {
        Map<String, Object> values = Map.of("getOwnerIndex", owner, "getBulletId", id, "getState", state,
                "getVictimIndex", victim, "isExplosion", false);
        return (IBulletSnapshot) Proxy.newProxyInstance(IBulletSnapshot.class.getClassLoader(),
                new Class<?>[] {IBulletSnapshot.class}, (proxy, method, args) -> values.get(method.getName()));
    }

    @Test
    void shotsCountWhileTheRoundIsOpenAndHitsOnlyOnEnemies() {
        BattleRunner.Collector collector = new BattleRunner.Collector();
        IRobotSnapshot[] alive = {robot(0, "a.A 1", false), robot(1, "b.T 1 (1)", false), robot(1, "b.T 1 (1)", false)};
        IRobotSnapshot[] decided = {robot(0, "a.A 1", false), robot(1, "b.T 1 (1)", true), robot(1, "b.T 1 (1)", true)};
        collector.countShots(0, alive, new IBulletSnapshot[] {
                bullet(0, 1, BulletState.FIRED, -1), bullet(1, 1, BulletState.FIRED, -1)});
        collector.countShots(0, alive, new IBulletSnapshot[] {
                bullet(0, 1, BulletState.HIT_VICTIM, 1),
                // friendly fire is no hit, and id 0 (a death's explosion, a leader's energy) no shot
                bullet(1, 1, BulletState.HIT_VICTIM, 2), bullet(0, 0, BulletState.HIT_VICTIM, 1),
                bullet(0, 2, BulletState.FIRED, -1)});
        // One contestant is left: the bullets still in the air are taken back, also one fired in this turn.
        collector.countShots(0, decided, new IBulletSnapshot[] {
                bullet(0, 2, BulletState.MOVING, -1), bullet(0, 3, BulletState.FIRED, -1)});
        collector.countShots(0, decided, new IBulletSnapshot[] {bullet(0, 4, BulletState.FIRED, -1)});
        collector.countShots(1, alive, new IBulletSnapshot[] {bullet(0, 1, BulletState.FIRED, -1)});
        Map<String, BattleOutcome.Shots> shots = collector.shots();
        assertEquals(new BattleOutcome.Shots(2, 1), shots.get("a.A 1"));
        assertEquals(new BattleOutcome.Shots(1, 0), shots.get("b.T 1 (1)"));
    }

    /** Proxies of an interface whose methods return the values given by name, null otherwise. */
    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> values.get(method.getName()));
    }

    /** A turn in which our robot's snapshot shows {@code cpu} and printed {@code output}. */
    private static TurnEndedEvent turn(int turn, String cpu, String output) {
        IDebugProperty property = fake(IDebugProperty.class, Map.of("getKey", BattleRunner.TURN_TIME_PROPERTY,
                "getValue", cpu));
        IRobotSnapshot ogi = fake(IRobotSnapshot.class, Map.of("getName", "cz.libors.Ogi 1", "getTeamName",
                "cz.libors.Ogi 1", "getContestantIndex", 0, "getState", RobotState.ACTIVE, "getOutputStreamSnapshot",
                output, "getDebugProperties", new IDebugProperty[] {property}));
        IRobotSnapshot enemy = fake(IRobotSnapshot.class, Map.of("getName", "a.A 1", "getTeamName", "a.A 1",
                "getContestantIndex", 1, "getState", RobotState.ACTIVE, "getOutputStreamSnapshot", "",
                "getDebugProperties", new IDebugProperty[0]));
        return new TurnEndedEvent(fake(ITurnSnapshot.class, Map.of("getRound", 0, "getTurn", turn,
                "getRobots", new IRobotSnapshot[] {ogi, enemy}, "getBullets", new IBulletSnapshot[0])));
    }

    @Test
    void aTurnOverTheLimitCountsOnceAndTheSkippedTurnsRepeatsNot() {
        // As the engine shows it: turn 50 takes 40 ms; turns 51-62 are skipped and repeat older properties; turn 63,
        // with the engine's lines about them, carries turn 50's time.
        BattleRunner.Collector collector = new BattleRunner.Collector();
        collector.onTurnEnded(turn(49, "3", ""));
        collector.onTurnEnded(turn(50, "3", ""));
        StringBuilder lines = new StringBuilder();
        for (int t = 51; t <= 62; t++) {
            collector.onTurnEnded(turn(t, "3", ""));
            lines.append("SYSTEM: cz.libors.Ogi 1 skipped turn ").append(t).append('\n');
        }
        collector.onTurnEnded(turn(63, "40364", lines.toString()));
        collector.onTurnEnded(turn(64, "5", ""));
        BattleOutcome.TurnTimes times = collector.turnTimes(2000).get("cz.libors.Ogi 1");
        assertEquals(new BattleOutcome.TurnTimes(4, 40364, 1, 1), times);
    }
}
