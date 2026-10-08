package cz.libors.harness;

import java.util.List;

/**
 * A battle to run.
 *
 * @param robots robot or team names as Robocode lists them, e.g. {@code sample.Walls} or {@code cz.libors.Ogi 0.1}
 * @param seed   seed of the random generator shared by the engine and {@code Utils.getRandom()}, or {@code null}
 *               for a fresh one. It does not control robots that draw from {@link Math#random()} or from their own
 *               unseeded generators.
 * @param starts start of each robot by robot index (team members expanded, in the order of {@code robots}), used in
 *               every round; {@code null} for the engine's random starts. The engine drops a start that overlaps
 *               another robot and draws a random one instead (engine-model G-41).
 */
public record BattleSetup(String label, int width, int height, int rounds, List<String> robots, Long seed,
                          List<Start> starts) {

    public BattleSetup(String label, int width, int height, int rounds, List<String> robots, Long seed) {
        this(label, width, height, rounds, robots, seed, null);
    }

    /**
     * A robot's start position and the heading of its body, gun and radar. The heading is in degrees and in
     * Robocode's convention (0 = north, clockwise), as the engine's battle files take it.
     */
    public record Start(double x, double y, double headingDegrees) {
    }

    public static BattleSetup duel(String subject, String opponent, int rounds) {
        return new BattleSetup("1v1 vs " + opponent, 800, 600, rounds, List.of(subject, opponent), null);
    }

    public static BattleSetup melee(String label, List<String> robots, int rounds) {
        return new BattleSetup(label, 1000, 1000, rounds, robots, null);
    }

    public static BattleSetup team(String subjectTeam, String opponentTeam, int rounds) {
        return new BattleSetup("team vs " + opponentTeam, 1200, 1200, rounds, List.of(subjectTeam, opponentTeam),
                null);
    }

    public BattleSetup withSeed(Long newSeed) {
        return new BattleSetup(label, width, height, rounds, robots, newSeed, starts);
    }

    public BattleSetup withStarts(List<Start> newStarts) {
        return new BattleSetup(label, width, height, rounds, robots, seed, newStarts);
    }
}
