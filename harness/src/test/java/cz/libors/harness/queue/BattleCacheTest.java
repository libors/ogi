package cz.libors.harness.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleCacheTest {

    /** A worker that reports its environment and the jars of its robots. */
    private static final class FakeWorkers implements BattleCache.Workers {
        final Map<String, String> environment = new HashMap<>(Map.of("java", "17", "os", "Windows", "robocode",
                "1.11.1", "jvm", "-XX:+UseSerialGC", "cpuFactor", "1.5", "sandbox", "true"));
        final Map<String, String> jars = new HashMap<>(Map.of("x.Bot 1.0", "c".repeat(64), "sample.Walls",
                "builtin"));

        @Override
        public Optional<Map<String, String>> environment(String worker) {
            return worker.equals("w") ? Optional.of(Map.copyOf(environment)) : Optional.empty();
        }

        @Override
        public Optional<String> robotJar(String worker, String robot) {
            return Optional.ofNullable(jars.get(robot));
        }
    }

    private static BattleSpec battle(String opponent, long seed) {
        return new BattleSpec("1v1 vs " + opponent, 800, 600, 35, List.of("cz.libors.Ogi v1", opponent), seed, null);
    }

    @Test
    void theSameBattleOnTheSameWorkerHasTheSameKey(@TempDir Path tmp) throws IOException {
        FakeWorkers workers = new FakeWorkers();
        BattleCache cache = new BattleCache(tmp.resolve("cache.jsonl"), workers);
        String key = cache.key("w", battle("x.Bot 1.0", 7), "a".repeat(64)).orElseThrow();
        assertEquals(key, cache.key("w", battle("x.Bot 1.0", 7), "a".repeat(64)).orElseThrow());
        // Everything that decides the battle is in the key.
        assertNotEquals(key, cache.key("w", battle("x.Bot 1.0", 8), "a".repeat(64)).orElseThrow(), "seed");
        assertNotEquals(key, cache.key("w", battle("x.Bot 1.0", 7), "b".repeat(64)).orElseThrow(), "our jar");
        assertNotEquals(key, cache.key("w", battle("sample.Walls", 7), "a".repeat(64)).orElseThrow(), "opponent");
        BattleSpec fixed = new BattleSpec("1v1 vs x.Bot 1.0", 800, 600, 35, List.of("cz.libors.Ogi v1", "x.Bot 1.0"),
                7, 99L);
        assertNotEquals(key, cache.key("w", fixed, "a".repeat(64)).orElseThrow(), "fixed starts");
        BattleSpec shorter = new BattleSpec("1v1 vs x.Bot 1.0", 800, 600, 10, List.of("cz.libors.Ogi v1",
                "x.Bot 1.0"), 7, null);
        assertNotEquals(key, cache.key("w", shorter, "a".repeat(64)).orElseThrow(), "rounds");
        workers.jars.put("x.Bot 1.0", "d".repeat(64));
        assertNotEquals(key, cache.key("w", battle("x.Bot 1.0", 7), "a".repeat(64)).orElseThrow(), "their jar");
        workers.jars.put("x.Bot 1.0", "c".repeat(64));
        workers.environment.put("java", "21");
        assertNotEquals(key, cache.key("w", battle("x.Bot 1.0", 7), "a".repeat(64)).orElseThrow(), "the worker");
    }

    @Test
    void anUnknownOpponentJarAWorkerWithoutInfoAndDebugBattlesAreNotCached(@TempDir Path tmp) throws IOException {
        BattleCache cache = new BattleCache(tmp.resolve("cache.jsonl"), new FakeWorkers());
        assertTrue(cache.key("w", battle("y.Unknown 2.0", 7), "a".repeat(64)).isEmpty());
        assertTrue(cache.key("other", battle("x.Bot 1.0", 7), "a".repeat(64)).isEmpty());
        BattleSpec recorded = new BattleSpec("rec", 800, 600, 35, List.of("cz.libors.Ogi v1", "x.Bot 1.0"), 7, null,
                true, null, true);
        assertTrue(cache.key("w", recorded, "a".repeat(64)).isEmpty());
    }

    @Test
    void outcomesSurviveARestartAndACutLine(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("cache.jsonl");
        BattleCache cache = new BattleCache(file, new FakeWorkers());
        String key = cache.key("w", battle("x.Bot 1.0", 7), "a".repeat(64)).orElseThrow();
        cache.put(key, battle("x.Bot 1.0", 7), Map.of("seed", 7, "elapsedMillis", 120));
        Files.writeString(file, "{\"key\": \"cut", java.nio.file.StandardOpenOption.APPEND);
        BattleCache again = new BattleCache(file, new FakeWorkers());
        assertEquals(1, again.size());
        assertEquals(120, ((Number) again.get(key).orElseThrow().get("elapsedMillis")).intValue());
        // The next entry after the cut line gets a line of its own and survives the next restart.
        String next = again.key("w", battle("x.Bot 1.0", 8), "a".repeat(64)).orElseThrow();
        again.put(next, battle("x.Bot 1.0", 8), Map.of("seed", 8, "elapsedMillis", 130));
        BattleCache third = new BattleCache(file, new FakeWorkers());
        assertEquals(2, third.size());
        assertEquals(130, ((Number) third.get(next).orElseThrow().get("elapsedMillis")).intValue());
    }
}
