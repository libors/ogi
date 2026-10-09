package cz.libors;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.CircularAim;
import cz.libors.ogi.gun.GuessFactorGun;
import cz.libors.ogi.gun.GunLog;
import cz.libors.ogi.gun.GunModel;
import cz.libors.ogi.move.OrbitMovement;
import cz.libors.ogi.move.WaveSurfer;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.RobotMotion;
import cz.libors.ogi.team.EnemySighting;
import cz.libors.ogi.trace.Overlay;
import cz.libors.ogi.trace.Trace;
import cz.libors.ogi.trace.TurnClock;
import cz.libors.ogi.world.EnemyState;
import cz.libors.ogi.world.World;
import robocode.Bullet;
import robocode.BulletHitBulletEvent;
import robocode.BulletHitEvent;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.MessageEvent;
import robocode.RobotDeathEvent;
import robocode.Rules;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;
import robocode.TeamRobot;

import java.awt.Color;
import java.awt.Graphics2D;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Ogi, a team-first Robocode bot. One robot class serves 1v1, melee and team battles.
 *
 * <p>Radar lock or sweep, guess-factor and circular targeting chosen by virtual hits, wave surfing (orbiting the
 * target while no enemy wave is in the air) and shared scans.
 */
public class Ogi extends TeamRobot {

    private static final long MAX_TARGET_AGE = 8;
    private static final long MAX_TEAMMATE_AGE = 16;
    private static final double AIM_TOLERANCE = 0.005;
    private static final double FRIENDLY_FIRE_CLEARANCE = 30;
    private static final double DEFAULT_POWER = 1.9;
    private static final double CLOSE_RANGE = 200;
    /** The gun searches its data only this many turns before it can fire. */
    private static final int AIM_LEAD_TURNS = 4;

    private World world;
    private Field field;
    private OrbitMovement movement;
    private GuessFactorGun gun;
    private WaveSurfer surfer;
    private final Trace trace = new Trace();
    private final TurnClock clock = new TurnClock();
    /** Whether last turn's plan for this turn is a shot we can afford. */
    private boolean plannedShot;

    @Override
    public void run() {
        world = new World();
        field = new Field(getBattleFieldWidth(), getBattleFieldHeight());
        movement = new OrbitMovement(field, new Random(roundSeed()));
        gun = new GuessFactorGun(field);
        surfer = new WaveSurfer(field, getGunCoolingRate());
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);
        setColors(new Color(40, 44, 52), new Color(229, 192, 123), new Color(97, 175, 239));
        while (true) {
            act();
            clock.ended(System.nanoTime());
            execute();
        }
    }

    /** The first event of every turn: the turn's work starts, and the last turn's time is reported (P-04). */
    @Override
    public void onStatus(StatusEvent e) {
        clock.started(System.nanoTime());
        if (clock.lastMicros() >= 0) {
            setDebugProperty(TurnClock.PROPERTY, Long.toString(clock.lastMicros()));
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
        surfer.ourState(now, getX(), getY(), Angles.fromRobocode(getHeadingRadians()), getVelocity());
        surfer.update(now);
        Optional<EnemyState> target = world.closestEnemy(getX(), getY(), now, MAX_TARGET_AGE);
        steerRadar(target.orElse(null), now);
        if (target.isEmpty()) {
            gun.update(now, getX(), getY(), null);
            logPassedWaves();
            return;
        }
        EnemyState t = target.get();
        double[] next = move(t, now);
        boolean fired = fire(t, now);
        gun.update(now, getX(), getY(), fired ? t.name() : null);
        logPassedWaves();
        aim(t, now, next[0], next[1]);
        gun.planView(t.name(), now).ifPresent(v -> setDebugProperty("gun", Trace.gunLine(v, fired)));
        if (logsGun()) {
            gun.plan(t.name(), now).ifPresent(p -> setDebugProperty(GunLog.SHOT, GunLog.line(p.shot(),
                    p.decision())));
        }
    }

    /**
     * Whether the gun's inputs and decisions go to debug properties for the offline gun bench ({@link GunLog}). Only
     * {@link OgiGunLog} logs: formatting them costs about a tenth of a turn's time while the JVM is cold, and nothing
     * reads them in a rumble.
     */
    protected boolean logsGun() {
        return false;
    }

    /** The gun waves that passed in this turn, logged before the turn's plan. */
    private void logPassedWaves() {
        if (!logsGun()) {
            return;
        }
        for (GunModel.Pass pass : gun.passed()) {
            setDebugProperty(GunLog.PASS, GunLog.line(pass));
        }
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

    /** Fires last turn's plan if the gun is cool and reached its aim; returns whether it fired. */
    private boolean fire(EnemyState target, long now) {
        Optional<GuessFactorGun.Plan> plan = gun.planFor(target.name(), now);
        if (plan.isEmpty() || !plannedShot || getGunHeat() > 0 || getEnergy() <= plan.get().power()) {
            return false;
        }
        double gunHeading = Angles.fromRobocode(getGunHeadingRadians());
        if (Math.abs(Angles.normalizeRelative(plan.get().aim() - gunHeading)) >= AIM_TOLERANCE
                || teammateInLineOfFire(target.distanceTo(getX(), getY()), now)) {
            return false;
        }
        fired(setFireBullet(plan.get().power()), plan.get());
        return true;
    }

    /** A hook for {@link OgiGunLog}: this bullet was just fired from this plan. */
    protected void fired(Bullet bullet, GuessFactorGun.Plan plan) {
    }

    /**
     * Plans the shot of the next turn from where we will be then; the gun fires along the heading it reaches by the
     * end of the next turn (engine-model G-01).
     */
    private void aim(EnemyState target, long now, double nextX, double nextY) {
        double distance = target.distanceTo(nextX, nextY);
        double power = bulletPower(target, distance);
        plannedShot = power > 0;
        double planned = power > 0 ? power : DEFAULT_POWER;
        double circular = CircularAim.aim(nextX, nextY, target, Rules.getBulletSpeed(planned), now + 1, field);
        boolean soon = getGunHeat() <= AIM_LEAD_TURNS * getGunCoolingRate();
        double aim = gun.aim(target.name(), now, nextX, nextY, planned, soon, circular);
        if (!Double.isNaN(aim)) {
            setTurnGunRightRadians(Angles.robocodeTurn(Angles.fromRobocode(getGunHeadingRadians()), aim));
        }
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

    /**
     * Sets this turn's movement and returns where it takes us by the end of the next turn: surfing the nearest enemy
     * wave, or orbiting the target while none is in the air.
     */
    private double[] move(EnemyState target, long now) {
        Optional<double[]> surf = surfer.command(ourEngineState(), now);
        if (surf.isPresent()) {
            setAhead(surf.get()[0]);
            setTurnRightRadians(surf.get()[1]);
            return predictNext(surf.get()[0], surf.get()[1]);
        }
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
        return predictNext(distance, -turn);
    }

    /**
     * Our position at the end of the next turn under the commands just set, by the engine's own rules; exact unless we
     * collide with a robot.
     */
    private double[] predictNext(double distance, double rightTurn) {
        EngineRobot me = ourEngineState();
        me.distanceRemaining = distance;
        me.bodyTurnRemaining = rightTurn;
        RobotMotion.move(me, (int) field.width(), (int) field.height(), getGunCoolingRate());
        return new double[] {me.x, me.y};
    }

    /** Our state at the end of this turn in the engine's convention, without commands. */
    private EngineRobot ourEngineState() {
        EngineRobot me = new EngineRobot();
        me.x = getX();
        me.y = getY();
        me.velocity = getVelocity();
        me.bodyHeading = getHeadingRadians();
        me.gunHeading = getGunHeadingRadians();
        me.radarHeading = getRadarHeadingRadians();
        me.gunHeat = getGunHeat();
        me.energy = getEnergy();
        return me;
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        double bearing = Angles.fromRobocode(getHeadingRadians() + e.getBearingRadians());
        double x = getX() + e.getDistance() * Math.cos(bearing);
        double y = getY() + e.getDistance() * Math.sin(bearing);
        if (isTeammate(e.getName())) {
            world.observeTeammate(e.getName(), x, y, e.getTime());
            surfer.teammateSeen(e.getName(), e.getTime(), x, y);
            return;
        }
        EnemySighting sighting = new EnemySighting(e.getName(), x, y, Angles.fromRobocode(e.getHeadingRadians()),
                e.getVelocity(), e.getEnergy(), e.getTime(), getRoundNum());
        world.observeEnemy(sighting);
        gun.scanned(e.getName(), e.getTime(), x, y, sighting.heading(), e.getVelocity());
        surfer.scanned(e.getName(), e.getTime(), x, y, e.getVelocity(), e.getEnergy());
        if (getTeammates() != null) {
            try {
                broadcastMessage(sighting);
            } catch (IOException ex) {
                out.println("Broadcast failed: " + ex.getMessage());
            }
        }
    }

    @Override
    public void onBulletHit(BulletHitEvent e) {
        if (!isTeammate(e.getName())) {
            surfer.ourBulletHit(e.getName(), e.getBullet().getPower());
        }
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        if (!isTeammate(e.getName())) {
            surfer.hitByBullet(e.getName(), e.getTime(), e.getBullet().getX(), e.getBullet().getY(),
                    Angles.fromRobocode(e.getHeadingRadians()), e.getPower());
        }
    }

    @Override
    public void onBulletHitBullet(BulletHitBulletEvent e) {
        if (!isTeammate(e.getHitBullet().getName())) {
            surfer.bulletHitBullet(e.getHitBullet().getName(), e.getTime(), e.getHitBullet().getX(),
                    e.getHitBullet().getY(), Angles.fromRobocode(e.getHitBullet().getHeadingRadians()),
                    e.getHitBullet().getPower());
        }
    }

    @Override
    public void onHitRobot(HitRobotEvent e) {
        if (!isTeammate(e.getName())) {
            surfer.rammed(e.getName());
        }
    }

    /** Called only while painting is enabled for this robot in the GUI. */
    @Override
    public void onPaint(Graphics2D g) {
        if (gun == null) {
            return;
        }
        trace.start(getTime(), getBattleFieldHeight());
        gun.describe(getTime(), trace);
        surfer.describe(getTime(), trace);
        Overlay.paint(g, trace);
    }

    @Override
    public void onMessageReceived(MessageEvent e) {
        if (e.getMessage() instanceof EnemySighting sighting && sighting.round() == getRoundNum()) {
            world.observeEnemy(sighting);
            gun.scanned(sighting.name(), sighting.time(), sighting.x(), sighting.y(), sighting.heading(),
                    sighting.velocity());
        }
    }

    @Override
    public void onRobotDeath(RobotDeathEvent e) {
        world.robotDied(e.getName());
        gun.robotDied(e.getName());
        surfer.robotDied(e.getName());
    }
}
