package cz.libors.harness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Result of one battle.
 *
 * @param seed         seed the battle ran with; rerunning the setup with it replays the battle, as far as the
 *                     robots themselves are deterministic
 * @param results      contestants sorted by rank
 * @param skippedTurns skipped turns per robot name, as reported by the engine
 * @param problems     engine errors and suspicious robot output (exceptions)
 * @param startMismatches robot rounds that did not begin at the start the setup asked for; 0 without fixed starts
 */
public record BattleOutcome(
        BattleSetup setup,
        long seed,
        List<ContestantResult> results,
        Map<String, Integer> skippedTurns,
        List<String> problems,
        int startMismatches,
        long elapsedMillis) {

    /** The contestant whose name starts with {@code prefix}. */
    public Optional<ContestantResult> result(String prefix) {
        return results.stream().filter(r -> r.name().startsWith(prefix)).findFirst();
    }

    /** Share of the total score taken by the contestant whose name starts with {@code prefix}. */
    public double scoreShare(String prefix) {
        double total = results.stream().mapToInt(ContestantResult::score).sum();
        return total == 0 ? 0 : result(prefix).map(r -> r.score() / total).orElse(0.0);
    }

    public int skippedTurns(String prefix) {
        return skippedTurns.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    public List<String> problems(String prefix) {
        return problems.stream().filter(p -> p.startsWith(prefix) || p.startsWith("engine:")).toList();
    }
}
