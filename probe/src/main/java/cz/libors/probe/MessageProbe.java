package cz.libors.probe;

import robocode.MessageEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;
import robocode.TeamRobot;
import robocode.util.Utils;

import java.io.IOException;
import java.util.StringJoiner;

/**
 * Probe robot for team messages (engine-model G-23): in every turn it broadcasts the turn it acts in. It spins its
 * radar and fires at any enemy it scans, so that rounds end. Debug properties, values comma-separated:
 * <ul>
 *   <li>{@code msg} every turn: the turn, then one {@code sender:sentTurn:eventTurn} per message received in it.</li>
 *   <li>{@code ev} once per message, death and scan received, in the order the handlers ran: event type, event time,
 *       the sender or the robot's name, and {@code getOthers()} in the handler (engine-model G-52).</li>
 *   <li>{@code statics} once a round: the rounds this robot started, counted in a static field (engine-model
 *       G-22).</li>
 * </ul>
 */
public class MessageProbe extends TeamRobot {

    /** Lives as long as the robot's class loader, which the engine makes for each robot and battle (G-22). */
    private static int roundsStarted;

    private StringJoiner received;

    @Override
    public void run() {
        roundsStarted++;
        setDebugProperty("statics", Integer.toString(roundsStarted));
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        received = new StringJoiner(",");
        while (true) {
            setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
            setDebugProperty("msg", getTime() + (received.length() == 0 ? "" : "," + received));
            received = new StringJoiner(",");
            try {
                broadcastMessage(new long[] {getRoundNum(), getTime()});
            } catch (IOException e) {
                setDebugProperty("msgError", e.toString());
            }
            execute();
        }
    }

    @Override
    public void onMessageReceived(MessageEvent e) {
        if (e.getMessage() instanceof long[] m && m.length == 2 && m[0] == getRoundNum()) {
            received.add(e.getSender() + ":" + m[1] + ":" + e.getTime());
        }
        event("Message", e.getTime(), e.getSender());
    }

    @Override
    public void onRobotDeath(RobotDeathEvent e) {
        event("RobotDeath", e.getTime(), e.getName());
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        event("ScannedRobot", e.getTime(), e.getName());
        if (!isTeammate(e.getName())) {
            setTurnGunRightRadians(Utils.normalRelativeAngle(
                    getHeadingRadians() + e.getBearingRadians() - getGunHeadingRadians()));
            setFire(3);
        }
    }

    private void event(String type, long time, String name) {
        setDebugProperty("ev", type + "," + time + "," + name + "," + getOthers());
    }
}
