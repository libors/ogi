package cz.libors.harness.positionbench;

import cz.libors.harness.recording.Recording;
import cz.libors.ogi.move.MeleeMovement;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A full recording of a team battle of {@code OgiGunLogTeam} with the melee movement's decision log
 * ({@link MeleeMovement#LOG}): every robot's state in every turn, every bullet's way, and our robots' decisions.
 *
 * @param opponent the enemy team as the recording's label names it
 * @param ours     the indexes of our robots
 */
record PositionBattle(Path file, String opponent, int width, int height, double coolingRate, Set<Integer> ours,
                      List<Round> rounds) {

    /**
     * One round.
     *
     * @param states    per turn and robot {x, y, body heading (Robocode's), velocity, energy}, null while it is dead
     * @param bullets   every bullet of the round
     * @param decisions our robots' logged decisions, by turn
     */
    record Round(int round, int turns, double[][][] states, List<Bullet> bullets, List<Decision> decisions) {

        boolean alive(int turn, int robot) {
            return turn >= 0 && turn < states.length && states[turn][robot] != null;
        }
    }

    /**
     * A bullet: fired by {@code owner} in turn {@code firstTurn} (its first record, after its first move), where it was
     * at the end of each turn of its flight ({@code xs}, {@code ys} from {@code firstTurn}), and how it ended in
     * {@code endTurn}: {@code HIT_VICTIM} ({@code victim}), {@code HIT_WALL}, {@code HIT_BULLET}, or null if still
     * flying when the round ended.
     */
    record Bullet(int owner, int firstTurn, double power, double heading, double[] xs, double[] ys, int endTurn,
                  String end, int victim) {

        /** Where it was at the end of turn {@code turn}, of its recorded flight; null outside it. */
        double[] at(int turn) {
            int i = turn - firstTurn;
            return i < 0 || i >= xs.length ? null : new double[] {xs[i], ys[i]};
        }
    }

    /**
     * One logged decision of the melee movement, as the robot made it in turn {@code turn}.
     *
     * @param candidates {x, y, kept}
     * @param waves      the waves' damage on the way to each candidate
     */
    record Decision(int robot, int turn, double x, double y, double energy, boolean gangDefence,
                    List<MeleeMovement.Robot> robots, double[][] candidates, double[] waves, double chosenX,
                    double chosenY) {
    }

    static PositionBattle read(Path file, String subjectPrefix) throws IOException {
        Recording rec = Recording.read(file);
        Set<Integer> ours = new HashSet<>();
        int robots = 0;
        for (Recording.RobotInfo r : rec.robots()) {
            if (r.name().startsWith(subjectPrefix)) {
                ours.add(r.index());
            }
            robots = Math.max(robots, r.index() + 1);
        }
        if (ours.isEmpty()) {
            throw new IOException(file + ": no robot named " + subjectPrefix + "…");
        }
        if (rec.robotTicks().isEmpty()) {
            throw new IOException(file + ": no robot records; the position bench needs full recordings");
        }
        Map<Integer, Integer> turnsOf = new HashMap<>();
        for (Recording.RoundEnd end : rec.roundEnds()) {
            turnsOf.put(end.round(), end.turns());
        }
        Map<Integer, double[][][]> states = new HashMap<>();
        for (Recording.RobotTick t : rec.robotTicks()) {
            final int n = robots;
            double[][][] round = states.computeIfAbsent(t.round(),
                    r -> new double[turnsOf.getOrDefault(r, maxTurn(rec, r)) + 1][n][]);
            if (t.turn() < round.length && !t.state().equals("DEAD")) {
                round[t.turn()][t.robot()] = new double[] {t.x(), t.y(), t.bodyHeading(), t.velocity(), t.energy()};
            }
        }
        // Bullets: their records in order, one per turn of flight.
        Map<Long, List<Recording.BulletTick>> ticks = new LinkedHashMap<>();
        for (Recording.BulletTick b : rec.bulletTicks()) {
            ticks.computeIfAbsent(((long) b.round() << 40) | ((long) b.owner() << 24) | b.bulletId(),
                    k -> new ArrayList<>()).add(b);
        }
        Map<Integer, List<Bullet>> bullets = new HashMap<>();
        for (List<Recording.BulletTick> list : ticks.values()) {
            list.sort(Comparator.comparingInt(Recording.BulletTick::turn));
            Recording.BulletTick first = list.get(0);
            List<double[]> way = new ArrayList<>();
            int endTurn = -1;
            String end = null;
            int victim = -1;
            for (Recording.BulletTick b : list) {
                way.add(new double[] {b.x(), b.y()});
                if (!b.state().equals("FIRED") && !b.state().equals("MOVING")) {
                    endTurn = b.turn();
                    end = b.state();
                    victim = b.victim();
                    break;
                }
            }
            double[] xs = new double[way.size()];
            double[] ys = new double[way.size()];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = way.get(i)[0];
                ys[i] = way.get(i)[1];
            }
            bullets.computeIfAbsent(first.round(), r -> new ArrayList<>()).add(new Bullet(first.owner(), first.turn(),
                    first.power(), first.heading(), xs, ys, endTurn, end, victim));
        }
        Map<Integer, List<Decision>> decisions = new HashMap<>();
        for (Recording.DebugEntry d : rec.debug()) {
            if (d.key().equals(MeleeMovement.LOG) && ours.contains(d.robot())) {
                try {
                    decisions.computeIfAbsent(d.round(), r -> new ArrayList<>()).add(decision(d.robot(), d.value()));
                } catch (RuntimeException e) {
                    throw new IOException(file + ": round " + d.round() + ", turn " + d.turn() + ": " + e, e);
                }
            }
        }
        List<Round> rounds = new ArrayList<>();
        for (int r : states.keySet().stream().sorted().toList()) {
            rounds.add(new Round(r, states.get(r).length - 1, states.get(r), bullets.getOrDefault(r, List.of()),
                    decisions.getOrDefault(r, List.of())));
        }
        Map<String, String> battle = rec.battle();
        String label = battle.getOrDefault("label", "");
        return new PositionBattle(file, label.startsWith("team vs ") ? label.substring(8) : label,
                (int) Double.parseDouble(battle.get("width")), (int) Double.parseDouble(battle.get("height")),
                Double.parseDouble(battle.getOrDefault("gunCoolingRate", "0.1")), Set.copyOf(ours), rounds);
    }

    private static int maxTurn(Recording rec, int round) {
        int max = 0;
        for (Recording.RobotTick t : rec.robotTicks()) {
            if (t.round() == round) {
                max = Math.max(max, t.turn());
            }
        }
        return max;
    }

    /** Parses one {@link MeleeMovement#LOG} line ({@code MeleeMovement.logLine}). */
    static Decision decision(int robot, String line) {
        String[] f = line.split(";", -1);
        List<MeleeMovement.Robot> robots = new ArrayList<>();
        if (!f[6].isEmpty()) {
            for (String r : f[6].split(" ")) {
                String[] v = r.split(",");
                robots.add(new MeleeMovement.Robot(Double.parseDouble(v[0]), Double.parseDouble(v[1]),
                        Double.parseDouble(v[2]), v[3].equals("E")));
            }
        }
        String[] cs = f[7].isEmpty() ? new String[0] : f[7].split(" ");
        double[][] candidates = new double[cs.length][];
        double[] waves = new double[cs.length];
        for (int i = 0; i < cs.length; i++) {
            String[] v = cs[i].split(",");
            candidates[i] = new double[] {Double.parseDouble(v[0]), Double.parseDouble(v[1]),
                    Integer.parseInt(v[2])};
            waves[i] = Double.parseDouble(v[3]);
        }
        String[] chosen = f[8].split(",");
        return new Decision(robot, Integer.parseInt(f[0]), Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                Double.parseDouble(f[4]), f[5].equals("1"), List.copyOf(robots), candidates, waves,
                Double.parseDouble(chosen[0]), Double.parseDouble(chosen[1]));
    }
}
