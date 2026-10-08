package cz.libors.harness;

import java.util.List;

/**
 * A battle to run.
 *
 * @param robots robot or team names as Robocode lists them, e.g. {@code sample.Walls} or {@code cz.libors.Ogi 0.1}
 */
public record BattleSetup(String label, int width, int height, int rounds, List<String> robots) {

    public static BattleSetup duel(String subject, String opponent, int rounds) {
        return new BattleSetup("1v1 vs " + opponent, 800, 600, rounds, List.of(subject, opponent));
    }

    public static BattleSetup melee(String label, List<String> robots, int rounds) {
        return new BattleSetup(label, 1000, 1000, rounds, robots);
    }

    public static BattleSetup team(String subjectTeam, String opponentTeam, int rounds) {
        return new BattleSetup("team vs " + opponentTeam, 1200, 1200, rounds, List.of(subjectTeam, opponentTeam));
    }
}
