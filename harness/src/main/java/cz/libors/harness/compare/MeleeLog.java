package cz.libors.harness.compare;

import cz.libors.harness.compare.CompareLog.Side;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Append-only log of a melee comparison's battles, one tab-separated line per battle, written as each battle
 * finishes; like {@link CompareLog}, reopened with the same configuration it resumes the comparison, and another
 * configuration is refused.
 *
 * <pre>
 * #ogi-melee  2
 * config  key=value ...
 * battle  draw  side  seed  subjectIndex  score  survival  lastSurvivorBonus  bulletDamage  bulletDamageBonus
 *         ramDamage  ramDamageBonus  firsts  fired  hits  skippedTurns  opponentSkippedTurns  problems
 *         elapsedMillis  then for each opponent: name  score  firsts  skippedTurns
 * </pre>
 */
final class MeleeLog implements Closeable {

    private static final String MAGIC = "#ogi-melee";
    private static final String VERSION = "2";
    private static final int FIXED_FIELDS = 19;

    /** An opponent's totals in a battle, and the turns it skipped. */
    record Opponent(String name, int score, int firsts, int skippedTurns) {
    }

    /**
     * One battle of a draw: the subject's totals and every opponent's.
     *
     * @param fired        bullets the subject fired; {@code hits} of them hit an opponent
     * @param skippedTurns turns the subject skipped; {@code opponentSkippedTurns} the opponents' together
     */
    record Battle(int draw, Side side, long seed, int subjectIndex, int score, int survival, int lastSurvivorBonus,
                  int bulletDamage, int bulletDamageBonus, int ramDamage, int ramDamageBonus, int firsts, int fired,
                  int hits, int skippedTurns, int opponentSkippedTurns, int problems, long elapsedMillis,
                  List<Opponent> opponents) {

        Battle {
            opponents = List.copyOf(opponents);
        }

        /** The subject's share of its own and {@code o}'s score, in points; 50 if neither scored. */
        double share(Opponent o) {
            int total = score + o.score();
            return total == 0 ? 50 : 100.0 * score / total;
        }

        /** The mean of the pairwise shares over the battle's opponents: the rumble's measure of one battle. */
        double share() {
            double sum = 0;
            for (Opponent o : opponents) {
                sum += share(o);
            }
            return sum / opponents.size();
        }

        String line() {
            StringJoiner j = new StringJoiner("\t");
            j.add("battle").add(Integer.toString(draw)).add(side.name()).add(Long.toString(seed));
            for (long v : new long[] {subjectIndex, score, survival, lastSurvivorBonus, bulletDamage,
                    bulletDamageBonus, ramDamage, ramDamageBonus, firsts, fired, hits, skippedTurns,
                    opponentSkippedTurns, problems, elapsedMillis}) {
                j.add(Long.toString(v));
            }
            for (Opponent o : opponents) {
                j.add(o.name()).add(Integer.toString(o.score())).add(Integer.toString(o.firsts()))
                        .add(Integer.toString(o.skippedTurns()));
            }
            return j.toString();
        }

        static Battle parse(String line) {
            String[] f = line.split("\t");
            if (f.length < FIXED_FIELDS || (f.length - FIXED_FIELDS) % 4 != 0 || !f[0].equals("battle")) {
                throw new IllegalArgumentException("not a melee battle line: " + line);
            }
            int[] v = new int[14];
            for (int i = 0; i < v.length; i++) {
                v[i] = Integer.parseInt(f[4 + i]);
            }
            List<Opponent> opponents = new ArrayList<>();
            for (int i = FIXED_FIELDS; i < f.length; i += 4) {
                opponents.add(new Opponent(f[i], Integer.parseInt(f[i + 1]), Integer.parseInt(f[i + 2]),
                        Integer.parseInt(f[i + 3])));
            }
            return new Battle(Integer.parseInt(f[1]), Side.valueOf(f[2]), Long.parseLong(f[3]), v[0], v[1], v[2],
                    v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13],
                    Long.parseLong(f[18]), opponents);
        }
    }

    private final Path file;
    private final List<Battle> battles;
    private final BufferedWriter out;

    private MeleeLog(Path file, List<Battle> battles, BufferedWriter out) {
        this.file = file;
        this.battles = battles;
        this.out = out;
    }

    /** Opens the log, creating it or resuming it; fails if it exists with another configuration. */
    static MeleeLog open(Path file, Map<String, String> config) throws IOException {
        String configLine = CompareLog.configLine(config);
        List<Battle> battles = new ArrayList<>();
        if (Files.exists(file)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            List<String> lines = text.lines().toList();
            if (lines.size() < 2 || !lines.get(0).equals(MAGIC + "\t" + VERSION)) {
                throw new IOException(file + " is not a melee comparison log of version " + VERSION);
            }
            if (!lines.get(1).equals(configLine)) {
                throw new CompareLog.ConfigurationChanged(file + " was written with another configuration:\n  "
                        + lines.get(1)
                        + "\nnow:\n  " + configLine);
            }
            if (!text.endsWith("\n")) {
                // The last line was cut off by a crash: drop it.
                text = text.substring(0, text.lastIndexOf('\n') + 1);
                Files.writeString(file, text, StandardCharsets.UTF_8);
                lines = text.lines().toList();
            }
            for (int i = 2; i < lines.size(); i++) {
                battles.add(Battle.parse(lines.get(i)));
            }
            return new MeleeLog(file, battles,
                    Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND));
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        out.write(MAGIC + "\t" + VERSION + "\n" + configLine + "\n");
        out.flush();
        return new MeleeLog(file, battles, out);
    }

    Path file() {
        return file;
    }

    List<Battle> battles() {
        return List.copyOf(battles);
    }

    void append(Battle battle) throws IOException {
        out.write(battle.line());
        out.write('\n');
        out.flush();
        battles.add(battle);
    }

    /** The draws whose two battles are both logged, in draw order: baseline first. */
    List<Battle[]> completeDraws() {
        Map<Integer, Battle[]> byDraw = new TreeMap<>();
        for (Battle b : battles) {
            byDraw.computeIfAbsent(b.draw(), k -> new Battle[2])[b.side().ordinal()] = b;
        }
        return byDraw.values().stream().filter(p -> p[0] != null && p[1] != null).toList();
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
