package cz.libors;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.CircularAim;
import cz.libors.ogi.move.OrbitMovement;
import cz.libors.ogi.team.EnemySighting;
import cz.libors.ogi.world.EnemyState;
import cz.libors.ogi.world.World;
import robocode.MessageEvent;
import robocode.RobotDeathEvent;
import robocode.Rules;
import robocode.ScannedRobotEvent;
import robocode.TeamRobot;

import java.awt.Color;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Ogi, a team-first Robocode bot. One robot class serves 1v1, melee and team battles.
 *
 * <p>This is the walking skeleton: radar lock or sweep, circular targeting, orbiting movement and shared scans.
 */
public class Ogi extends TeamRobot {

    private static final long MAX_TARGET_AGE = 8;
    private static final long MAX_TEAMMATE_AGE = 16;
    private static final double AIM_TOLERANCE = 0.005;
    private static final double FRIENDLY_FIRE_CLEARANCE = 30;
    private static final double DEFAULT_POWER = 1.9;
    private static final double CLOSE_RANGE = 200;

    private World world;
    private Field field;
    private OrbitMovement movement;

    @Override
    public void run() {
        world = new World();
        field = new Field(getBattleFieldWidth(), getBattleFieldHeight());
        movement = new OrbitMovement(field, new Random(roundSeed()));
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);
        setColors(new Color(40, 44, 52), new Color(229, 192, 123), new Color(97, 175, 239));
        while (true) {
            act();
            execute();
        }
    }

    /**
     * Seed of the bot's own random generator, from the round and the start position. The engine draws the start
     * position from its seeded generator, so a seeded harness battle replays; the bot never draws from
     * {@code Utils.getRandom()} or {@code Math.random()} (engine-model G-26, G-33, G-34).
     */
    private long roundSeed() {
        return Objects.hash(getRoundNum(), getX(), getY(), getHeadingRadians());
    }

    private void act() {
        long now = getTime();
        Optional<EnemyState> target = world.closestEnemy(getX(), getY(), now, MAX_TARGET_AGE);
        steerRadar(target.orElse(null), now);
        target.ifPresent(t -> {
            fireAndAim(t, now);
            move(t, now);
        });
    }

    private void steerRadar(EnemyState target, long now) {
        if (getOthers() == 1 && target != null && now - target.time() <= 1) {
            double bearing = Angles.bearing(getX(), getY(), target.x(), target.y());
            double turn = Angles.robocodeTurn(Angles.fromRobocode(getRadarHeadingRadians()), bearing);
            // Overshooting keeps the target inside the swept arc every tick.
            setTurnRadarRightRadians(2 * turn);
        } else {
            setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
        }
    }

    private void fireAndAim(EnemyState target, long now) {
        double distance = target.distanceTo(getX(), getY());
        double power = bulletPower(target, distance);
        // The gun fires before it turns, so this fires along the aim set in the previous tick.
        if (power > 0 && getGunHeat() == 0 && Math.abs(getGunTurnRemainingRadians()) < AIM_TOLERANCE
                && !teammateInLineOfFire(distance, now)) {
            setFire(power);
        }
        double bulletSpeed = Rules.getBulletSpeed(power > 0 ? power : DEFAULT_POWER);
        double aim = CircularAim.aim(getX(), getY(), target, bulletSpeed, now + 1, field);
        setTurnGunRightRadians(Angles.robocodeTurn(Angles.fromRobocode(getGunHeadingRadians()), aim));
    }

    private double bulletPower(EnemyState target, double distance) {
        double power = distance < CLOSE_RANGE ? Rules.MAX_BULLET_POWER : DEFAULT_POWER;
        power = Math.min(power, target.energy() / 4 + Rules.MIN_BULLET_POWER);
        power = Math.min(power, getEnergy() - Rules.MIN_BULLET_POWER);
        return power >= Rules.MIN_BULLET_POWER ? power : 0;
    }

    private boolean teammateInLineOfFire(double targetDistance, long now) {
        double gunHeading = Angles.fromRobocode(getGunHeadingRadians());
        for (World.Teammate t : world.recentTeammates(now, MAX_TEAMMATE_AGE)) {
            double distance = Math.hypot(t.x() - getX(), t.y() - getY());
            double offset = Math.abs(Angles.normalizeRelative(
                    Angles.bearing(getX(), getY(), t.x(), t.y()) - gunHeading));
            if (distance < targetDistance && offset < Math.atan2(FRIENDLY_FIRE_CLEARANCE, distance)) {
                return true;
            }
        }
        return false;
    }

    private void move(EnemyState target, long now) {
        double heading = movement.desiredHeading(getX(), getY(), target.x(), target.y(), now,
                world.recentTeammates(now, MAX_TEAMMATE_AGE));
        double turn = Angles.normalizeRelative(heading - Angles.fromRobocode(getHeadingRadians()));
        double distance = 100;
        if (Math.abs(turn) > Math.PI / 2) {
            // Drive backwards instead of turning around.
            turn = Angles.normalizeRelative(turn + Math.PI);
            distance = -distance;
        }
        setTurnRightRadians(-turn);
        setAhead(distance);
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        double bearing = Angles.fromRobocode(getHeadingRadians() + e.getBearingRadians());
        double x = getX() + e.getDistance() * Math.cos(bearing);
        double y = getY() + e.getDistance() * Math.sin(bearing);
        if (isTeammate(e.getName())) {
            world.observeTeammate(e.getName(), x, y, e.getTime());
            return;
        }
        EnemySighting sighting = new EnemySighting(e.getName(), x, y, Angles.fromRobocode(e.getHeadingRadians()),
                e.getVelocity(), e.getEnergy(), e.getTime(), getRoundNum());
        world.observeEnemy(sighting);
        if (getTeammates() != null) {
            try {
                broadcastMessage(sighting);
            } catch (IOException ex) {
                out.println("Broadcast failed: " + ex.getMessage());
            }
        }
    }

    @Override
    public void onMessageReceived(MessageEvent e) {
        if (e.getMessage() instanceof EnemySighting sighting && sighting.round() == getRoundNum()) {
            world.observeEnemy(sighting);
        }
    }

    @Override
    public void onRobotDeath(RobotDeathEvent e) {
        world.robotDied(e.getName());
    }
}
