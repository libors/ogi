package cz.libors.harness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Result of one battle.
 *
 * @param seed         seed the battle ran with; rerunning the setup with it replays the battle, as far as the
 *                     robots themselves are deterministic
 * @param results      contestants sorted by score, highest first
 * @param skippedTurns skipped turns per robot name, as reported by the engine
 * @param problems     engine errors and suspicious robot output (exceptions)
 * @param startMismatches robot rounds that did not begin at the start the setup asked for; 0 without fixed starts
 * @param shots        bullets fired and bullets that hit an enemy, per contestant name as the results name it; only
 *                     bullets that ended before one contestant was left count
 * @param turnTimes    the times robots reported for their own turns (our robots), per robot name
 */
public record BattleOutcome(
        BattleSetup setup,
        long seed,
        List<ContestantResult> results,
        Map<String, Integer> skippedTurns,
        List<String> problems,
        int startMismatches,
        Map<String, Shots> shots,
        long elapsedMillis,
        Map<String, TurnTimes> turnTimes) {

    /**
     * A robot's own turn times as it reported them ({@code TurnClock} in the bot): the longest, how many took longer
     * than half the CPU constant of an idle machine, and how many longer than that constant, which the engine would
     * have skipped with it.
     */
    public record TurnTimes(int turns, long maxMicros, int overHalf, int overIdleConstant) {

        public static final TurnTimes NONE = new TurnTimes(0, 0, 0, 0);

        public TurnTimes plus(TurnTimes o) {
            return new TurnTimes(turns + o.turns, Math.max(maxMicros, o.maxMicros), overHalf + o.overHalf,
                    overIdleConstant + o.overIdleConstant);
        }
    }

    /** The turn times of the robots whose names start with {@code prefix}, combined. */
    public TurnTimes turnTimes(String prefix) {
        TurnTimes sum = TurnTimes.NONE;
        for (Map.Entry<String, TurnTimes> e : turnTimes.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                sum = sum.plus(e.getValue());
            }
        }
        return sum;
    }

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

    /**
     * The place by score of the contestant whose name starts with {@code prefix}: 1 plus the contestants with a higher
     * score, or {@link Integer#MAX_VALUE} without such a contestant. {@link ContestantResult#rank()} is no ranking: the
     * engine's rank is the contestant's position in the battle's setup (engine-model G-31).
     */
    public int placeByScore(String prefix) {
        return result(prefix).map(mine -> 1 + (int) results.stream().filter(r -> r.score() > mine.score()).count())
                .orElse(Integer.MAX_VALUE);
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
