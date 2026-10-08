package cz.libors.harness.recording;

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
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
 * end     round  turns
 * </pre>
 *
 * <p>Turn 0 is the state at round start. A record of turn t describes the state after the engine's update of turn t;
 * the debug properties of turn t are those the robot set while acting in turn t, whose commands take effect in turn
 * t + 1. Explosions are left out. Debug properties of dead robots are left out: the engine does not wait for a dead
 * robot's last turn, so they would race with the snapshot.
 *
 * <p>The engine swallows listener exceptions, so failures are kept and rethrown by {@link #close()}.
 */
public final class TurnRecorder extends BattleAdaptor implements AutoCloseable {

    public static final String MAGIC = "#ogi-recording";
    public static final int VERSION = 1;

    private final BattleSetup setup;
    private final MessageDigest digest;
    private final Writer out;
    private final StringBuilder line = new StringBuilder(256);
    private Exception failure;
    private String hexDigest;

    private TurnRecorder(BattleSetup setup, MessageDigest digest, Writer out) {
        this.setup = setup;
        this.digest = digest;
        this.out = out;
    }

    /** Opens a recording of {@code setup}, which must carry a seed so the recording can be replayed. */
    public static TurnRecorder open(Path file, BattleSetup setup) throws IOException {
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
        Writer out = new BufferedWriter(new OutputStreamWriter(new DigestOutputStream(
                new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file)), 1 << 16), digest),
                StandardCharsets.UTF_8), 1 << 16);
        return new TurnRecorder(setup, digest, out);
    }

    @Override
    public void onBattleStarted(BattleStartedEvent event) {
        guard(() -> {
            BattleRules rules = event.getBattleRules();
            start(MAGIC).field(VERSION).end();
            start("battle").field("label=" + setup.label()).field("seed=" + setup.seed())
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
        for (IRobotSnapshot r : snapshot.getRobots()) {
            IDebugProperty[] properties = r.getDebugProperties();
            if (properties == null || r.getState() == RobotState.DEAD) {
                continue;
            }
            for (IDebugProperty p : properties) {
                start("d").field(round).field(turn).field(r.getRobotIndex()).field(p.getKey()).field(p.getValue())
                        .end();
            }
        }
    }

    private TurnRecorder start(String type) {
        line.setLength(0);
        line.append(type);
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

    /** SHA-256 of the uncompressed content; available after {@link #close()}. */
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
