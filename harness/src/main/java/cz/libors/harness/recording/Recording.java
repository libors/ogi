package cz.libors.harness.recording;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A recording written by {@link TurnRecorder}, read back into memory. It depends on no Robocode classes, so tests
 * can use it without an engine. States are the names of Robocode's {@code RobotState} and {@code BulletState}.
 *
 * @param battle the {@code battle} line as key-value pairs
 */
public record Recording(
        Map<String, String> battle,
        List<RobotInfo> robots,
        List<RobotTick> robotTicks,
        List<BulletTick> bulletTicks,
        List<DebugEntry> debug,
        List<Position> positions,
        List<RoundEnd> roundEnds) {

    public record RobotInfo(int index, String name, int teamIndex, String teamName, boolean droid) {
    }

    public record RobotTick(int round, int turn, int robot, String state, double x, double y, double bodyHeading,
                            double velocity, double energy, double gunHeat, double gunHeading, double radarHeading) {
    }

    /** @param victim robot index, or -1 */
    public record BulletTick(int round, int turn, int owner, int bulletId, String state, double power, double x,
                             double y, double heading, int victim) {
    }

    public record DebugEntry(int round, int turn, int robot, String key, String value) {
    }

    /** A live robot's position in a recording limited to some debug properties ({@link TurnRecorder#POSITIONS}). */
    public record Position(int round, int turn, int robot, double x, double y) {
    }

    public record RoundEnd(int round, int turns) {
    }

    public static Recording read(Path file) throws IOException {
        Map<String, String> battle = new LinkedHashMap<>();
        List<RobotInfo> robots = new ArrayList<>();
        List<RobotTick> robotTicks = new ArrayList<>();
        List<BulletTick> bulletTicks = new ArrayList<>();
        List<DebugEntry> debug = new ArrayList<>();
        List<Position> positions = new ArrayList<>();
        List<RoundEnd> roundEnds = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file), 1 << 16), StandardCharsets.UTF_8), 1 << 16)) {
            String header = in.readLine();
            String expected = TurnRecorder.MAGIC + "\t" + TurnRecorder.VERSION;
            if (!expected.equals(header)) {
                throw new IOException(file + ": not a recording of version " + TurnRecorder.VERSION + ": " + header);
            }
            int lineNumber = 1;
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                lineNumber++;
                String[] f = line.split("\t", -1);
                try {
                    switch (f[0]) {
                        case "r" -> robotTicks.add(new RobotTick(i(f[1]), i(f[2]), i(f[3]), f[4], d(f[5]), d(f[6]),
                                d(f[7]), d(f[8]), d(f[9]), d(f[10]), d(f[11]), d(f[12])));
                        case "b" -> bulletTicks.add(new BulletTick(i(f[1]), i(f[2]), i(f[3]), i(f[4]), f[5],
                                d(f[6]), d(f[7]), d(f[8]), d(f[9]), i(f[10])));
                        case "d" -> debug.add(new DebugEntry(i(f[1]), i(f[2]), i(f[3]), f[4], f[5]));
                        case "p" -> positions.add(new Position(i(f[1]), i(f[2]), i(f[3]), d(f[4]), d(f[5])));
                        case "robot" -> robots.add(new RobotInfo(i(f[1]), f[2], i(f[3]), f[4],
                                Boolean.parseBoolean(f[5])));
                        case "end" -> roundEnds.add(new RoundEnd(i(f[1]), i(f[2])));
                        case "battle" -> {
                            for (int k = 1; k < f.length; k++) {
                                int eq = f[k].indexOf('=');
                                battle.put(f[k].substring(0, eq), f[k].substring(eq + 1));
                            }
                        }
                        default -> throw new IOException("unknown record type " + f[0]);
                    }
                } catch (RuntimeException e) {
                    throw new IOException(file + ":" + lineNumber + ": malformed line: " + line, e);
                }
            }
        }
        return new Recording(battle, robots, robotTicks, bulletTicks, debug, positions, roundEnds);
    }

    private static int i(String s) {
        return Integer.parseInt(s);
    }

    private static double d(String s) {
        return Double.parseDouble(s);
    }
}
