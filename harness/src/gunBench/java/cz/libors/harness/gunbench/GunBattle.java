package cz.libors.harness.gunbench;

import cz.libors.harness.recording.Recording;
import cz.libors.ogi.gun.GunLog;
import cz.libors.ogi.gun.GunModel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The gun log of one recorded battle ({@link GunLog}), in the order the bot acted on it: per round, per turn, first
 * the waves that passed, then the turn's plan.
 *
 * @param subject the robot whose log it is, as the recording names it
 * @param events  {@link RoundStart}, {@link GunModel.Pass} and {@link GunLog.LoggedShot} in replay order
 */
public record GunBattle(Path file, String subject, String opponent, List<Object> events) {

    /** The start of a round: waves of the previous round that had not passed never will. */
    public record RoundStart(int round) {
    }

    /** Reads the log of the robot whose name starts with {@code subjectPrefix}; the opponent is the other robot. */
    public static GunBattle read(Path file, String subjectPrefix) throws IOException {
        Recording recording = Recording.read(file);
        Recording.RobotInfo subject = null;
        String opponent = null;
        for (Recording.RobotInfo robot : recording.robots()) {
            if (subject == null && robot.name().startsWith(subjectPrefix)) {
                subject = robot;
            } else {
                opponent = opponent == null ? robot.name() : opponent + ", " + robot.name();
            }
        }
        if (subject == null) {
            throw new IOException(file + ": no robot named " + subjectPrefix + "…");
        }
        List<Object> events = new ArrayList<>();
        List<GunModel.Pass> passes = new ArrayList<>();
        List<GunLog.LoggedShot> shots = new ArrayList<>();
        int round = -1;
        int turn = -1;
        for (Recording.DebugEntry d : recording.debug()) {
            if (d.robot() != subject.index()) {
                continue;
            }
            if (d.round() != round || d.turn() != turn) {
                flush(events, passes, shots);
                if (d.round() != round) {
                    events.add(new RoundStart(d.round()));
                }
                round = d.round();
                turn = d.turn();
            }
            try {
                switch (d.key()) {
                    case GunLog.PASS -> passes.add(GunLog.pass(d.value()));
                    case GunLog.SHOT -> shots.add(GunLog.shot(d.value()));
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                throw new IOException(file + ": round " + d.round() + ", turn " + d.turn() + ": " + e.getMessage(), e);
            }
        }
        flush(events, passes, shots);
        return new GunBattle(file, subject.name(), opponent, List.copyOf(events));
    }

    private static void flush(List<Object> events, List<GunModel.Pass> passes, List<GunLog.LoggedShot> shots) {
        events.addAll(passes);
        events.addAll(shots);
        passes.clear();
        shots.clear();
    }
}
