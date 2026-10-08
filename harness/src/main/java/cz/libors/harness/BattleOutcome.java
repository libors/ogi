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
 * @param shots        bullets fired and bullets that hit an enemy, per contestant name as the results name it; only
 *                     bullets that ended before one contestant was left count
 */
public record BattleOutcome(
        BattleSetup setup,
        long seed,
        List<ContestantResult> results,
        Map<String, Integer> skippedTurns,
        List<String> problems,
        int startMismatches,
        Map<String, Shots> shots,
        long elapsedMillis) {

    /**
     * Bullets a contestant fired that ended while the round was open, and how many of them hit a robot of another
     * contestant.
     */
    public record Shots(int fired, int hits) {

        public static final Shots NONE = new Shots(0, 0);

        /** Hits per bullet fired, in percent; NaN without shots. */
        public double hitRate() {
            return fired == 0 ? Double.NaN : 100.0 * hits / fired;
        }
    }

    /** The shots of the contestant whose name starts with {@code prefix}. */
    public Shots shots(String prefix) {
        return shots.entrySet().stream().filter(e -> e.getKey().startsWith(prefix)).map(Map.Entry::getValue)
                .findFirst().orElse(Shots.NONE);
    }

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
