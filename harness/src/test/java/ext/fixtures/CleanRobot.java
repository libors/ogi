package ext.fixtures;

import robocode.AdvancedRobot;
import robocode.ScannedRobotEvent;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Uses what ordinary robots use: the robot API, collections, streams, lambdas, records, string concatenation. */
public class CleanRobot extends AdvancedRobot {

    private record Sighting(String name, double distance) {
    }

    private final List<Sighting> sightings = new ArrayList<>();

    @Override
    public void run() {
        while (true) {
            setTurnRadarRight(Double.POSITIVE_INFINITY);
            execute();
        }
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        sightings.add(new Sighting(e.getName(), e.getDistance()));
        sightings.stream().min(Comparator.comparingDouble(Sighting::distance))
                .ifPresent(s -> out.println("closest " + s.name() + " at " + Math.round(s.distance())));
    }

    @Override
    public void onPaint(Graphics2D g) {
        g.setColor(Color.GREEN);
        g.draw(new Ellipse2D.Double(getX() - 20, getY() - 20, 40, 40));
    }
}
