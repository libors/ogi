package cz.libors.harness;

import cz.libors.harness.ProbeTrace.Command;
import cz.libors.harness.ProbeTrace.Remaining;
import cz.libors.harness.recording.Recording;
import cz.libors.harness.recording.Recording.BulletTick;
import cz.libors.harness.recording.Recording.RobotTick;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.Inactivity;
import cz.libors.ogi.physics.RobotMotion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays the probe recordings turn by turn through the full single-robot model, energy included: firing, bullet
 * hits and hit bonuses, the robot's move with wall damage, rams in both roles and the inactivity penalty, with
 * {@link Inactivity} deciding the penalty turns for the whole battle.
 *
 * <p>The engine updates bullets and robots in random order, and floating-point sums depend on the order. So for each
 * robot and turn the test tries every order of the bullet-phase changes and every place of the robot's own move among
 * the rams it suffers, and requires one of them to match the engine bit for bit in energy, position, velocity,
 * headings, gun heat and remaining values. Unlike {@code RobotMotionReplayTest}, this also covers turns in which the
 * energy drops to 0. Who rammed whom comes from the probes' {@code HitRobot} events.
 */
class EnergyReplayTest {

    /** A change of a robot's energy in the bullet phase: hit by a bullet, or its own bullet hit someone. */
    private record BulletChange(boolean hit, double power) {

        void apply(EngineRobot r) {
            if (hit) {
                RobotMotion.hitByBullet(r, power);
            } else {
                RobotMotion.bulletHit(r, power);
            }
        }
    }

    @Test
    void energyMatchesTheEngineInSomeProcessingOrder() throws IOException {
        Mismatches mismatches = new Mismatches();
        Map<String, Integer> stats = new TreeMap<>();
        for (ProbeTrace trace : ProbeTrace.loadAll()) {
            replay(trace, mismatches, stats);
        }
        System.out.printf("Energy replay %s, mismatches %s%n", stats, mismatches.byField);
        mismatches.examples.forEach(e -> System.out.println("  " + e));
        assertTrue(stats.getOrDefault("compared", 0) > 100_000, "Too few compared turns: " + stats);
        assertTrue(stats.getOrDefault("compared, zapped", 0) > 1_000, "Too few inactivity turns: " + stats);
        assertEquals(Map.of(), mismatches.byField, "Model and engine disagree, examples:\n"
                + String.join("\n", mismatches.examples));
    }

    private static void replay(ProbeTrace trace, Mismatches mismatches, Map<String, Integer> stats) {
        long inactivityTime = Long.parseLong(trace.recording.battle().get("inactivityTime"));
        List<Recording.RobotInfo> robots = trace.recording.robots();
        Map<Long, List<BulletChange>> changes = new HashMap<>();
        for (BulletTick b : trace.robotHits()) {
            changes.computeIfAbsent(ProbeTrace.key(b.round(), b.turn(), b.victim()), k -> new ArrayList<>())
                    .add(new BulletChange(true, b.power()));
            changes.computeIfAbsent(ProbeTrace.key(b.round(), b.turn(), b.owner()), k -> new ArrayList<>())
                    .add(new BulletChange(false, b.power()));
        }

        // One engine robot per robot and battle, and one inactivity state per battle: both carry hidden state.
        EngineRobot[] sims = new EngineRobot[robots.size()];
        for (int i = 0; i < sims.length; i++) {
            sims[i] = new EngineRobot();
        }
        Inactivity inactivity = new Inactivity();
        for (Recording.RoundEnd round : trace.recording.roundEnds()) {
            inactivity.startRound();
            for (EngineRobot sim : sims) {
                RobotMotionReplayTest.resetCommands(sim);
            }
            for (int turn = 1; turn <= round.turns(); turn++) {
                int r = round.round();
                int t = turn;
                // Commands load in robot order: shots cost energy before anything else happens.
                double[] firePower = new double[sims.length];
                for (Recording.RobotInfo robot : robots) {
                    RobotTick from = trace.tick(r, t - 1, robot.index());
                    Command cmd = trace.command(r, t - 1, robot.index());
                    if (from != null && !from.state().equals("DEAD") && cmd != null && cmd.fired()) {
                        EngineRobot shooter = new EngineRobot();
                        RobotMotionReplayTest.load(shooter, from);
                        firePower[robot.index()] = RobotMotion.fire(shooter, cmd.firePower());
                        inactivity.lose(firePower[robot.index()]);
                    }
                }
                // Bullet phase: damage and the deaths it causes count; regained energy does not.
                for (Recording.RobotInfo robot : robots) {
                    boolean hit = false;
                    for (BulletChange c : changes.getOrDefault(ProbeTrace.key(r, t, robot.index()), List.of())) {
                        if (c.hit()) {
                            inactivity.lose(BulletMotion.damage(c.power()));
                            hit = true;
                        }
                    }
                    if (hit && diedIn(trace, r, t, robot.index())) {
                        inactivity.death();
                    }
                }
                boolean zap = inactivity.zapping(inactivityTime);
                for (Recording.RobotInfo robot : robots) {
                    replayRobot(trace, r, t, robot.index(), sims, firePower[robot.index()],
                            changes.getOrDefault(ProbeTrace.key(r, t, robot.index()), List.of()), zap, mismatches,
                            stats);
                }
                // Move phase: rams cost both robots, deaths not caused by bullets happen here.
                for (Recording.RobotInfo robot : robots) {
                    for (String[] e : trace.events(r, t, robot.index(), "HitRobot")) {
                        if (Boolean.parseBoolean(e[5])) {
                            inactivity.lose(RobotMotion.ROBOT_HIT_DAMAGE);
                            inactivity.lose(RobotMotion.ROBOT_HIT_DAMAGE);
                        }
                    }
                    boolean hit = changes.getOrDefault(ProbeTrace.key(r, t, robot.index()), List.of()).stream()
                            .anyMatch(BulletChange::hit);
                    if (!hit && diedIn(trace, r, t, robot.index())) {
                        inactivity.death();
                    }
                }
                inactivity.endTurn();
            }
        }
    }

    private static void replayRobot(ProbeTrace trace, int round, int turn, int robot, EngineRobot[] sims,
                                    double firePower, List<BulletChange> bulletChanges, boolean zap,
                                    Mismatches mismatches, Map<String, Integer> stats) {
        RobotTick from = trace.tick(round, turn - 1, robot);
        RobotTick to = trace.tick(round, turn, robot);
        if (from == null || to == null || from.state().equals("DEAD") || to.state().equals("DEAD")) {
            return;
        }
        EngineRobot base = sims[robot];
        RobotMotionReplayTest.load(base, from);
        Command cmd = trace.command(round, turn - 1, robot);
        if (cmd != null) {
            RobotMotionReplayTest.load(base, cmd);
        }
        if (firePower > 0) {
            RobotMotion.fire(base, cmd.firePower());
        }
        int atFault = 0;
        int rammedBy = 0;
        for (String[] e : trace.events(round, turn, robot, "HitRobot")) {
            if (Boolean.parseBoolean(e[5])) {
                atFault++;
            } else {
                rammedBy++;
            }
        }
        String where = trace.name + " round " + round + " turn " + turn + " robot " + robot;
        if ((atFault > 0) != to.state().equals("HIT_ROBOT")) {
            mismatches.add(where, "ram events", "state " + to.state() + ", rams at fault " + atFault);
            return;
        }

        Remaining rem = trace.remaining(round, turn, robot);
        List<EngineRobot> matches = new ArrayList<>();
        EngineRobot first = null;
        for (List<BulletChange> order : orders(bulletChanges)) {
            for (int ownMove = 0; ownMove <= rammedBy; ownMove++) {
                EngineRobot c = base.copy();
                order.forEach(change -> change.apply(c));
                boolean survives = true;
                for (int i = 0; i < rammedBy; i++) {
                    if (i == ownMove) {
                        survives = moveOwn(c, trace, atFault, zap);
                    }
                    RobotMotion.rammed(c);
                }
                if (ownMove == rammedBy) {
                    survives = moveOwn(c, trace, atFault, zap);
                }
                if (first == null) {
                    first = c;
                }
                // The recorded robot is alive, so a penalty that kills it cannot be the engine's order.
                if (survives && sameState(c, to, rem)) {
                    matches.add(c);
                }
            }
        }
        count(stats, "compared");
        if (zap) {
            count(stats, "compared, zapped");
        }
        if (from.energy() > 0 && to.energy() == 0) {
            count(stats, "compared, energy dropped to 0");
        }
        if (matches.isEmpty()) {
            mismatches.add(where, field(first, to, rem), "engine energy " + to.energy() + ", model " + first.energy
                    + "; fire " + firePower + ", bullets " + bulletChanges + ", rams at fault " + atFault
                    + ", rammed " + rammedBy + ", zap " + zap);
            sims[robot] = first;
            return;
        }
        if (matches.stream().map(m -> m.energy).distinct().count() > 1) {
            count(stats, "compared, several orders match");
        }
        sims[robot] = matches.get(0);
    }

    /**
     * The robot's own move: turning and moving with wall damage, then the rams it causes, then the penalty.
     *
     * @return whether the robot survives it
     */
    private static boolean moveOwn(EngineRobot c, ProbeTrace trace, int atFault, boolean zap) {
        RobotMotion.move(c, trace.width, trace.height, trace.gunCoolingRate);
        for (int i = 0; i < atFault; i++) {
            RobotMotion.ram(c);
        }
        return !zap || RobotMotion.zap(c, Inactivity.ZAP);
    }

    /** Distinct orders of the bullet-phase changes. */
    private static Set<List<BulletChange>> orders(List<BulletChange> changes) {
        Set<List<BulletChange>> orders = new LinkedHashSet<>();
        permute(new ArrayList<>(changes), 0, orders);
        return orders;
    }

    private static void permute(List<BulletChange> list, int k, Set<List<BulletChange>> out) {
        if (k >= list.size() - 1) {
            out.add(List.copyOf(list));
            return;
        }
        for (int i = k; i < list.size(); i++) {
            Collections.swap(list, k, i);
            permute(list, k + 1, out);
            Collections.swap(list, k, i);
        }
    }

    private static boolean diedIn(ProbeTrace trace, int round, int turn, int robot) {
        RobotTick before = trace.tick(round, turn - 1, robot);
        RobotTick now = trace.tick(round, turn, robot);
        return before != null && now != null && !before.state().equals("DEAD") && now.state().equals("DEAD");
    }

    private static boolean sameState(EngineRobot c, RobotTick t, Remaining rem) {
        return field(c, t, rem) == null;
    }

    /** The first field in which the model differs from the engine, or null. */
    private static String field(EngineRobot c, RobotTick t, Remaining rem) {
        if (differs(t.energy(), c.energy)) {
            return "energy";
        } else if (differs(t.x(), c.x) || differs(t.y(), c.y)) {
            return "position";
        } else if (differs(t.velocity(), c.velocity)) {
            return "velocity";
        } else if (differs(t.bodyHeading(), c.bodyHeading) || differs(t.gunHeading(), c.gunHeading)
                || differs(t.radarHeading(), c.radarHeading)) {
            return "heading";
        } else if (differs(t.gunHeat(), c.gunHeat)) {
            return "gunHeat";
        } else if (rem != null && (differs(rem.distance(), c.distanceRemaining)
                || differs(rem.bodyTurn(), c.bodyTurnRemaining) || differs(rem.gunTurn(), c.gunTurnRemaining)
                || differs(rem.radarTurn(), c.radarTurnRemaining))) {
            return "remaining";
        }
        return null;
    }

    private static boolean differs(double engine, double model) {
        return Double.doubleToLongBits(engine) != Double.doubleToLongBits(model);
    }

    private static void count(Map<String, Integer> stats, String key) {
        stats.merge(key, 1, Integer::sum);
    }
}
