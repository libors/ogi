package cz.libors.harness.compare;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A fixed set of opponents and the battle format to meet them in, read from a text file:
 *
 * <pre>
 * # comment
 * title The sample bots
 * format 1v1
 * weight [strong] 10
 * weight [other] 90
 * [strong]
 * sample.RamFire
 * [other]
 * sample.SpinBot
 * </pre>
 *
 * <p>One robot or team per line, named as Robocode lists it (with its version, if it has one). A line {@code [name]}
 * starts a group: opponents under {@code [strong]} make up the second scoreboard, every other group but
 * {@code [other]} a scoreboard of its own. The optional {@code title} says in words what the set is.
 *
 * <p>A line {@code weight [name] n} says how many robots of the rumble the group stands for, e.g. a rumble tier's
 * size. With weights, every group needs one, and the report adds a rumble estimate: each opponent weighs the same
 * within its group, and the groups weigh as their robots in the rumble.
 *
 * @param title   the set's {@code title} line, or empty
 * @param weights per group, the robots of the rumble it stands for; empty without {@code weight} lines
 */
record OpponentSet(String name, String title, Format format, List<Opponent> opponents, Map<String, Double> weights) {

    /** @param group the section the opponent is listed under, empty before the first */
    record Opponent(String name, boolean strong, String group) {
    }

    /** The rumble's battle settings. */
    enum Format {
        DUEL("1v1", 800, 600, 35),
        TEAM("team", 1200, 1200, 10);

        final String label;
        final int width;
        final int height;
        final int rounds;

        Format(String label, int width, int height, int rounds) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.rounds = rounds;
        }

        static Format of(String label) {
            for (Format f : values()) {
                if (f.label.equals(label)) {
                    return f;
                }
            }
            throw new IllegalArgumentException("unknown format: " + label);
        }
    }

    static OpponentSet read(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        String name = fileName.endsWith(".txt") ? fileName.substring(0, fileName.length() - 4) : fileName;
        return parse(name, Files.readAllLines(file));
    }

    private static final Set<String> KEYWORDS = Set.of("format", "title", "weight");
    /** Scoreboards every comparison shows; a group of the same name would show twice. */
    private static final Set<String> RESERVED_GROUPS = Set.of("all", "rumble");

    static OpponentSet parse(String name, List<String> lines) {
        Format format = null;
        String title = "";
        String group = "";
        List<Opponent> opponents = new ArrayList<>();
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String raw : lines) {
            int comment = raw.indexOf('#');
            String line = (comment >= 0 ? raw.substring(0, comment) : raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            String keyword = line.split("\\s+", 2)[0];
            String argument = line.substring(keyword.length()).trim();
            if (KEYWORDS.contains(keyword) && argument.isEmpty()) {
                throw new IllegalArgumentException(name + ": " + keyword + " needs an argument");
            }
            if (keyword.equals("format")) {
                if (format != null) {
                    throw new IllegalArgumentException(name + ": a second format line: " + line);
                }
                format = Format.of(argument);
            } else if (keyword.equals("title")) {
                title = argument;
            } else if (keyword.equals("weight")) {
                int close = argument.indexOf(']');
                double weight;
                try {
                    weight = argument.startsWith("[") && close > 1
                            ? Double.parseDouble(argument.substring(close + 1).trim()) : Double.NaN;
                } catch (NumberFormatException e) {
                    weight = Double.NaN;
                }
                if (!(weight > 0) || Double.isInfinite(weight)) {
                    throw new IllegalArgumentException(name + ": expected weight [group] <positive number>: " + line);
                }
                if (weights.put(argument.substring(1, close).trim(), weight) != null) {
                    throw new IllegalArgumentException(name + ": a second weight for the same group: " + line);
                }
            } else if (line.startsWith("[") && line.endsWith("]")) {
                group = line.substring(1, line.length() - 1).trim();
                if (RESERVED_GROUPS.contains(group)) {
                    throw new IllegalArgumentException(name + ": [" + group + "] is the name of a built-in scoreboard");
                }
            } else if (opponents.stream().anyMatch(o -> o.name().equals(line))) {
                throw new IllegalArgumentException(name + ": " + line + " is listed twice");
            } else {
                opponents.add(new Opponent(line, group.equals("strong"), group));
            }
        }
        if (format == null || opponents.isEmpty()) {
            throw new IllegalArgumentException(name + ": needs a format line and at least one opponent");
        }
        if (!weights.isEmpty()) {
            for (Opponent o : opponents) {
                if (!weights.containsKey(o.group())) {
                    throw new IllegalArgumentException(name + ": " + o.name() + " is in a group without a weight");
                }
            }
        }
        return new OpponentSet(name, title, format, List.copyOf(opponents), Map.copyOf(weights));
    }
}
