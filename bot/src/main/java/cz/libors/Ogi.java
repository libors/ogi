package cz.libors;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.CircularAim;
import cz.libors.ogi.gun.GuessFactorGun;
import cz.libors.ogi.gun.GunLog;
import cz.libors.ogi.gun.GunModel;
import cz.libors.ogi.gun.HitRecord;
import cz.libors.ogi.gun.MirrorAim;
import cz.libors.ogi.gun.RacePower;
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
import robocode.BulletMissedEvent;
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
import java.util.HashMap;
import java.util.Map;
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
    /**
     * 23/12 and one unit in the last place more: 10 times its bullet speed lies just past a half (142.5), so a surfer
     * that matches a bullet that hit it to its wave by the speed rounded to tenths, with the speed taken from our
     * energy drop, often rounds the two differently and does not learn the hit.
     */
    private static final double DEFAULT_POWER = 1.916666666666667;
    private static final double CLOSE_RANGE = 200;
    /**
     * Against an enemy that our bullets hit at least this often over the battle, after this many of them ended, we
     * fire at full power beyond close range too: we read its movement, so the slower bullet still hits, and the
     * sooner it dies, the fewer turns it has to hit us. Lower thresholds lose: against an enemy we hit a third of the
     * time the slower bullet misses so much more often that the rounds do not end sooner.
     */
    private static final double READ_HIT_RATE = 0.55;
    private static final int READ_MIN_BULLETS = 20;
    /**
     * In a duel, against an enemy that our bullets hit less often than this over the battle, after this many of them
     * ended, the surfer keeps the nearer distance for the round: against the enemies we hit least a nearer fight
     * raises our hit rate more than theirs; against the rest it changes little or costs.
     */
    private static final double NEAR_HIT_RATE = 0.18;
    private static final int NEAR_MIN_BULLETS = 30;
    private static final double NEAR_DISTANCE = 275;
    /**
     * Beyond close range, in a duel, against an enemy that our bullets hit less often than this over the battle,
     * after {@link #NEAR_MIN_BULLETS} of them ended: more, faster bullets.
     */
    private static final double RARE_HIT_RATE = 0.25;
    private static final double RARE_HIT_POWER = 1.45;
    /**
     * Below this energy, in a duel, our power is the energy race's ({@link RacePower}): the round goes to whoever runs
     * out last, and a weak bullet costs us almost nothing while the enemy's misses run it down.
     */
    private static final double RACE_ENERGY = 20;
    /** The prior of our hit rate in the race: this many hits in {@link #RACE_PRIOR_BULLETS} bullets. */
    private static final double RACE_PRIOR_HITS = 1.5;
    private static final double RACE_PRIOR_BULLETS = 10;
    /**
     * The race is on too while the enemy is below {@link #RACE_ENERGY} and we are below this: a close race, which the
     * strong bots often start with both of us in this band.
     */
    private static final double RACE_CLOSE_ENERGY = 40;
    /** Our bullets at each enemy over the battle (statics last for the battle, as the gun's data do). */
    private static final Map<String, HitRecord> HIT_RECORDS = new HashMap<>();
    /** The gun searches its data only this many turns before it can fire. */
    private static final int AIM_LEAD_TURNS = 4;
    /** How far past a disabled enemy we drive when ramming it, so that we do not stop short. */
    private static final double RAM_OVERSHOOT = 50;
    /**
     * The turret aims up to this many pixels (at the target's distance) to either side of the gun's aim, at random.
     * A bullet aimed exactly at a robot's centre is predictable to a fraction of a pixel, and bullet shielders shoot
     * such bullets down; a few pixels off the centre still hit as often.
     */
    private static final double AIM_SPREAD = 4;
    /**
     * Shadow shots: in the turn before a shot of the anti-surfer gun, its other peaks with at least this share of its
     * best one's weight (choosing among them at random cost no hits) compete by what their bullet's shadows would save
     * us on the enemy waves in the air; at most this many, this far apart in guess factors (about a robot's width).
     */
    private static final double SHADOW_MIN_SHARE = 0.7;
    private static final int SHADOW_CANDIDATES = 3;
    private static final double SHADOW_SPACING = 0.15;
    /**
     * How many turns of the surfer's planned path the mirror gun reads: about the flight of our bullet across 700 px.
     * Against a mirror a few turns late the target's position at the bullet's arrival is the map of ours then.
     */
    private static final int MIRROR_PLANNED_TURNS = 50;
    /** How much further than the target is now the mirror gun's planned path reaches: the target may move away. */
    private static final double MIRROR_REACH = 100;

    private World world;
    private Field field;
    private OrbitMovement movement;
    private GuessFactorGun gun;
    private MirrorAim mirror;
    private WaveSurfer surfer;
    private Random aimSpread;
    /**
     * Where in the target's escape range the random gun aims: drawn once per bullet and held until the bullet fires,
     * so that the turret reaches it in the turns before the gun is cool. A draw in every turn would make the turret
     * chase it, and the bullets would fire where it happened to fall within the turret's reach.
     */
    private Random randomAim;
    private double randomDraw;
    private final Trace trace = new Trace();
    private final TurnClock clock = new TurnClock();
    /** Whether last turn's plan for this turn is a shot we can afford. */
    private boolean plannedShot;
    /** Whether this round's preferred distance is chosen: once, in the round's first turn with a target. */
    private boolean distanceChosen;

    @Override
    public void run() {
        world = new World();
        field = new Field(getBattleFieldWidth(), getBattleFieldHeight());
        movement = new OrbitMovement(field, new Random(roundSeed()));
        gun = new GuessFactorGun(field);
        mirror = new MirrorAim(field);
        aimSpread = new Random(~roundSeed());
        randomAim = new Random(roundSeed() * 31 + 17);
        randomDraw = randomAim.nextDouble();
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

    /** The first event of every turn: the turn's work starts, and the last turn's time is reported. */
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
        mirror.ours(now, getX(), getY());
        Optional<EnemyState> target = world.closestEnemy(getX(), getY(), now, MAX_TARGET_AGE);
        steerRadar(target.orElse(null), now);
        if (target.isEmpty()) {
            gun.update(now, getX(), getY(), null);
            logPassedWaves();
            return;
        }
        EnemyState t = target.get();
        if (getOthers() == 1 && t.time() == now) {
            mirror.scanned(t.name(), now, t.x(), t.y());
        }
        if (!distanceChosen) {
            distanceChosen = true;
            if (getOthers() == 1 && hitsRarely(t.name())) {
                surfer.preferredDistance(NEAR_DISTANCE);
            }
        }
        double[] next = move(t, now);
        boolean fired = fire(t, now);
        if (fired) {
            randomDraw = randomAim.nextDouble();
        }
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
        Bullet bullet = setFireBullet(plan.get().power());
        if (bullet != null) {
            surfer.ourBulletFired(bullet, now, bullet.getX(), bullet.getY(),
                    Angles.fromRobocode(bullet.getHeadingRadians()), bullet.getPower());
            inFlight.put(bullet, new FiredShot(target.name(), plan.get().time()));
        }
        fired(bullet, plan.get());
        return true;
    }

    /** A bullet of ours in the air: at whom, from the plan of which turn. */
    private record FiredShot(String target, long planTime) {
    }

    /** Our bullets in the air, until they hit, miss or are shot down. */
    private final Map<Bullet, FiredShot> inFlight = new HashMap<>();

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
        double offset = (2 * aimSpread.nextDouble() - 1) * AIM_SPREAD / Math.max(distance, 1);
        double mirrorAim = soon && getOthers() == 1
                ? mirrorAim(target.name(), nextX, nextY, distance, now + 1, planned) : Double.NaN;
        double aim = gun.aim(target.name(), now, nextX, nextY, planned, soon, circular, offset, randomDraw,
                mirrorAim);
        if (plannedShot && getOthers() == 1 && getGunHeat() <= getGunCoolingRate() + 1e-9 && !Double.isNaN(aim)) {
            aim = shadowingAim(target.name(), now, nextX, nextY, planned, aim, offset);
        }
        if (!Double.isNaN(aim)) {
            setTurnGunRightRadians(Angles.robocodeTurn(Angles.fromRobocode(getGunHeadingRadians()), aim));
        }
    }

    /**
     * The mirror gun's aim for a shot fired in turn {@code fireTime} from ({@code nextX}, {@code nextY}) at a target
     * {@code distance} away, NaN unless the target's position follows ours ({@link MirrorAim}); our later positions
     * come from the surfer's planned path.
     */
    private double mirrorAim(String name, double nextX, double nextY, double distance, long fireTime, double power) {
        MirrorAim.Fit fit = mirror.fit(name);
        if (fit == null) {
            return Double.NaN;
        }
        // Only the turns the aim can reach: the bullet's flight, a little beyond the target's distance, less the lag.
        double speed = Rules.getBulletSpeed(power);
        int turns = Math.min(MIRROR_PLANNED_TURNS, (int) Math.ceil((distance + MIRROR_REACH) / speed) - fit.lag());
        double[][] planned = turns > 1 ? surfer.plannedPositions(ourEngineState(), turns) : null;
        double[][] future = new double[planned == null ? 1 : planned.length][];
        future[0] = new double[] {nextX, nextY};
        for (int i = 1; i < future.length; i++) {
            future[i] = planned[i];
        }
        return mirror.aim(fit, fireTime, speed, future);
    }

    /**
     * The heading for the shot of the next turn: among the anti-surfer gun's aim and its nearly as good peaks, the one
     * whose bullet leaves us the least danger to surf, its shadows on the enemy waves counted; the gun's own aim on a
     * tie. A turret-only change: the guns learn from their own aims.
     */
    private double shadowingAim(String name, long now, double nextX, double nextY, double power, double aim,
                                double spread) {
        double[][] candidates = gun.alternatives(name, now, SHADOW_MIN_SHARE, SHADOW_CANDIDATES, SHADOW_SPACING);
        if (candidates.length < 2) {
            return aim;
        }
        EngineRobot me = ourEngineState();
        double gunHeading = Angles.fromRobocode(getGunHeadingRadians());
        double best = aim;
        double bestDanger = surfer.dangerWithShot(me, now, now + 1, nextX, nextY, aim, power);
        if (Double.isNaN(bestDanger)) {
            return aim;
        }
        for (double[] c : candidates) {
            double heading = c[0] + spread;
            if (Math.abs(Angles.normalizeRelative(heading - aim)) < 1e-9
                    || Math.abs(Angles.normalizeRelative(heading - gunHeading)) > Rules.GUN_TURN_RATE_RADIANS) {
                continue;
            }
            double danger = surfer.dangerWithShot(me, now, now + 1, nextX, nextY, heading, power);
            if (danger < bestDanger) {
                bestDanger = danger;
                best = heading;
            }
        }
        if (best != aim) {
            gun.offsetPlan(name, now, best - (aim - spread));
        }
        return best;
    }

    /**
     * Whether we finish this enemy by ramming instead of shooting: a disabled enemy (energy 0) in a duel. It can
     * neither fire nor move, and a ram kill adds 30 % of all the damage we dealt it this round, a bullet kill 20 %
     * (engine-model G-46).
     */
    private boolean finishesByRamming(EnemyState target) {
        return getOthers() == 1 && target.energy() == 0;
    }

    private double bulletPower(EnemyState target, double distance) {
        if (finishesByRamming(target)) {
            return 0;
        }
        double power = distance < CLOSE_RANGE || readsWell(target.name()) ? Rules.MAX_BULLET_POWER
                : getOthers() == 1 && hitsBelow(target.name(), RARE_HIT_RATE) ? RARE_HIT_POWER : DEFAULT_POWER;
        if (getOthers() == 1 && (getEnergy() < RACE_ENERGY
                || target.energy() < RACE_ENERGY && getEnergy() < RACE_CLOSE_ENERGY)) {
            HitRecord record = HIT_RECORDS.get(target.name());
            double hits = record == null ? 0 : record.hits();
            double ended = record == null ? 0 : record.ended();
            power = RacePower.choose((hits + RACE_PRIOR_HITS) / (ended + RACE_PRIOR_BULLETS),
                    surfer.hitRateOnUs(target.name()), surfer.lastPower(target.name()), power);
        }
        power = Math.min(power, target.energy() / 4 + Rules.MIN_BULLET_POWER);
        power = Math.min(power, getEnergy() - Rules.MIN_BULLET_POWER);
        return power >= Rules.MIN_BULLET_POWER ? power : 0;
    }

    /** Whether our bullets hit this enemy often enough over the battle for full power at any distance. */
    private static boolean readsWell(String name) {
        HitRecord record = HIT_RECORDS.get(name);
        return record != null && record.ended() >= READ_MIN_BULLETS && record.rate() >= READ_HIT_RATE;
    }

    /** Whether our bullets hit this enemy rarely enough over the battle for the nearer distance. */
    private static boolean hitsRarely(String name) {
        return hitsBelow(name, NEAR_HIT_RATE);
    }

    /** Whether our bullets hit this enemy less often than {@code rate} over the battle, once enough of them ended. */
    private static boolean hitsBelow(String name, double rate) {
        HitRecord record = HIT_RECORDS.get(name);
        return record != null && record.ended() >= NEAR_MIN_BULLETS && record.rate() < rate;
    }

    /** One of our bullets fired at {@code shot}'s target ended; it hit {@code victim}, or nobody if null. */
    private static void bulletEnded(FiredShot shot, String victim) {
        if (shot != null) {
            HIT_RECORDS.computeIfAbsent(shot.target(), n -> new HitRecord()).ended(shot.target().equals(victim));
        }
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
     * wave, or in a duel the wave the enemy can fire next once its gun is cool, ramming a disabled enemy once none is
     * in the air, or orbiting the target.
     */
    private double[] move(EnemyState target, long now) {
        Optional<double[]> surf = surfer.command(ourEngineState(), now);
        if (surf.isPresent()) {
            setAhead(surf.get()[0]);
            setTurnRightRadians(surf.get()[1]);
            return predictNext(surf.get()[0], surf.get()[1]);
        }
        boolean ram = finishesByRamming(target);
        Optional<double[]> next = ram || getOthers() != 1 ? Optional.empty()
                : surfer.gunHeatCommand(ourEngineState(), target.name(), now);
        if (next.isPresent()) {
            setAhead(next.get()[0]);
            setTurnRightRadians(next.get()[1]);
            return predictNext(next.get()[0], next.get()[1]);
        }
        Optional<double[]> evade = ram ? Optional.empty() : surfer.evade(ourEngineState(), target.name(), now);
        if (evade.isPresent()) {
            setAhead(evade.get()[0]);
            setTurnRightRadians(evade.get()[1]);
            return predictNext(evade.get()[0], evade.get()[1]);
        }
        double heading = ram ? Angles.bearing(getX(), getY(), target.x(), target.y())
                : movement.desiredHeading(getX(), getY(), target.x(), target.y(), now,
                world.recentTeammates(now, MAX_TEAMMATE_AGE));
        double body = Angles.fromRobocode(getHeadingRadians());
        double right = Angles.robocodeTurn(body, heading);
        double distance = ram ? target.distanceTo(getX(), getY()) + RAM_OVERSHOOT : 100;
        if (Math.abs(right) > Math.PI / 2) {
            // Drive backwards instead of turning around.
            right = Angles.robocodeTurn(body, heading + Math.PI);
            distance = -distance;
        }
        setTurnRightRadians(right);
        setAhead(distance);
        return predictNext(distance, right);
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
        surfer.scanned(e.getName(), e.getTime(), x, y, sighting.heading(), e.getVelocity(), e.getEnergy());
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
        bulletEnded(inFlight.remove(e.getBullet()), e.getName());
        surfer.ourBulletEnded(e.getBullet());
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
    public void onBulletMissed(BulletMissedEvent e) {
        bulletEnded(inFlight.remove(e.getBullet()), null);
    }

    @Override
    public void onBulletHitBullet(BulletHitBulletEvent e) {
        FiredShot shot = inFlight.remove(e.getBullet());
        if (shot != null) {
            gun.shotDown(shot.target(), shot.planTime());
        }
        surfer.ourBulletEnded(e.getBullet());
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
