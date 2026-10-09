package cz.libors.harness.queue;

import cz.libors.harness.BattleOutcome;
import cz.libors.harness.BattleSetup;
import cz.libors.harness.ContestantResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A battle as the server hands it to a slot, and the codec of its outcome for the way back.
 *
 * @param robots     robot or team names as Robocode lists them
 * @param seed       the battle's seed ({@link cz.libors.harness.BattleRunner#run})
 * @param startsSeed fixed starts drawn from this seed by {@link cz.libors.harness.Starts#random}, the same in every
 *                   round; {@code null} for the engine's random starts
 */
public record BattleSpec(String label, int width, int height, int rounds, List<String> robots, long seed,
                         Long startsSeed) {

    public BattleSpec {
        robots = List.copyOf(robots);
    }

    /** The setup without fixed starts; the slot draws them, because it needs the engine to count team members. */
    public BattleSetup setup() {
        return new BattleSetup(label, width, height, rounds, robots, seed);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("width", width);
        m.put("height", height);
        m.put("rounds", rounds);
        m.put("robots", robots);
        // As a string: a JSON number would lose a long's low bits in a reader that uses doubles.
        m.put("seed", Long.toString(seed));
        m.put("startsSeed", startsSeed == null ? null : Long.toString(startsSeed));
        return m;
    }

    public static BattleSpec fromMap(Map<String, Object> m) {
        List<String> robots = new ArrayList<>();
        for (Object r : (List<?>) m.get("robots")) {
            robots.add((String) r);
        }
        Object starts = m.get("startsSeed");
        return new BattleSpec((String) m.get("label"), number(m, "width"), number(m, "height"), number(m, "rounds"),
                robots, Long.parseLong((String) m.get("seed")), starts == null ? null : Long.parseLong((String) starts));
    }

    /** A battle's outcome as a message field; the setup stays behind, the receiver has the spec. */
    public static Map<String, Object> outcomeToMap(BattleOutcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seed", Long.toString(o.seed()));
        List<Object> results = new ArrayList<>();
        for (ContestantResult r : o.results()) {
            results.add(List.of(r.name(), r.rank(), r.score(), r.survival(), r.lastSurvivorBonus(), r.bulletDamage(),
                    r.bulletDamageBonus(), r.ramDamage(), r.ramDamageBonus(), r.firsts()));
        }
        m.put("results", results);
        m.put("skippedTurns", new LinkedHashMap<>(o.skippedTurns()));
        m.put("problems", o.problems());
        m.put("startMismatches", o.startMismatches());
        Map<String, Object> shots = new LinkedHashMap<>();
        o.shots().forEach((name, s) -> shots.put(name, List.of(s.fired(), s.hits())));
        m.put("shots", shots);
        m.put("elapsedMillis", o.elapsedMillis());
        return m;
    }

    public BattleOutcome outcomeFromMap(Map<String, Object> m) {
        List<ContestantResult> results = new ArrayList<>();
        for (Object item : (List<?>) m.get("results")) {
            List<?> r = (List<?>) item;
            results.add(new ContestantResult((String) r.get(0), integer(r.get(1)), integer(r.get(2)),
                    integer(r.get(3)), integer(r.get(4)), integer(r.get(5)), integer(r.get(6)), integer(r.get(7)),
                    integer(r.get(8)), integer(r.get(9))));
        }
        Map<String, Integer> skipped = new LinkedHashMap<>();
        ((Map<?, ?>) m.get("skippedTurns")).forEach((k, v) -> skipped.put((String) k, integer(v)));
        List<String> problems = new ArrayList<>();
        for (Object p : (List<?>) m.get("problems")) {
            problems.add((String) p);
        }
        Map<String, BattleOutcome.Shots> shots = new LinkedHashMap<>();
        ((Map<?, ?>) m.get("shots")).forEach((k, v) -> {
            List<?> s = (List<?>) v;
            shots.put((String) k, new BattleOutcome.Shots(integer(s.get(0)), integer(s.get(1))));
        });
        return new BattleOutcome(setup(), Long.parseLong((String) m.get("seed")), results, skipped, problems,
                integer(m.get("startMismatches")), shots, ((Number) m.get("elapsedMillis")).longValue());
    }

    private static int number(Map<String, Object> m, String key) {
        return integer(m.get(key));
    }

    private static int integer(Object v) {
        return ((Number) v).intValue();
    }
}
