package cz.libors.probe;

import robocode.ScannedRobotEvent;
import robocode.util.Utils;

/**
 * {@link RandomProbe} with the radar of a real bot: locked on the enemy in 1v1, spinning in melee. Its scans are
 * what a bot sees, for measuring enemy fire detection.
 */
public class WatchingProbe extends RandomProbe {

    private long scanTime = -1;
    private double scanBearing;

    @Override
    protected boolean randomRadar() {
        return false;
    }

    @Override
    protected void finishCommands() {
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);
        if (getOthers() == 1 && scanTime == getTime()) {
            // Overshooting keeps the enemy inside the swept arc every turn.
            setTurnRadarRightRadians(2 * Utils.normalRelativeAngle(scanBearing - getRadarHeadingRadians()));
        } else {
            setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
        }
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        super.onScannedRobot(e);
        scanTime = e.getTime();
        scanBearing = getHeadingRadians() + e.getBearingRadians();
    }
}
