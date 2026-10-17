package cz.libors.harness.meleebench;

import cz.libors.harness.recording.Recording;
import cz.libors.ogi.gun.MeleeGunLog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The melee gun's log of one logging robot in one recorded battle ({@link MeleeGunLog}) and every robot's position in
 * every turn: per round, the scans, deaths and shots in the order the bot acted on them. A team battle holds one per
 * robot of our team ({@link #readAll}).
 *
 * @param subjectIndex the logging robot's index in the recording
 * @param names        the robots' names by index, as the recording (and the bot's scans) name them
 * @param teammates    the indexes of the logging robot's teammates: its bullets that reach them hit no enemy
 */
public record MeleeBattle(Path file, int subjectIndex, List<String> names, Set<Integer> teammates, int width,
                          int height, List<Round> rounds) {

    /**
     * One round.
     *
     * @param positions per turn (0 to {@code turns}) the x and y of every robot by index, NaN while it is dead
     * @param events    {@link MeleeGunLog.Scan}, {@link Death} and {@link Shot} in replay order: a shot stands where
     *                  the gun aimed it
     */
    public record Round(int round, int turns, double[][] positions, List<Object> events) {

        /** Whether robot {@code robot} is alive at the end of turn {@code turn}. */
        boolean alive(int turn, int robot) {
            return !Double.isNaN(positions[turn][2 * robot]);
        }
    }

    /** A robot died ({@link MeleeGunLog#DEATH}). */
    public record Death(String name) {
    }

    /**
     * A bullet fired on the melee gun's aim: the aim as logged, the bullet as fired in turn {@code fireTurn}, and how
     * it ended ({@code end}, null if the bot never learned: the round ended first, or the bot died).
     */
    public record Shot(MeleeGunLog.Aim aim, int fireTurn, MeleeGunLog.Fire fire, MeleeGunLog.End end) {
    }

    /** Reads the log of the first robot whose name starts with {@code subjectPrefix}. */
    public static MeleeBattle read(Path file, String subjectPrefix) throws IOException {
        return readAll(file, subjectPrefix).get(0);
    }

    /** Reads the log of every robot whose name starts with {@code subjectPrefix}: in a team battle, our five. */
    public static List<MeleeBattle> readAll(Path file, String subjectPrefix) throws IOException {
        Recording recording = Recording.read(file);
        List<Integer> subjects = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Map<Integer, Integer> teamOf = new HashMap<>();
        for (Recording.RobotInfo robot : recording.robots()) {
            if (robot.name().startsWith(subjectPrefix)) {
                subjects.add(robot.index());
            }
            while (names.size() <= robot.index()) {
                names.add(null);
            }
            names.set(robot.index(), robot.name());
            teamOf.put(robot.index(), robot.teamIndex());
        }
        if (subjects.isEmpty()) {
            throw new IOException(file + ": no robot named " + subjectPrefix + "…");
        }
        List<MeleeBattle> battles = new ArrayList<>();
        for (int subject : subjects) {
            Set<Integer> teammates = new HashSet<>();
            int team = teamOf.get(subject);
            for (Map.Entry<Integer, Integer> e : teamOf.entrySet()) {
                if (team >= 0 && e.getValue() == team && e.getKey() != subject) {
                    teammates.add(e.getKey());
                }
            }
            battles.add(read(file, recording, subject, names, Set.copyOf(teammates)));
        }
        return battles;
    }

    private static MeleeBattle read(Path file, Recording recording, int subject, List<String> names,
                                    Set<Integer> teammates) throws IOException {
        if (recording.positions().isEmpty()) {
            throw new IOException(file + ": no robot positions; record with the key @positions");
        }
        int robots = names.size();
        Map<Integer, Integer> turnsOf = new HashMap<>();
        for (Recording.RoundEnd end : recording.roundEnds()) {
            turnsOf.put(end.round(), end.turns());
        }
        Map<Integer, double[][]> positions = new HashMap<>();
        for (Recording.Position p : recording.positions()) {
            double[][] round = positions.computeIfAbsent(p.round(), r -> {
                int turns = turnsOf.getOrDefault(r, 0);
                double[][] a = new double[turns + 1][];
                for (int t = 0; t <= turns; t++) {
                    a[t] = new double[2 * robots];
                    Arrays.fill(a[t], Double.NaN);
                }
                return a;
            });
            if (p.turn() < round.length) {
                round[p.turn()][2 * p.robot()] = p.x();
                round[p.turn()][2 * p.robot() + 1] = p.y();
            }
        }

        // The shots first: an aim is replayed only if a bullet was fired on it.
        Map<Long, MeleeGunLog.Fire> fires = new HashMap<>();
        Map<Long, Integer> fireTurns = new HashMap<>();
        Map<Long, MeleeGunLog.End> ends = new HashMap<>();
        for (Recording.DebugEntry d : recording.debug()) {
            if (d.robot() != subject) {
                continue;
            }
            try {
                if (d.key().equals(MeleeGunLog.FIRE)) {
                    MeleeGunLog.Fire f = MeleeGunLog.fire(d.value());
                    fires.put(key(d.round(), f.planTime()), f);
                    fireTurns.put(key(d.round(), f.planTime()), d.turn());
                } else if (d.key().equals(MeleeGunLog.END)) {
                    MeleeGunLog.End e = MeleeGunLog.end(d.value());
                    ends.put(key(d.round(), e.planTime()), e);
                }
            } catch (RuntimeException e) {
                throw new IOException(file + ": round " + d.round() + ", turn " + d.turn() + ": " + e.getMessage(), e);
            }
        }
        List<Round> rounds = new ArrayList<>();
        List<Object> events = null;
        int round = -1;
        for (Recording.DebugEntry d : recording.debug()) {
            if (d.robot() != subject) {
                continue;
            }
            if (d.round() != round) {
                round = d.round();
                events = new ArrayList<>();
                double[][] p = positions.get(round);
                if (p == null) {
                    throw new IOException(file + ": round " + round + " has a log but no positions");
                }
                rounds.add(new Round(round, p.length - 1, p, events));
            }
            try {
                switch (d.key()) {
                    case MeleeGunLog.SCAN -> events.add(MeleeGunLog.scan(d.value()));
                    case MeleeGunLog.DEATH -> events.add(new Death(d.value()));
                    case MeleeGunLog.AIM -> {
                        long k = key(round, d.turn());
                        MeleeGunLog.Fire f = fires.get(k);
                        if (f != null) {
                            events.add(new Shot(MeleeGunLog.aim(d.value()), fireTurns.get(k), f, ends.get(k)));
                        }
                    }
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                throw new IOException(file + ": round " + d.round() + ", turn " + d.turn() + ": " + e.getMessage(), e);
            }
        }
        int width = (int) Double.parseDouble(recording.battle().get("width"));
        int height = (int) Double.parseDouble(recording.battle().get("height"));
        return new MeleeBattle(file, subject, List.copyOf(names), teammates, width, height, List.copyOf(rounds));
    }

    private static long key(int round, long turn) {
        return ((long) round << 32) | (turn & 0xffffffffL);
    }
}
