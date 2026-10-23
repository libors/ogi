package cz.libors.harness;

import cz.libors.harness.ProbeTrace.Command;
import cz.libors.harness.ProbeTrace.Remaining;
import cz.libors.harness.recording.Recording;
import cz.libors.harness.recording.Recording.RobotTick;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.RobotMotion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays every probe recording through {@link RobotMotion}: from the recorded state of turn t and the commands the
 * probe sent in turn t, it predicts the state of turn t + 1 and the remaining values the probe reads in turn t + 1.
 * Every compared value must match the engine bit for bit.
 *
 * <p>Each turn starts from the recorded state, so errors do not accumulate. The engine's hidden state (overdrive
 * flag) is carried by the simulation from the start of the battle. Robot collisions come from the recording
 * ({@code HIT_ROBOT} marks the robot at fault), because predicting them needs the engine's processing order.
 *
 * <p>Not compared: transitions into death, and transitions in which the robot's energy drops to 0, because the
 * engine then drops the remaining distance and body turn at a point that depends on what drained the energy.
 */
class RobotMotionReplayTest {

    @Test
    void robotMotionMatchesTheEngineInEveryRecordedTurn() throws IOException {
        Mismatches mismatches = new Mismatches();
        int transitions = 0;
        int compared = 0;
        int remainingCompared = 0;
        int wallEvents = 0;
        int signFlips = 0;
        int quarterSnaps = 0;
        int overdriveDrops = 0;
        for (ProbeTrace trace : ProbeTrace.loadAll()) {
            for (Recording.RobotInfo robot : trace.recording.robots()) {
                // One engine robot per battle: the engine keeps the overdrive flag across rounds.
                EngineRobot sim = new EngineRobot();
                for (Recording.RoundEnd round : trace.recording.roundEnds()) {
                    resetCommands(sim);
                    for (int turn = 0; turn < round.turns(); turn++) {
                        RobotTick from = trace.tick(round.round(), turn, robot.index());
                        RobotTick to = trace.tick(round.round(), turn + 1, robot.index());
                        if (from == null || to == null || from.state().equals("DEAD")) {
                            continue;
                        }
                        transitions++;
                        load(sim, from);
                        Command cmd = trace.command(round.round(), turn, robot.index());
                        // Without fresh commands the engine keeps counting down the previous ones.
                        if (cmd != null) {
                            load(sim, cmd);
                            if (cmd.fired()) {
                                RobotMotion.fire(sim, cmd.firePower());
                            }
                        }
                        OptionalDouble wallBearing = RobotMotion.move(sim, trace.width, trace.height,
                                trace.gunCoolingRate);
                        if (to.state().equals("HIT_ROBOT")) {
                            RobotMotion.ram(sim);
                        }
                        if (to.state().equals("DEAD") || (from.energy() > 0 && to.energy() == 0)) {
                            continue;
                        }
                        String where = trace.name + " round " + round.round() + " turn " + (turn + 1) + " robot "
                                + robot.index();
                        compared++;
                        // The edge cases whose gotchas this test proves must occur, or the proof is empty.
                        if (from.velocity() * to.velocity() < 0) {
                            signFlips++; // G-07: through zero
                        }
                        if (to.bodyHeading() != from.bodyHeading() && quarter(to.bodyHeading())) {
                            quarterSnaps++; // G-36: a finished turn on a cardinal heading
                        }
                        Remaining before = trace.remaining(round.round(), turn, robot.index());
                        Remaining after = trace.remaining(round.round(), turn + 1, robot.index());
                        if (before != null && after != null && after.distance() == 0
                                && Math.abs(before.distance() - to.velocity()) > 1e-9
                                && Math.abs(to.velocity()) < 1e-5 && !to.state().equals("HIT_WALL")) {
                            overdriveDrops++; // G-35: braking to zero dropped the rest of the distance
                        }
                        mismatches.compare(where, "x", to.x(), sim.x);
                        mismatches.compare(where, "y", to.y(), sim.y);
                        mismatches.compare(where, "velocity", to.velocity(), sim.velocity);
                        mismatches.compare(where, "bodyHeading", to.bodyHeading(), sim.bodyHeading);
                        mismatches.compare(where, "gunHeading", to.gunHeading(), sim.gunHeading);
                        mismatches.compare(where, "radarHeading", to.radarHeading(), sim.radarHeading);
                        mismatches.compare(where, "gunHeat", to.gunHeat(), sim.gunHeat);
                        Remaining rem = trace.remaining(round.round(), turn + 1, robot.index());
                        if (rem != null) {
                            List<String[]> walls = trace.events(round.round(), turn + 1, robot.index(), "HitWall");
                            if (walls.size() != (wallBearing.isPresent() ? 1 : 0)) {
                                mismatches.add(where, "HitWall events", walls.size() + ", model "
                                        + (wallBearing.isPresent() ? 1 : 0));
                            } else if (wallBearing.isPresent()) {
                                wallEvents++;
                                mismatches.compare(where, "HitWall bearing", Double.parseDouble(walls.get(0)[2]),
                                        wallBearing.getAsDouble());
                            }
                            remainingCompared++;
                            mismatches.compare(where, "distanceRemaining", rem.distance(), sim.distanceRemaining);
                            mismatches.compare(where, "bodyTurnRemaining", rem.bodyTurn(), sim.bodyTurnRemaining);
                            mismatches.compare(where, "gunTurnRemaining", rem.gunTurn(), sim.gunTurnRemaining);
                            mismatches.compare(where, "radarTurnRemaining", rem.radarTurn(), sim.radarTurnRemaining);
                        }
                    }
                }
            }
        }
        System.out.printf("Robot transitions %d, compared %d (remaining values %d, wall events %d), mismatches %s%n",
                transitions, compared, remainingCompared, wallEvents, mismatches.byField);
        mismatches.examples.forEach(e -> System.out.println("  " + e));
        assertTrue(compared > 100_000, "Too few compared turns: " + compared);
        System.out.printf("Edge cases compared: sign flips %d, quarter snaps %d, overdrive drops %d%n", signFlips,
                quarterSnaps, overdriveDrops);
        assertTrue(wallEvents > 100, "Too few wall hits (G-11): " + wallEvents);
        assertTrue(signFlips > 100, "Too few velocity sign flips (G-07): " + signFlips);
        assertTrue(quarterSnaps > 0, "No body turn ended on a quarter heading (G-36)");
        assertTrue(overdriveDrops > 0, "No distance dropped by braking to zero (G-35)");
        assertEquals(Map.of(), mismatches.byField, "Model and engine disagree, examples:\n"
                + String.join("\n", mismatches.examples));
    }

    /** Whether an angle is exactly 0, π/2, π or 3π/2, as the engine's snap leaves it (G-36). */
    private static boolean quarter(double angle) {
        return angle == 0 || angle == Math.PI / 2 || angle == Math.PI || angle == 3 * Math.PI / 2;
    }

    /** A new round starts with fresh engine commands; the overdrive flag survives. */
    static void resetCommands(EngineRobot sim) {
        sim.distanceRemaining = 0;
        sim.bodyTurnRemaining = 0;
        sim.gunTurnRemaining = 0;
        sim.radarTurnRemaining = 0;
        sim.maxVelocity = RobotMotion.MAX_VELOCITY;
        sim.maxTurnRate = RobotMotion.MAX_TURN_RATE;
        sim.adjustGunForBodyTurn = false;
        sim.adjustRadarForGunTurn = false;
        sim.adjustRadarForBodyTurn = false;
    }

    static void load(EngineRobot sim, RobotTick t) {
        sim.x = t.x();
        sim.y = t.y();
        sim.velocity = t.velocity();
        sim.bodyHeading = t.bodyHeading();
        sim.gunHeading = t.gunHeading();
        sim.radarHeading = t.radarHeading();
        sim.gunHeat = t.gunHeat();
        sim.energy = t.energy();
        sim.inCollision = t.state().equals("HIT_ROBOT");
    }

    static void load(EngineRobot sim, Command c) {
        sim.distanceRemaining = c.distance();
        sim.bodyTurnRemaining = c.bodyTurn();
        sim.gunTurnRemaining = c.gunTurn();
        sim.radarTurnRemaining = c.radarTurn();
        sim.maxVelocity = RobotMotion.limitVelocity(c.maxVelocity());
        sim.maxTurnRate = RobotMotion.limitTurnRate(c.maxTurnRate());
        sim.adjustGunForBodyTurn = c.adjustGunForBody();
        sim.adjustRadarForGunTurn = c.adjustRadarForGun();
        sim.adjustRadarForBodyTurn = c.adjustRadarForBody();
    }
}
