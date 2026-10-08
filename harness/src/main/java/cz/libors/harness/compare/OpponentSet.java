package cz.libors.harness.compare;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A fixed set of opponents and the battle format to meet them in, read from a text file:
 *
 * <pre>
 * # comment
 * format 1v1
 * [strong]
 * sample.RamFire
 * [other]
 * sample.SpinBot
 * </pre>
 *
 * <p>One robot or team per line, named as Robocode lists it (with its version, if it has one). Opponents in the
 * {@code [strong]} section make up the second scoreboard.
 */
record OpponentSet(String name, Format format, List<Opponent> opponents) {

    record Opponent(String name, boolean strong) {
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

    static OpponentSet parse(String name, List<String> lines) {
        Format format = null;
        boolean strong = false;
        List<Opponent> opponents = new ArrayList<>();
        for (String raw : lines) {
            int comment = raw.indexOf('#');
            String line = (comment >= 0 ? raw.substring(0, comment) : raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("format ")) {
                format = Format.of(line.substring("format ".length()).trim());
            } else if (line.equals("[strong]") || line.equals("[other]")) {
                strong = line.equals("[strong]");
            } else if (opponents.stream().anyMatch(o -> o.name().equals(line))) {
                throw new IllegalArgumentException(name + ": " + line + " is listed twice");
            } else {
                opponents.add(new Opponent(line, strong));
            }
        }
        if (format == null || opponents.isEmpty()) {
            throw new IllegalArgumentException(name + ": needs a format line and at least one opponent");
        }
        return new OpponentSet(name, format, List.copyOf(opponents));
    }
}
