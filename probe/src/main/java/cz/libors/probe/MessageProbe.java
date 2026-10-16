package cz.libors.probe;

import robocode.MessageEvent;
import robocode.ScannedRobotEvent;
import robocode.TeamRobot;
import robocode.util.Utils;

import java.io.IOException;
import java.util.StringJoiner;

/**
 * Probe robot for team messages (engine-model G-23): in every turn it broadcasts the turn it acts in, and it reports
 * every message it received in the turn through the debug property {@code msg}: the turn, then one
 * {@code sender:sentTurn:eventTurn} per message. It spins its radar and fires at any enemy it scans, so that rounds
 * end.
 */
public class MessageProbe extends TeamRobot {

    private StringJoiner received;

    @Override
    public void run() {
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
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        if (!isTeammate(e.getName())) {
            setTurnGunRightRadians(Utils.normalRelativeAngle(
                    getHeadingRadians() + e.getBearingRadians() - getGunHeadingRadians()));
            setFire(3);
        }
    }
}
