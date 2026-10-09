package cz.libors.harness;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    private record Point(String name, double x, int count, List<String> tags) {
    }

    @Test
    void readsBackWhatItWrites() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", "tab\t \"quoted\" back\\slash, Δ × 2, line\nbreak");
        m.put("long", Long.MIN_VALUE);
        m.put("double", 0.1 + 0.2);
        m.put("negative", -1.5e-300);
        m.put("flag", true);
        m.put("none", null);
        m.put("list", List.of(1L, "two", List.of()));
        m.put("empty", Map.of());
        for (String json : List.of(Json.write(m), Json.line(m))) {
            assertEquals(m, Json.parse(json));
        }
    }

    @Test
    void aLineIsPlainAscii() {
        String line = Json.line(Map.of("text", "Δ přístup\n"));
        assertTrue(line.chars().allMatch(c -> c >= 0x20 && c < 0x7f), line);
        assertEquals(Map.of("text", "Δ přístup\n"), Json.parse(line));
    }

    @Test
    void recordsAndNonFiniteNumbers() {
        assertEquals(Map.of("name", "p", "x", 1.5, "count", 3L, "tags", List.of("a")),
                Json.parse(Json.line(new Point("p", 1.5, 3, List.of("a")))));
        assertEquals("[null,null]", Json.line(List.of(Double.NaN, Double.POSITIVE_INFINITY)));
    }

    @Test
    void refusesBrokenInput() {
        for (String bad : List.of("{", "{\"a\" 1}", "[1,]x", "\"open", "tru", "{\"a\":1} 2")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
        }
    }
}
