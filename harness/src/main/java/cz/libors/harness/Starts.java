package cz.libors.harness;

import cz.libors.harness.BattleSetup.Start;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Fixed start positions: drawn from a seed and handed to the engine as a battle's initial positions. */
public final class Starts {

    /** Width and height of a robot's box. */
    static final double ROBOT_SIZE = 36;

    /** Extra gap between generated boxes, so that rounding never makes the engine see an overlap. */
    private static final double GAP = 1;

    private static final int MAX_ATTEMPTS = 100_000;

    private Starts() {
    }

    /**
     * Starts for {@code count} robots whose boxes do not overlap, drawn the way the engine draws them: x and y uniform
     * at least one robot size from the walls, heading uniform. Values are rounded to 0.001 so that the engine's
     * parser reads them back exactly (engine-model G-41).
     */
    public static List<Start> random(long seed, int count, int width, int height) {
        Random random = new Random(seed);
        List<Start> starts = new ArrayList<>(count);
        for (int attempt = 0; starts.size() < count; attempt++) {
            if (attempt == MAX_ATTEMPTS) {
                throw new IllegalArgumentException(count + " robots do not fit on " + width + "x" + height);
            }
            double x = round(ROBOT_SIZE + random.nextDouble() * (width - 2 * ROBOT_SIZE));
            double y = round(ROBOT_SIZE + random.nextDouble() * (height - 2 * ROBOT_SIZE));
            double heading = round(360 * random.nextDouble()) % 360;
            if (starts.stream().noneMatch(s -> overlaps(s.x(), s.y(), x, y, ROBOT_SIZE + GAP))) {
                starts.add(new Start(x, y, heading));
            }
        }
        return starts;
    }

    /**
     * The engine's initial-positions string, {@code (x,y,heading),...}. The engine removes every character other than
     * digits and dots before it parses a number, so values must print without sign or exponent.
     */
    public static String format(List<Start> starts) {
        StringBuilder sb = new StringBuilder();
        for (Start s : starts) {
            sb.append('(').append(s.x()).append(',').append(s.y()).append(',').append(s.headingDegrees()).append("),");
        }
        return sb.toString();
    }

    /** Whether two robot boxes centred at the given points overlap, as the engine tests a start spot. */
    static boolean overlaps(double x1, double y1, double x2, double y2) {
        return overlaps(x1, y1, x2, y2, ROBOT_SIZE);
    }

    private static boolean overlaps(double x1, double y1, double x2, double y2, double size) {
        return Math.abs(x1 - x2) < size && Math.abs(y1 - y2) < size;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
