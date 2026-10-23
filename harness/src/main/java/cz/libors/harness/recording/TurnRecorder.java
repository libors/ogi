package cz.libors.harness.recording;

import cz.libors.harness.BattleRunner;
import cz.libors.harness.BattleSetup;
import robocode.BattleRules;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.BattleStartedEvent;
import robocode.control.events.RoundEndedEvent;
import robocode.control.events.RoundStartedEvent;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IDebugProperty;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * Battle listener that records the engine's ground truth turn by turn: every robot and every bullet at the end of
 * every turn, plus the debug properties the robots set. Probe robots report their commands that way, so a recording
 * holds the engine's inputs and its response.
 *
 * <p>Format: gzip-compressed UTF-8 text, one record per line, fields separated by tabs. Numbers are written as
 * {@link Double#toString} prints them, which parses back to the same value. Angles are in radians and in Robocode's
 * convention (0 = north, clockwise), exactly as the engine reports them.
 * <pre>
 * #ogi-recording  1
 * battle  label=…  seed=…  width=…  height=…  rounds=…  gunCoolingRate=…  inactivityTime=…
 *         hideEnemyNames=…    (one line)
 * robot   index  name  teamIndex  teamName  droid
 * r       round  turn  robot  state  x  y  bodyHeading  velocity  energy  gunHeat  gunHeading  radarHeading
 * b       round  turn  owner  bulletId  state  power  x  y  heading  victim
 * d       round  turn  robot  key  value
 * p       round  turn  robot  x  y
 * end     round  turns
 * </pre>
 *
 * <p>Turn 0 is the state at round start. A record of turn t describes the state after the engine's update of turn t;
 * the debug properties of turn t are those the robot set while acting in turn t, whose commands take effect in turn
 * t + 1. In a turn the robot does not act in (a skipped turn, the turns after the last enemy died) the snapshot repeats
 * the properties the robot set last, so a reader that counts events from them must drop repeats. Explosions are left
 * out. Debug properties of dead robots are left out: the engine does not wait for a dead
 * robot's last turn, so they would race with the snapshot.
 *
 * <p>A recording can be limited to some debug properties: then it has no {@code r} and {@code b} records and only
 * the {@code d} records with those keys, e.g. the gun's log for the gun bench. If the keys include
 * {@value #POSITIONS}, it also has a {@code p} record for every robot alive in every turn: its position only, a
 * fraction of an {@code r} record, which a melee battle of ten robots needs (the melee bench scores bullets against
 * them).
 *
 * <p>The engine swallows listener exceptions, so failures are kept and rethrown by {@link #close()}.
 */
public final class TurnRecorder extends BattleAdaptor implements AutoCloseable {

    public static final String MAGIC = "#ogi-recording";
    /** Not a debug property: in a limited recording's keys, it asks for the robots' positions ({@code p}). */
    public static final String POSITIONS = "@positions";
    public static final int VERSION = 1;

    /**
     * Debug properties that measure time, e.g. our robots' own turn times: they differ between two runs of the
     * same battle, so they are recorded but left out of the digest.
     */
    public static final Set<String> MEASUREMENTS = Set.of(BattleRunner.TURN_TIME_PROPERTY);

    private final BattleSetup setup;
    private final Set<String> debugKeys;
    private final MessageDigest digest;
    private final Writer out;
    private final StringBuilder line = new StringBuilder(256);
    private boolean inDigest;
    private Exception failure;
    private String hexDigest;

    private TurnRecorder(BattleSetup setup, Set<String> debugKeys, MessageDigest digest, Writer out) {
        this.setup = setup;
        this.debugKeys = debugKeys;
        this.digest = digest;
        this.out = out;
    }

    /** Opens a recording of {@code setup}, which must carry a seed so the recording can be replayed. */
    public static TurnRecorder open(Path file, BattleSetup setup) throws IOException {
        return open(file, setup, null);
    }

    /**
     * Opens a recording of {@code setup}, which must carry a seed so the recording can be replayed.
     *
     * @param debugKeys if not null, only the debug properties with these keys are recorded, and no robots and bullets
     */
    public static TurnRecorder open(Path file, BattleSetup setup, Set<String> debugKeys) throws IOException {
        if (setup.seed() == null) {
            throw new IllegalArgumentException("A recorded battle needs a seed: " + setup.label());
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Writer out = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file)), 1 << 16),
                StandardCharsets.UTF_8), 1 << 16);
        return new TurnRecorder(setup, debugKeys == null ? null : Set.copyOf(debugKeys), digest, out);
    }

    @Override
    public void onBattleStarted(BattleStartedEvent event) {
        guard(() -> {
            BattleRules rules = event.getBattleRules();
            start(MAGIC).field(VERSION).end();
            // Out of the digest: it names the seed, so battles with different seeds would differ by it alone.
            start("battle");
            inDigest = false;
            field("label=" + setup.label()).field("seed=" + setup.seed())
                    .field("width=" + rules.getBattlefieldWidth()).field("height=" + rules.getBattlefieldHeight())
                    .field("rounds=" + rules.getNumRounds()).field("gunCoolingRate=" + rules.getGunCoolingRate())
                    .field("inactivityTime=" + rules.getInactivityTime())
                    .field("hideEnemyNames=" + rules.getHideEnemyNames()).end();
        });
    }

    @Override
    public void onRoundStarted(RoundStartedEvent event) {
        guard(() -> {
            ITurnSnapshot snapshot = event.getStartSnapshot();
            if (snapshot.getRound() == 0) {
                for (IRobotSnapshot robot : snapshot.getRobots()) {
                    start("robot").field(robot.getRobotIndex()).field(robot.getName()).field(robot.getTeamIndex())
                            .field(robot.getTeamName()).field(robot.isDroid()).end();
                }
            }
            writeTurn(snapshot);
        });
    }

    @Override
    public void onTurnEnded(TurnEndedEvent event) {
        guard(() -> writeTurn(event.getTurnSnapshot()));
    }

    @Override
    public void onRoundEnded(RoundEndedEvent event) {
        guard(() -> start("end").field(event.getRound()).field(event.getTurns()).end());
    }

    private void writeTurn(ITurnSnapshot snapshot) throws IOException {
        int round = snapshot.getRound();
        int turn = snapshot.getTurn();
        if (debugKeys == null) {
            writeRobotsAndBullets(snapshot, round, turn);
        } else if (debugKeys.contains(POSITIONS)) {
            for (IRobotSnapshot r : snapshot.getRobots()) {
                if (r.getState() != RobotState.DEAD) {
                    start("p").field(round).field(turn).field(r.getRobotIndex()).field(r.getX()).field(r.getY()).end();
                }
            }
        }
        for (IRobotSnapshot r : snapshot.getRobots()) {
            IDebugProperty[] properties = r.getDebugProperties();
            if (properties == null || r.getState() == RobotState.DEAD) {
                continue;
            }
            for (IDebugProperty p : properties) {
                if (debugKeys != null && !debugKeys.contains(p.getKey())) {
                    continue;
                }
                start("d").field(round).field(turn).field(r.getRobotIndex()).field(p.getKey()).field(p.getValue());
                inDigest = !MEASUREMENTS.contains(p.getKey());
                end();
            }
        }
    }

    private void writeRobotsAndBullets(ITurnSnapshot snapshot, int round, int turn) throws IOException {
        for (IRobotSnapshot r : snapshot.getRobots()) {
            start("r").field(round).field(turn).field(r.getRobotIndex()).field(r.getState().name())
                    .field(r.getX()).field(r.getY()).field(r.getBodyHeading()).field(r.getVelocity())
                    .field(r.getEnergy()).field(r.getGunHeat()).field(r.getGunHeading()).field(r.getRadarHeading())
                    .end();
        }
        for (IBulletSnapshot b : snapshot.getBullets()) {
            if (b.isExplosion()) {
                continue;
            }
            start("b").field(round).field(turn).field(b.getOwnerIndex()).field(b.getBulletId())
                    .field(b.getState().name()).field(b.getPower()).field(b.getX()).field(b.getY())
                    .field(b.getHeading()).field(b.getVictimIndex()).end();
        }
    }

    private TurnRecorder start(String type) {
        line.setLength(0);
        line.append(type);
        inDigest = true;
        return this;
    }

    private TurnRecorder field(String value) {
        line.append('\t');
        String v = String.valueOf(value);
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            line.append(ch == '\t' || ch == '\n' || ch == '\r' ? ' ' : ch);
        }
        return this;
    }

    private TurnRecorder field(double value) {
        line.append('\t').append(value);
        return this;
    }

    private TurnRecorder field(long value) {
        line.append('\t').append(value);
        return this;
    }

    private TurnRecorder field(boolean value) {
        line.append('\t').append(value);
        return this;
    }

    private void end() throws IOException {
        line.append('\n');
        out.append(line);
        if (inDigest) {
            digest.update(line.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private interface Action {
        void run() throws IOException;
    }

    private void guard(Action action) {
        if (failure != null || hexDigest != null) {
            return;
        }
        try {
            action.run();
        } catch (IOException | RuntimeException e) {
            failure = e;
        }
    }

    /** SHA-256 of the uncompressed content without the {@link #MEASUREMENTS}; available after {@link #close()}. */
    public String digest() {
        if (hexDigest == null) {
            throw new IllegalStateException("Recording not closed yet");
        }
        return hexDigest;
    }

    @Override
    public void close() throws IOException {
        if (hexDigest != null) {
            return;
        }
        out.close();
        hexDigest = HexFormat.of().formatHex(digest.digest());
        if (failure instanceof IOException e) {
            throw e;
        } else if (failure != null) {
            throw new IllegalStateException("Recording failed", failure);
        }
    }
}
