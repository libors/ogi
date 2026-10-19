package cz.libors.probe;

import robocode.Robot;
import robocode.RobotStatus;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;
import robocode.util.Utils;

import java.util.Objects;
import java.util.Random;

/**
 * Probe robot for the engine's fire assist (engine-model G-51): a plain {@link Robot}, not an {@code AdvancedRobot},
 * whose radar turns with its gun, so the two stay aligned. It turns its gun and body and moves at random. When it
 * scans a robot in this turn with a cool gun it fires from {@code onScannedRobot}; now and then it fires from
 * {@code run()} instead, outside any event handler. Debug properties, values comma-separated:
 * <ul>
 *   <li>{@code assistShot}: a shot from {@code onScannedRobot}: time, the scanned robot's absolute bearing as the
 *       engine computes the assisted heading ({@code normalAbsoluteAngle} of the status heading plus the scan's
 *       bearing) and the power.</li>
 *   <li>{@code plainShot}: a shot from {@code run()}: time and the power.</li>
 * </ul>
 *
 * <p>The random sequence is seeded from the start position, as {@link RandomProbe}'s.
 */
public class FireAssistProbe extends Robot {

    private Random random;
    /** This turn's status: a plain robot has no getter for its heading in radians. */
    private RobotStatus status;

    @Override
    public void run() {
        random = new Random(Objects.hash(getRoundNum(), getX(), getY(), getHeading()));
        while (true) {
            double r = random.nextDouble();
            if (r < 0.5) {
                turnGunRight(120 * random.nextDouble() - 60);
            } else if (r < 0.7) {
                ahead(200 * random.nextDouble() - 100);
            } else if (r < 0.9) {
                turnRight(90 * random.nextDouble() - 45);
            } else if (getGunHeat() == 0) {
                double power = power();
                setDebugProperty("plainShot", getTime() + "," + power);
                fire(power);
            } else {
                doNothing();
            }
        }
    }

    @Override
    public void onStatus(StatusEvent e) {
        status = e.getStatus();
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        // The engine assists only a shot in the handler of a scan of this turn.
        if (e.getTime() == getTime() && status != null && status.getTime() == getTime() && getGunHeat() == 0) {
            double power = power();
            double bearing = Utils.normalAbsoluteAngle(status.getHeadingRadians() + e.getBearingRadians());
            setDebugProperty("assistShot", getTime() + "," + bearing + "," + power);
            fire(power);
        }
    }

    private double power() {
        return 0.1 + 2.9 * random.nextDouble();
    }
}
