package cz.libors.harness;

import cz.libors.harness.ProbeTrace.Command;
import cz.libors.harness.ProbeTrace.Remaining;
import cz.libors.harness.recording.Recording;
import cz.libors.harness.recording.Recording.RobotTick;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.RobotMotion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

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
                        RobotMotion.move(sim, trace.width, trace.height, trace.gunCoolingRate);
                        if (to.state().equals("HIT_ROBOT")) {
                            RobotMotion.ram(sim);
                        }
                        if (to.state().equals("DEAD") || (from.energy() > 0 && to.energy() == 0)) {
                            continue;
                        }
                        String where = trace.name + " round " + round.round() + " turn " + (turn + 1) + " robot "
                                + robot.index();
                        compared++;
                        mismatches.compare(where, "x", to.x(), sim.x);
                        mismatches.compare(where, "y", to.y(), sim.y);
                        mismatches.compare(where, "velocity", to.velocity(), sim.velocity);
                        mismatches.compare(where, "bodyHeading", to.bodyHeading(), sim.bodyHeading);
                        mismatches.compare(where, "gunHeading", to.gunHeading(), sim.gunHeading);
                        mismatches.compare(where, "radarHeading", to.radarHeading(), sim.radarHeading);
                        mismatches.compare(where, "gunHeat", to.gunHeat(), sim.gunHeat);
                        Remaining rem = trace.remaining(round.round(), turn + 1, robot.index());
                        if (rem != null) {
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
        System.out.printf("Robot transitions %d, compared %d (remaining values %d), mismatches %s%n", transitions,
                compared, remainingCompared, mismatches.byField);
        mismatches.examples.forEach(e -> System.out.println("  " + e));
        assertTrue(compared > 100_000, "Too few compared turns: " + compared);
        assertEquals(Map.of(), mismatches.byField, "Model and engine disagree, examples:\n"
                + String.join("\n", mismatches.examples));
    }

    /** A new round starts with fresh engine commands; the overdrive flag survives. */
    private static void resetCommands(EngineRobot sim) {
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

    private static void load(EngineRobot sim, Command c) {
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
