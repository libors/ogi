package cz.libors.harness.gunbench;

import cz.libors.harness.recording.Recording;
import cz.libors.ogi.gun.GunLog;
import cz.libors.ogi.gun.GunModel;
import cz.libors.ogi.gun.MeleeGunLog;
import cz.libors.ogi.world.Names;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The gun log of one recorded battle ({@link GunLog}), in the order the bot acted on it: per round, per turn, first
 * the waves that passed, then the turn's plan.
 *
 * <p>A plan logged without the one-enemy flag ({@link GunLog.LoggedShot#aloneLogged}) gets it from the enemies' deaths
 * the subject logged, as the bot counts them ({@code getOthers()} less its teammates); for a plan logged with it,
 * {@code aloneDiffering} counts those whose flag the deaths contradict (0 when the log is whole).
 *
 * @param subject the robot whose log it is, as the recording names it
 * @param enemies the robots not on the subject's team, alive at a round's start
 * @param events  {@link RoundStart}, {@link Shielded}, {@link EnemyDied}, {@link GunModel.Pass} and
 *                {@link GunLog.LoggedShot} in replay order
 */
public record GunBattle(Path file, String subject, String opponent, int enemies, List<Object> events,
                        long aloneDiffering) {

    /** The start of a round: waves of the previous round that had not passed never will. */
    public record RoundStart(int round) {
    }

    /** An enemy bullet destroyed the bullet of the plan of turn {@code time} ({@link GunLog#SHIELD}). */
    public record Shielded(long time) {
    }

    /**
     * An enemy died, as the subject learned in this turn ({@link MeleeGunLog#DEATH}, logged before the turn's plan).
     * Logs without that key (1v1 gun logs) have none.
     */
    public record EnemyDied(String name) {
    }

    /**
     * Reads the logs of the robots whose names start with {@code subjectPrefix}. In a battle without teams that is the
     * first such robot, and the opponent is every other robot. In a team battle it is every such robot of the first
     * one's team, each a battle of its own (each robot has a gun of its own), and the opponent is the other teams'
     * names without the engine's copy number.
     */
    public static List<GunBattle> readAll(Path file, String subjectPrefix) throws IOException {
        Recording recording = Recording.read(file);
        Recording.RobotInfo first = recording.robots().stream().filter(r -> r.name().startsWith(subjectPrefix))
                .findFirst().orElseThrow(() -> new IOException(file + ": no robot named " + subjectPrefix + "…"));
        List<Recording.RobotInfo> subjects = new ArrayList<>();
        Set<String> teammates = new TreeSet<>();
        int enemies = 0;
        String opponent;
        if (first.teamIndex() < 0) {
            subjects.add(first);
            opponent = recording.robots().stream().filter(r -> r != first).map(Recording.RobotInfo::name)
                    .collect(Collectors.joining(", "));
            enemies = recording.robots().size() - 1;
        } else {
            Set<String> teams = new TreeSet<>();
            for (Recording.RobotInfo robot : recording.robots()) {
                if (robot.teamIndex() != first.teamIndex()) {
                    teams.add(Names.base(robot.teamName()));
                    enemies++;
                } else {
                    teammates.add(robot.name());
                    if (robot.name().startsWith(subjectPrefix)) {
                        subjects.add(robot);
                    }
                }
            }
            opponent = String.join(", ", teams);
        }
        List<GunBattle> battles = new ArrayList<>();
        for (Recording.RobotInfo subject : subjects) {
            long[] differing = new long[1];
            List<Object> events = events(file, recording, subject, teammates, enemies, differing);
            battles.add(new GunBattle(file, subject.name(), opponent, enemies, events, differing[0]));
        }
        return battles;
    }

    private static List<Object> events(Path file, Recording recording, Recording.RobotInfo subject,
                                       Set<String> teammates, int enemies, long[] aloneDiffering)
            throws IOException {
        int[] alive = {enemies};
        List<Object> events = new ArrayList<>();
        List<Object> shields = new ArrayList<>();
        List<Object> deaths = new ArrayList<>();
        List<GunModel.Pass> passes = new ArrayList<>();
        List<GunLog.LoggedShot> shots = new ArrayList<>();
        int round = -1;
        int turn = -1;
        for (Recording.DebugEntry d : recording.debug()) {
            if (d.robot() != subject.index()) {
                continue;
            }
            if (d.round() != round || d.turn() != turn) {
                flush(events, shields, deaths, passes, shots, alive, aloneDiffering);
                if (d.round() != round) {
                    events.add(new RoundStart(d.round()));
                    alive[0] = enemies;
                }
                round = d.round();
                turn = d.turn();
            }
            try {
                switch (d.key()) {
                    case GunLog.PASS -> passes.add(GunLog.pass(d.value()));
                    case GunLog.SHOT -> shots.add(GunLog.shot(d.value()));
                    case GunLog.SHIELD -> shields.add(new Shielded(GunLog.shieldTime(d.value())));
                    case MeleeGunLog.DEATH -> {
                        if (!teammates.contains(d.value())) {
                            deaths.add(new EnemyDied(d.value()));
                        }
                    }
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                throw new IOException(file + ": round " + d.round() + ", turn " + d.turn() + ": " + e.getMessage(), e);
            }
        }
        flush(events, shields, deaths, passes, shots, alive, aloneDiffering);
        return List.copyOf(events);
    }

    private static void flush(List<Object> events, List<Object> shields, List<Object> deaths,
                              List<GunModel.Pass> passes, List<GunLog.LoggedShot> shots, int[] alive,
                              long[] aloneDiffering) {
        // A bullet is shot down before its wave passes the target; deaths come as events, before the turn's plan.
        events.addAll(shields);
        shields.clear();
        events.addAll(deaths);
        alive[0] -= deaths.size();
        deaths.clear();
        events.addAll(passes);
        for (GunLog.LoggedShot logged : shots) {
            boolean alone = alive[0] <= 1;
            if (logged.aloneLogged()) {
                aloneDiffering[0] += logged.shot().alone() != alone ? 1 : 0;
                events.add(logged);
            } else {
                GunModel.Shot s = logged.shot();
                events.add(new GunLog.LoggedShot(new GunModel.Shot(s.target(), s.time(), s.features(), s.direction(),
                        s.reference(), s.escape(), s.search(), s.circularAim(), s.randomLow(), s.randomHigh(),
                        s.randomDraw(), s.mirrorAim(), alone), logged.guessFactor(), logged.gun(), false));
            }
        }
        passes.clear();
        shots.clear();
    }
}
