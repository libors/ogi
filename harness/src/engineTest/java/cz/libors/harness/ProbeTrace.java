package cz.libors.harness;

import cz.libors.harness.recording.Recording;
import cz.libors.harness.recording.Recording.BulletTick;
import cz.libors.harness.recording.Recording.DebugEntry;
import cz.libors.harness.recording.Recording.RobotTick;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** A probe recording indexed by round, turn and robot, with the probes' {@code cmd} and {@code rem} parsed. */
final class ProbeTrace {

    /** A probe's {@code cmd} property: the commands it sent in a turn. */
    record Command(long time, double distance, double bodyTurn, double gunTurn, double radarTurn, double maxVelocity,
                   double maxTurnRate, boolean adjustGunForBody, boolean adjustRadarForGun,
                   boolean adjustRadarForBody, double firePower, boolean fired) {

        static Command parse(String value) {
            String[] f = value.split(",");
            return new Command(Long.parseLong(f[0]), d(f[1]), d(f[2]), d(f[3]), d(f[4]), d(f[5]), d(f[6]),
                    Boolean.parseBoolean(f[7]), Boolean.parseBoolean(f[8]), Boolean.parseBoolean(f[9]),
                    f[10].equals("-") ? Double.NaN : d(f[10]), Boolean.parseBoolean(f[11]));
        }
    }

    /** A probe's {@code rem} property: the remaining values the engine left after a turn's move. */
    record Remaining(long time, double distance, double bodyTurn, double gunTurn, double radarTurn) {

        static Remaining parse(String value) {
            String[] f = value.split(",");
            return new Remaining(Long.parseLong(f[0]), d(f[1]), d(f[2]), d(f[3]), d(f[4]));
        }
    }

    final String name;
    final Recording recording;
    final int width;
    final int height;
    final double gunCoolingRate;
    private final Map<Long, RobotTick> ticks = new HashMap<>();
    private final Map<Long, Command> commands = new HashMap<>();
    private final Map<Long, Remaining> remaining = new HashMap<>();

    private ProbeTrace(String name, Recording recording) {
        this.name = name;
        this.recording = recording;
        width = Integer.parseInt(recording.battle().get("width"));
        height = Integer.parseInt(recording.battle().get("height"));
        gunCoolingRate = Double.parseDouble(recording.battle().get("gunCoolingRate"));
        for (RobotTick t : recording.robotTicks()) {
            ticks.put(key(t.round(), t.turn(), t.robot()), t);
        }
        for (DebugEntry d : recording.debug()) {
            long k = key(d.round(), d.turn(), d.robot());
            if (d.key().equals("cmd")) {
                commands.put(k, Command.parse(d.value()));
            } else if (d.key().equals("rem")) {
                remaining.put(k, Remaining.parse(d.value()));
            }
        }
    }

    /** The golden recordings written by {@code recordProbes} (not the replays or experiments). */
    static List<ProbeTrace> loadAll() throws IOException {
        Path dir = Path.of(System.getProperty("ogi.recordings", "build/recordings"));
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".rec.gz")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "No recordings in " + dir + "; run recordProbes");
        List<ProbeTrace> traces = new java.util.ArrayList<>();
        for (Path file : files) {
            traces.add(new ProbeTrace(file.getFileName().toString(), Recording.read(file)));
        }
        return traces;
    }

    RobotTick tick(int round, int turn, int robot) {
        return ticks.get(key(round, turn, robot));
    }

    /** The commands sent in this turn, or null if the robot did not act in it. */
    Command command(int round, int turn, int robot) {
        Command c = commands.get(key(round, turn, robot));
        return c != null && c.time() == turn ? c : null;
    }

    /** The remaining values read in this turn, or null if the robot did not act in it. */
    Remaining remaining(int round, int turn, int robot) {
        Remaining r = remaining.get(key(round, turn, robot));
        return r != null && r.time() == turn ? r : null;
    }

    /** Every bullet's ticks, keyed by round, owner and bullet id, each ordered by turn. */
    Map<Long, TreeMap<Integer, BulletTick>> bulletTracks() {
        Map<Long, TreeMap<Integer, BulletTick>> tracks = new HashMap<>();
        for (BulletTick b : recording.bulletTicks()) {
            tracks.computeIfAbsent(key(b.round(), b.bulletId(), b.owner()), k -> new TreeMap<>()).put(b.turn(), b);
        }
        return tracks;
    }

    static long key(int round, int turn, int robot) {
        return ((long) round << 40) | ((long) turn << 8) | robot;
    }

    private static double d(String s) {
        return Double.parseDouble(s);
    }
}
