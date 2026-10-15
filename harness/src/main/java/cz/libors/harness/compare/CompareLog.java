package cz.libors.harness.compare;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Append-only log of a comparison's battles, one tab-separated line per battle, written as each battle finishes.
 * Reopening the log with the same configuration resumes the comparison; a different configuration is refused, so
 * results of different jars never mix.
 *
 * <pre>
 * #ogi-compare  3
 * config  key=value ...
 * battle  opponent  pair  side  seed  share  score  opponentScore  survival  lastSurvivorBonus  bulletDamage
 *         bulletDamageBonus  ramDamage  ramDamageBonus  firsts  opponentFirsts  fired  hits  opponentFired
 *         opponentHits  skippedTurns  opponentSkippedTurns  problems  startMismatches  elapsedMillis
 * </pre>
 */
final class CompareLog implements Closeable {

    private static final String MAGIC = "#ogi-compare";
    private static final String VERSION = "3";

    /** Which version fought the battle. */
    enum Side {
        BASELINE, CANDIDATE
    }

    /**
     * One battle of a pair.
     *
     * @param share           the subject's score share in points (0–100)
     * @param fired           bullets the subject fired; {@code hits} of them hit the opponent
     * @param skippedTurns    turns the subject's robots skipped
     * @param problems        exceptions in robot output and engine errors
     * @param startMismatches robot rounds that did not begin at the fixed start
     */
    record Battle(String opponent, int pair, Side side, long seed, double share, int score, int opponentScore,
                  int survival, int lastSurvivorBonus, int bulletDamage, int bulletDamageBonus, int ramDamage,
                  int ramDamageBonus, int firsts, int opponentFirsts, int fired, int hits, int opponentFired,
                  int opponentHits, int skippedTurns, int opponentSkippedTurns, int problems, int startMismatches,
                  long elapsedMillis) {

        /** The subject's hits per bullet fired, in percent; NaN without shots. */
        double hitRate() {
            return fired == 0 ? Double.NaN : 100.0 * hits / fired;
        }

        /** The opponent's hits per bullet fired, in percent; NaN without shots. */
        double opponentHitRate() {
            return opponentFired == 0 ? Double.NaN : 100.0 * opponentHits / opponentFired;
        }

        String line() {
            StringJoiner j = new StringJoiner("\t");
            j.add("battle").add(opponent).add(Integer.toString(pair)).add(side.name()).add(Long.toString(seed))
                    .add(Double.toString(share));
            for (long v : new long[] {score, opponentScore, survival, lastSurvivorBonus, bulletDamage,
                    bulletDamageBonus, ramDamage, ramDamageBonus, firsts, opponentFirsts, fired, hits, opponentFired,
                    opponentHits, skippedTurns, opponentSkippedTurns, problems, startMismatches, elapsedMillis}) {
                j.add(Long.toString(v));
            }
            return j.toString();
        }

        static Battle parse(String line) {
            String[] f = line.split("\t");
            if (f.length != 25 || !f[0].equals("battle")) {
                throw new IllegalArgumentException("not a battle line: " + line);
            }
            int[] v = new int[18];
            for (int i = 0; i < v.length; i++) {
                v[i] = Integer.parseInt(f[6 + i]);
            }
            return new Battle(f[1], Integer.parseInt(f[2]), Side.valueOf(f[3]), Long.parseLong(f[4]),
                    Double.parseDouble(f[5]), v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10],
                    v[11], v[12], v[13], v[14], v[15], v[16], v[17], Long.parseLong(f[24]));
        }
    }

    private final Path file;
    private final List<Battle> battles;
    private final BufferedWriter out;

    private CompareLog(Path file, List<Battle> battles, BufferedWriter out) {
        this.file = file;
        this.battles = battles;
        this.out = out;
    }

    /** Opens the log, creating it or resuming it; fails if it exists with another configuration. */
    static CompareLog open(Path file, Map<String, String> config) throws IOException {
        String configLine = configLine(config);
        List<Battle> battles = new ArrayList<>();
        if (Files.exists(file)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            List<String> lines = text.lines().toList();
            if (lines.size() < 2 || !lines.get(0).equals(MAGIC + "\t" + VERSION)) {
                throw new IOException(file + " is not a comparison log of version " + VERSION
                        + "; use another output directory or delete it");
            }
            if (!lines.get(1).equals(configLine)) {
                throw new IOException(file + " was written with another configuration:\n  " + lines.get(1)
                        + "\nnow:\n  " + configLine + "\nUse another output directory or delete the old one.");
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
            BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            return new CompareLog(file, battles, out);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        out.write(MAGIC + "\t" + VERSION + "\n" + configLine + "\n");
        out.flush();
        return new CompareLog(file, battles, out);
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

    /** The pairs whose two battles are both logged, by opponent, in pair order. */
    Map<String, List<Battle[]>> completePairs() {
        Map<String, Map<Integer, Battle[]>> byOpponent = new LinkedHashMap<>();
        for (Battle b : battles) {
            Battle[] pair = byOpponent.computeIfAbsent(b.opponent(), k -> new TreeMap<>())
                    .computeIfAbsent(b.pair(), k -> new Battle[2]);
            pair[b.side().ordinal()] = b;
        }
        Map<String, List<Battle[]>> complete = new LinkedHashMap<>();
        byOpponent.forEach((opponent, pairs) -> complete.put(opponent,
                pairs.values().stream().filter(p -> p[0] != null && p[1] != null).toList()));
        return complete;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    static String configLine(Map<String, String> config) {
        StringJoiner j = new StringJoiner("\t");
        j.add("config");
        config.forEach((k, v) -> {
            if (v.contains("\t") || v.contains("\n")) {
                throw new IllegalArgumentException("config value with a tab or newline: " + k);
            }
            j.add(k + "=" + v);
        });
        return j.toString();
    }
}
