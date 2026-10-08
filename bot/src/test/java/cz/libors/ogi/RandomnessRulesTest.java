package cz.libors.ogi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tripwire for the bot's randomness rule: the bot draws only from its own generators with explicit seeds.
 *
 * <ul>
 *   <li>{@code Utils.getRandom()} is the engine's own generator. Draws from it shift the engine's processing order
 *       and start positions, and a draw in the robot's last turn after its death races with the engine, so seeded
 *       battles stop replaying (engine-model G-33, G-34).</li>
 *   <li>{@code Math.random()}, unseeded {@code new Random()} and similar sources ignore the battle seed
 *       (G-26).</li>
 * </ul>
 */
class RandomnessRulesTest {

    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("Math\\s*\\.\\s*random\\s*\\("),
            Pattern.compile("getRandom\\s*\\("),
            Pattern.compile("RandomFactory"),
            Pattern.compile("new\\s+Random\\s*\\(\\s*\\)"),
            Pattern.compile("ThreadLocalRandom"),
            Pattern.compile("SecureRandom"),
            Pattern.compile("SplittableRandom\\s*\\(\\s*\\)"),
            Pattern.compile("randomUUID"),
            Pattern.compile("Collections\\s*\\.\\s*shuffle\\s*\\([^,()]*\\)"),
            Pattern.compile("import\\s+static\\s+java\\.lang\\.Math\\.\\*"));

    @Test
    void botDrawsOnlyFromItsOwnSeededGenerators() throws IOException {
        Path sources = Path.of("src/main/java");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(sources)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertTrue(files.size() > 5, "Bot sources not found in " + sources.toAbsolutePath());
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String code = code(lines.get(i));
                for (Pattern p : FORBIDDEN) {
                    if (p.matcher(code).find()) {
                        violations.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            }
        }
        assertEquals(List.of(), violations, "Use an own java.util.Random with an explicit seed instead");
    }

    /** The line without comments; Javadoc and block-comment lines are dropped, trailing line comments cut. */
    private static String code(String line) {
        String t = line.trim();
        if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) {
            return "";
        }
        int comment = t.indexOf("//");
        return comment >= 0 ? t.substring(0, comment) : t;
    }
}
