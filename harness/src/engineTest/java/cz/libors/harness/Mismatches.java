package cz.libors.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Counts disagreements between the engine and the model per field and keeps the first examples. */
final class Mismatches {

    private static final int MAX_EXAMPLES = 15;

    final Map<String, Integer> byField = new TreeMap<>();
    final List<String> examples = new ArrayList<>();

    /** Bit-for-bit comparison, so that 0.0 and -0.0 differ as well. */
    void compare(String where, String field, double engine, double model) {
        if (Double.doubleToLongBits(engine) != Double.doubleToLongBits(model)) {
            add(where, field, "engine " + engine + ", model " + model);
        }
    }

    void add(String where, String field, String detail) {
        byField.merge(field, 1, Integer::sum);
        if (examples.size() < MAX_EXAMPLES) {
            examples.add(where + " " + field + ": " + detail);
        }
    }
}
