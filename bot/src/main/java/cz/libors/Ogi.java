package cz.libors;

import cz.libors.ogi.enemy.DetectedShot;
import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.BulletPower;
import cz.libors.ogi.gun.CircularAim;
import cz.libors.ogi.gun.GunLog;
import cz.libors.ogi.gun.GunModel;
import cz.libors.ogi.gun.MeleeGun;
import cz.libors.ogi.gun.MeleeGunLog;
import cz.libors.ogi.gun.MeleeGunParameters;
import cz.libors.ogi.gun.MirrorAim;
import cz.libors.ogi.gun.RacePower;
import cz.libors.ogi.gun.VirtualGuns;
import cz.libors.ogi.move.EnemyWaves;
import cz.libors.ogi.move.MeleeMovement;
import cz.libors.ogi.move.Move;
import cz.libors.ogi.move.OrbitMovement;
import cz.libors.ogi.move.WaveSurfer;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.Inactivity;
import cz.libors.ogi.physics.RobotMotion;
import cz.libors.ogi.radar.RadarControl;
import cz.libors.ogi.radar.TeamRadar;
import cz.libors.ogi.shield.Shield;
import cz.libors.ogi.shield.ShieldPlanner;
import cz.libors.ogi.shield.ShieldStance;
import cz.libors.ogi.team.EnemySighting;
import cz.libors.ogi.team.TeamEvent;
import cz.libors.ogi.team.TeamFeed;
import cz.libors.ogi.team.TeamOutbox;
import cz.libors.ogi.team.TeamPacket;
import cz.libors.ogi.team.TeamShot;
import cz.libors.ogi.team.TeammateState;
import cz.libors.ogi.trace.Overlay;
import cz.libors.ogi.trace.Trace;
import cz.libors.ogi.trace.TurnClock;
import cz.libors.ogi.world.BattleKind;
import cz.libors.ogi.world.EnemyState;
import cz.libors.ogi.world.Phase;
import cz.libors.ogi.world.World;
import robocode.Bullet;
import robocode.BulletHitBulletEvent;
import robocode.BulletHitEvent;
import robocode.BulletMissedEvent;
import robocode.DeathEvent;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.HitWallEvent;
import robocode.MessageEvent;
import robocode.RobotDeathEvent;
import robocode.Rules;
import robocode.ScannedRobotEvent;
import robocode.StatusEvent;
import robocode.TeamRobot;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Ogi, a team-first Robocode bot. One robot class serves 1v1, melee and team battles.
 *
 * <p>Radar lock in a duel, towards the stalest enemy in a melee, the enemies shared out between the radars of a team;
 * guess-factor and circular targeting chosen by virtual hits; wave surfing in a duel (orbiting the target while no
 * enemy wave is in the air), minimum risk with the likely shooters' waves in a melee; shared scans.
 */
public class Ogi extends TeamRobot {

    private static final long MAX_TARGET_AGE = 8;
    /** Teammates seen or reported within this many turns count. */
    static final long MAX_TEAMMATE_AGE = 16;
    private static final double AIM_TOLERANCE = 0.005;
    private static final double FRIENDLY_FIRE_CLEARANCE = 30;
    /**
     * In a duel, against an enemy that our bullets hit less often than this over the battle
     * ({@link BulletPower#hitsBelow}), the surfer keeps the nearer distance for the round: against the enemies we hit
     * least a nearer fight raises our hit rate more than theirs; against the rest it changes little or costs.
     */
    private static final double NEAR_HIT_RATE = 0.18;
    private static final double NEAR_DISTANCE = 275;
    /** What lasts for the battle: the only static state ({@link BattleMemory}). */
    private static final BattleMemory BATTLE = new BattleMemory();
    /** The gun searches its data only this many turns before it can fire. */
    private static final int AIM_LEAD_TURNS = 4;
    /** In a melee the melee gun aims ({@link MeleeGun}) in this many turns before the gun is cool, and while it is. */
    private static final int MELEE_AIM_TURNS = 2;
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
    /** Whether we shoot the enemy's bullets down in a duel instead of surfing them. */
    private static final boolean SHIELD = true;
    /** The gun must be this close to a shield shot's heading, in radians: the shot has to be exact. */
    private static final double SHIELD_AIM_TOLERANCE = 1e-9;
    /** While shielding, after this many turns without an enemy shot the gun shoots at the enemy. */
    private static final long SHIELD_IDLE_TURNS = 60;
    /** The power assumed for an enemy's shots before its first one. */
    private static final double SHIELD_FIRST_POWER = 2;

    private World world;
    private Field field;
    /** The duel's movement while no wave is in the air and no robot drives at us. */
    private OrbitMovement orbit;
    private MeleeMovement meleeMovement;
    /** The enemies each teammate's radar watches ({@link TeamRadar}); null outside a team. */
    private TeamRadar teamRadar;
    private RadarControl radar;
    /** The radar's turn commanded in this turn, mathematical; NaN before the first. */
    private double radarTurn = Double.NaN;
    /** Our teammates alive this round; 0 outside a team. */
    private int teammatesAlive;
    private VirtualGuns guns;
    private MirrorAim mirror;
    private MeleeGun meleeGun;
    /** The melee gun's aim planned for the next turn's shot (mathematical), and the turn it was planned in. */
    private double meleeAim;
    private long meleeAimTime = -1;
    /** The enemies' waves in the air, and the duel's surfing against them. */
    private EnemyWaves waves;
    private WaveSurfer surfer;
    private Shield shield;
    private ShieldStance stance;
    /** The enemy we shielded against in the last turn, or null. */
    private String shieldedEnemy;
    private Random aimSpread;
    /**
     * Where in the target's escape range the random gun aims: drawn once per bullet and held until the bullet fires,
     * so that the turret reaches it in the turns before the gun is cool. A draw in every turn would make the turret
     * chase it, and the bullets would fire where it happened to fall within the turret's reach.
     */
    private Random randomAim;
    private double randomDraw;
    /** What the debug overlay shows; null before the round's start. */
    private OverlayFeed overlay;
    private final TurnClock clock = new TurnClock();
    /** Whether last turn's plan for this turn is a shot we can afford. */
    private boolean plannedShot;
    /** Whether this round's preferred distance is chosen: once, in the round's first turn with a target. */
    private boolean distanceChosen;
    /**
     * Our energy as this turn's known changes leave it (shots fired last turn, bullets, rams), to tell the engine's
     * inactivity penalty from them; NaN until the first turn checked. A turn in which we hit a wall is not checked.
     */
    private double expectedEnergy = Double.NaN;
    private boolean hitWall;
    private long energyChecked = -1;
    /** What we tell our teammates about this turn. */
    private final TeamOutbox outbox = new TeamOutbox();
    /** Where the events that change an enemy's energy go: to the fire detectors, in a team through the team's feed. */
    private EnergyEvents energy;
    /** Where this turn's commands take us by the end of the next turn, {x, y}; NaN before the first plan. */
    private double[] next = {Double.NaN, Double.NaN};

    @Override
    public void run() {
        if (BATTLE.kind == null) {
            BATTLE.kind = BattleKind.of(getTeammates() != null, getOthers());
        }
        world = new World();
        field = new Field(getBattleFieldWidth(), getBattleFieldHeight());
        orbit = new OrbitMovement(field, new Random(roundSeed()));
        meleeMovement = new MeleeMovement(field, new Random(roundSeed() * 7 + 3));
        meleeMovement.logging(logsMovement());
        teammatesAlive = getTeammates() == null ? 0 : getTeammates().length;
        teamRadar = getTeammates() == null ? null : new TeamRadar();
        radar = new RadarControl(teamRadar);
        guns = new VirtualGuns(field, BATTLE.guns);
        mirror = new MirrorAim(field, BATTLE.mirror);
        meleeGun = new MeleeGun(field, BATTLE.meleeGun,
                getTeammates() == null ? MeleeGunParameters.DEFAULT : MeleeGunParameters.TEAM);
        aimSpread = new Random(~roundSeed());
        randomAim = new Random(roundSeed() * 31 + 17);
        randomDraw = randomAim.nextDouble();
        waves = new EnemyWaves(field, getGunCoolingRate(), BATTLE.waves);
        surfer = new WaveSurfer(waves);
        if (getTeammates() != null) {
            waves.teamBattle();
        }
        energy = getTeammates() == null ? EnergyEvents.direct(waves)
                : EnergyEvents.team(waves, new TeamFeed(), outbox, getRoundNum());
        shield = new Shield(field, getGunCoolingRate(), BATTLE.shield);
        stance = new ShieldStance(field, getGunCoolingRate());
        overlay = new OverlayFeed(field, world, guns, meleeGun, meleeMovement, waves, teamRadar, BATTLE.hitRates);
        overlay.roundStarted(BATTLE.kind, getTeammates() == null ? 0 : getTeammates().length + 1);
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);
        // White with an orange radar and yellow bullets: easy to find among the robots of a team battle.
        setColors(Color.WHITE, Color.WHITE, new Color(255, 140, 0));
        setBulletColor(new Color(255, 230, 40));
        setScanColor(new Color(255, 140, 0));
        while (true) {
            act();
            share();
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

    /** The enemies alive: the other robots less our teammates. */
    private int enemiesAlive() {
        return getOthers() - teammatesAlive;
    }

    /** What the round has come to in this turn, by the robots alive ({@link Phase}). */
    private Phase phase() {
        return Phase.of(enemiesAlive(), teammatesAlive);
    }

    private void act() {
        long now = getTime();
        next = new double[] {Double.NaN, Double.NaN};
        overlay.turnStarted();
        if (energyChecked != now) {
            checkInactivity(now);
        }
        waves.ourState(now, getX(), getY(), Angles.fromRobocode(getHeadingRadians()), getVelocity());
        shield.ourState(now, getX(), getY(), Angles.fromRobocode(getHeadingRadians()), getVelocity());
        energy.turnStarted(now, teammatesAlive > 0);
        waves.update(now);
        mirror.ours(now, getX(), getY());
        Optional<EnemyState> target = world.closestEnemy(getX(), getY(), now, MAX_TARGET_AGE);
        steerRadar(target.orElse(null), now);
        if (target.isEmpty()) {
            guns.update(now, getX(), getY(), null);
            logPassedWaves();
            search();
            return;
        }
        EnemyState t = target.get();
        Phase phase = phase();
        if (phase == Phase.DUEL && t.time() == now) {
            mirror.scanned(t.name(), now, t.x(), t.y());
        }
        boolean shielding = shielding(t.name());
        // For the waves of the next scans: the danger models learn only from shots fired while we surf.
        waves.learning(!shielding);
        shieldedEnemy = shielding ? t.name() : null;
        if (shielding) {
            overlay.state(Trace.State.SHIELD);
            shieldAct(t, now);
            return;
        }
        if (!distanceChosen) {
            distanceChosen = true;
            if (phase == Phase.DUEL && BATTLE.power.hitsBelow(t.name(), NEAR_HIT_RATE)) {
                surfer.preferredDistance(NEAR_DISTANCE);
            }
        }
        next = move(t, now);
        boolean fired = fire(t, now);
        if (fired) {
            randomDraw = randomAim.nextDouble();
        }
        guns.update(now, getX(), getY(), fired ? t.name() : null);
        logPassedWaves();
        aim(t, now, next[0], next[1]);
        guns.planView(t.name(), now).ifPresent(v -> setDebugProperty("gun", Trace.gunLine(v, fired)));
        if (logsGun()) {
            guns.plan(t.name(), now).ifPresent(p -> setDebugProperty(GunLog.SHOT, GunLog.line(p.shot(),
                    p.decision())));
        }
    }

    /**
     * Tells the teammates, once a turn after acting, where we are and where this turn's commands take us and our
     * radar, whom we scanned and what we fired: one message, serialized once. They read it in the next turn
     * (engine-model G-23).
     */
    private void share() {
        if (getTeammates() != null && teammatesAlive > 0) {
            boolean planned = !Double.isNaN(next[0]);
            double nextX = planned ? next[0] : getX();
            double nextY = planned ? next[1] : getY();
            double turn = Double.isNaN(radarTurn) ? 0
                    : Math.max(-Rules.RADAR_TURN_RATE_RADIANS, Math.min(Rules.RADAR_TURN_RATE_RADIANS, radarTurn));
            double nextRadar = Angles.normalizeAbsolute(Angles.fromRobocode(getRadarHeadingRadians()) + turn);
            TeammateState me = new TeammateState(getName(), getX(), getY(),
                    Angles.fromRobocode(getHeadingRadians()), getVelocity(), getEnergy(), nextX, nextY, nextRadar,
                    getTime(), getRoundNum(), overlay.painting(getTime()));
            // The teammates plan the radars with this report in the next turn, and so do we.
            teamRadar.reported(new TeamRadar.Watcher(getName(), nextX, nextY, nextRadar, getTime() + 1));
            try {
                broadcastMessage(outbox.take(me));
            } catch (IOException ex) {
                out.println("Broadcast failed: " + ex.getMessage());
            }
        }
        outbox.clear();
    }

    /**
     * Once a turn, before the turn's scans: an unexplained loss of exactly the inactivity penalty means the engine took
     * it from every robot, the enemies too, which their fire detectors must not take for a shot.
     */
    private void checkInactivity(long now) {
        if (!Double.isNaN(expectedEnergy) && !hitWall && energyChecked == now - 1
                && Math.abs(getEnergy() - (expectedEnergy - Inactivity.ZAP)) < 1e-6) {
            energy.zapped(now);
        }
        expectedEnergy = getEnergy();
        hitWall = false;
        energyChecked = now;
    }

    /** A bullet of ours was fired in this turn: its power leaves our energy in the next. */
    private void paid(Bullet bullet) {
        if (bullet != null) {
            expectedEnergy -= bullet.getPower();
        }
    }

    /**
     * Whether we shoot this enemy's bullets down instead of surfing them: in a duel battle, until its trial fails;
     * never in the duel at the end of a melee or a team battle: the last enemy has seen us move all round, and a failed
     * trial would cost the round we fought for.
     */
    private boolean shielding(String name) {
        return SHIELD && BATTLE.kind == BattleKind.DUEL && phase() == Phase.DUEL && !BATTLE.shieldTrial.dropped(name);
    }

    /** Reports the shield trial's verdict, if it just dropped the shield against an enemy. */
    private void trialVerdict(String verdict) {
        if (verdict != null) {
            setDebugProperty("shieldTrial", verdict);
        }
    }

    @Override
    public void onDeath(DeathEvent e) {
        if (shieldedEnemy != null && getOthers() > 0) {
            trialVerdict(BATTLE.shieldTrial.roundLost(shieldedEnemy));
        }
    }

    /**
     * A turn of bullet shielding: fire the shield shot planned last turn, then stand (stepping a tenth of a pixel aside
     * when a shot needs it, and back), keep the body across the line to the enemy and turn the gun for the next shield
     * shot. Too close to the enemy we back away first. A disabled enemy gets the weakest bullet once no bullet of its
     * is left to shoot down: any hit kills it, and ramming it would move us, which guns that learn our movement learn
     * too. After a long time without enemy shots the gun shoots at the enemy instead.
     */
    private void shieldAct(EnemyState target, long now) {
        double fired = 0;
        Optional<ShieldPlanner.Plan> due = shield.due(now);
        if (due.isPresent() && getGunHeat() == 0 && getEnergy() > due.get().power() && Math.abs(
                Angles.normalizeRelative(due.get().heading() - Angles.fromRobocode(getGunHeadingRadians())))
                < SHIELD_AIM_TOLERANCE) {
            Bullet bullet = setFireBullet(due.get().power());
            paid(bullet);
            if (bullet != null) {
                waves.ourBulletFired(bullet, now, bullet.getX(), bullet.getY(),
                        Angles.fromRobocode(bullet.getHeadingRadians()), bullet.getPower());
                shield.fired(now);
                overlay.shieldFired(now, bullet);
                fired = bullet.getPower();
            }
        }
        String event = shield.takeEvent();
        if (event != null) {
            setDebugProperty("shield", event);
        }
        double bearing = Angles.bearing(getX(), getY(), target.x(), target.y());
        if (finishesByRamming(target) && !shield.hasTargets()) {
            guns.update(now, getX(), getY(), null);
            setAhead(0);
            double gunHeading = Angles.fromRobocode(getGunHeadingRadians());
            double within = Math.atan2(RobotMotion.HALF_SIZE / 2.0, target.distanceTo(getX(), getY()));
            if (fired == 0 && getGunHeat() == 0 && getEnergy() > Rules.MIN_BULLET_POWER
                    && Math.abs(Angles.normalizeRelative(bearing - gunHeading)) < within) {
                Bullet finisher = setFireBullet(Rules.MIN_BULLET_POWER);
                paid(finisher);
                overlay.shieldFired(now, finisher);
            }
            setTurnGunRightRadians(Angles.robocodeTurn(gunHeading, bearing));
            return;
        }
        ShieldStance.Stance standing = stance.next(ourEngineState(), target.x(), target.y(),
                Rules.getBulletSpeed(shield.nextPower(target.name(), SHIELD_FIRST_POWER)));
        double bodyTurn = standing.bodyTurn();
        double[] moves = standing.moves();
        setTurnRightRadians(bodyTurn);
        if (fired == 0 && shield.sinceLastShot(now) > SHIELD_IDLE_TURNS) {
            boolean shot = fire(target, now);
            guns.update(now, getX(), getY(), shot ? target.name() : null);
            setAhead(moves[0]);
            aim(target, now, getX(), getY());
            return;
        }
        guns.update(now, getX(), getY(), null);
        // The heat and energy after this turn's shield shot, if it fired (engine-model G-49).
        EngineRobot me = ourEngineState();
        me.adjustGunForBodyTurn = true;
        me.adjustRadarForGunTurn = true;
        me.adjustRadarForBodyTurn = true;
        ShieldPlanner.Plan plan = shield.plan(me, now, moves, bodyTurn, Math.min(Rules.MAX_BULLET_POWER,
                getEnergy() - Rules.MIN_BULLET_POWER));
        if (plan == null) {
            setAhead(moves[0]);
            setTurnGunRightRadians(Angles.robocodeTurn(Angles.fromRobocode(getGunHeadingRadians()), bearing));
        } else {
            setAhead(shield.isCommitted(now) ? plan.distance() : moves[0]);
            setTurnGunRightRadians(plan.gunTurn());
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

    /**
     * Whether the melee movement's decisions go to debug properties for the offline position bench
     * ({@link MeleeMovement#logging}): {@link OgiGunLog} in a team battle, whose logs are recorded in the engine's
     * debug mode (predicting every candidate against the waves costs turn time).
     */
    protected boolean logsMovement() {
        return logsGun() && getTeammates() != null;
    }

    /** The gun waves that passed in this turn, logged before the turn's plan. */
    private void logPassedWaves() {
        if (!logsGun()) {
            return;
        }
        for (GunModel.Pass pass : guns.passed()) {
            setDebugProperty(GunLog.PASS, GunLog.line(pass));
        }
    }

    /** Steers the radar ({@link RadarControl}); while the gun carries it, the gun turns along. */
    private void steerRadar(EnemyState target, long now) {
        RadarControl.Command c = radar.steer(now, getName(), getX(), getY(),
                Angles.fromRobocode(getRadarHeadingRadians()), phase(), enemiesAlive(), teammatesAlive,
                world.enemies(), target, getGunHeat() <= 2 * getGunCoolingRate(),
                getGunHeat() > (MELEE_AIM_TURNS + 1) * getGunCoolingRate());
        turnRadar(c.radarTurn());
        if (c.sweepChanged()) {
            setAdjustRadarForGunTurn(!c.gunCarries());
        }
        if (c.gunCarries()) {
            setTurnGunRightRadians(-c.radarTurn());
        }
    }

    /**
     * No enemy seen in the last {@link #MAX_TARGET_AGE} turns, although some that we saw this round live: we drive
     * towards the field's centre, from where the radar reaches every point of the field. From a corner of a team
     * battle's field (1200 × 1200) the far corners are out of the radar's range (1200 px), and without a target we
     * would neither move nor fire. Before the round's first scan we wait for the radar as before.
     */
    private void search() {
        if (world.enemyCount() == 0) {
            return;
        }
        Move m = Move.to(getX(), getY(), Angles.fromRobocode(getHeadingRadians()), getBattleFieldWidth() / 2,
                getBattleFieldHeight() / 2);
        setAhead(m.distance());
        setTurnRightRadians(m.rightTurn());
    }

    /** Commands the radar's turn, mathematical (counter-clockwise positive). */
    private void turnRadar(double turn) {
        radarTurn = turn;
        setTurnRadarRightRadians(-turn);
    }

    /** Fires last turn's plan if the gun is cool and reached its aim; returns whether it fired. */
    private boolean fire(EnemyState target, long now) {
        Optional<VirtualGuns.Plan> plan = guns.planFor(target.name(), now);
        if (plan.isEmpty() || !plannedShot || getGunHeat() > 0 || getEnergy() <= plan.get().power()) {
            return false;
        }
        double gunHeading = Angles.fromRobocode(getGunHeadingRadians());
        double planned = meleeAimTime == now - 1 ? meleeAim : plan.get().aim();
        if (Math.abs(Angles.normalizeRelative(planned - gunHeading)) >= AIM_TOLERANCE
                || teammateInLineOfFire(target.distanceTo(getX(), getY()), now)) {
            return false;
        }
        Bullet bullet = setFireBullet(plan.get().power());
        paid(bullet);
        if (bullet != null) {
            waves.ourBulletFired(bullet, now, bullet.getX(), bullet.getY(),
                    Angles.fromRobocode(bullet.getHeadingRadians()), bullet.getPower());
            boolean melee = meleeAimTime == now - 1;
            String aimedAt = melee && meleeGun.aimedAt() != null ? meleeGun.aimedAt() : target.name();
            inFlight.put(bullet, new FiredShot(aimedAt, plan.get().time(), melee));
            overlay.fired(now, bullet, aimedAt, melee, plan.get().decision().gun());
            outbox.shot(new TeamShot(getName(), bullet.getX(), bullet.getY(),
                    Angles.fromRobocode(bullet.getHeadingRadians()), bullet.getPower(), now, getRoundNum()));
            if (melee && logsGun()) {
                setDebugProperty(MeleeGunLog.FIRE, MeleeGunLog.fireLine(meleeAimTime,
                        Angles.fromRobocode(bullet.getHeadingRadians()), bullet.getPower(), bullet.getX(),
                        bullet.getY()));
            }
        }
        fired(bullet, plan.get());
        return true;
    }

    /** A bullet of ours in the air: at whom, from the plan of which turn, and whether the melee gun aimed it. */
    private record FiredShot(String target, long planTime, boolean melee) {
    }

    /** Our bullets in the air, until they hit, miss or are shot down. */
    private final Map<Bullet, FiredShot> inFlight = new HashMap<>();

    /** A hook for {@link OgiGunLog}: this bullet was just fired from this plan. */
    protected void fired(Bullet bullet, VirtualGuns.Plan plan) {
    }

    /**
     * Plans the shot of the next turn from where we will be then; the gun fires along the heading it reaches by the
     * end of the next turn (engine-model G-01).
     */
    private void aim(EnemyState target, long now, double nextX, double nextY) {
        double distance = target.distanceTo(nextX, nextY);
        double power = bulletPower(target, distance);
        if (!finishesByRamming(target) && racing(target)) {
            overlay.state(Trace.State.SAVING_ENERGY);
        }
        plannedShot = power > 0;
        double planned = power > 0 ? power : BulletPower.DEFAULT;
        double circular = CircularAim.aim(nextX, nextY, target, Rules.getBulletSpeed(planned), now + 1, field);
        boolean soon = getGunHeat() <= AIM_LEAD_TURNS * getGunCoolingRate();
        double offset = (2 * aimSpread.nextDouble() - 1) * AIM_SPREAD / Math.max(distance, 1);
        Phase phase = phase();
        double mirrorAim = soon && phase == Phase.DUEL
                ? mirrorAim(target.name(), nextX, nextY, distance, now + 1, planned) : Double.NaN;
        double aim = guns.aim(target.name(), now, nextX, nextY, planned, soon, circular, offset, randomDraw,
                mirrorAim, enemiesAlive() <= 1);
        if (plannedShot && phase == Phase.DUEL && getGunHeat() <= getGunCoolingRate() + 1e-9 && !Double.isNaN(aim)) {
            aim = shadowingAim(target.name(), now, nextX, nextY, planned, aim, offset);
        }
        meleeAimTime = -1;
        if (phase == Phase.MELEE && getGunHeat() <= MELEE_AIM_TURNS * getGunCoolingRate() + 1e-9) {
            meleeGun.tracing(overlay.painting(now));
            double melee = meleeGun.aim(now + 1, nextX, nextY, Rules.getBulletSpeed(planned));
            overlay.meleeAimed(now);
            if (logsGun()) {
                setDebugProperty(MeleeGunLog.AIM, MeleeGunLog.aimLine(now + 1, nextX, nextY,
                        Rules.getBulletSpeed(planned), melee, meleeGun.aimedAt()));
            }
            if (!Double.isNaN(melee)) {
                aim = melee;
                meleeAim = melee;
                meleeAimTime = now;
            }
        }
        if (!Double.isNaN(aim) && !radar.sweeping()) {
            setTurnGunRightRadians(Angles.robocodeTurn(Angles.fromRobocode(getGunHeadingRadians()), aim));
        }
        if (plannedShot && getGunHeat() <= getGunCoolingRate() + 1e-9) {
            overlay.planned(target, now, nextX, nextY, Rules.getBulletSpeed(planned), meleeAimTime == now);
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
        double[][] candidates = guns.alternatives(name, now, SHADOW_MIN_SHARE, SHADOW_CANDIDATES, SHADOW_SPACING);
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
            guns.offsetPlan(name, now, best - (aim - spread));
        }
        return best;
    }

    /**
     * Whether we finish this enemy by ramming instead of shooting: a disabled enemy (energy 0) in a duel. It can
     * neither fire nor move, and a ram kill adds 30 % of all the damage we dealt it this round, a bullet kill 20 %
     * (engine-model G-46).
     */
    private boolean finishesByRamming(EnemyState target) {
        return phase() == Phase.DUEL && target.energy() == 0;
    }

    /** The power of our next bullet at the target this far away ({@link BulletPower}); 0 to finish it by ramming. */
    private double bulletPower(EnemyState target, double distance) {
        if (finishesByRamming(target)) {
            return 0;
        }
        return BATTLE.power.power(target.name(), distance, target.energy(), getEnergy(), phase() == Phase.DUEL,
                waves.hitRateOnUs(target.name()), waves.lastPower(target.name()));
    }

    /** Whether our power is the energy race's ({@link RacePower}): in a duel, at the race's energies. */
    private boolean racing(EnemyState target) {
        return phase() == Phase.DUEL && BulletPower.racing(getEnergy(), target.energy());
    }

    /** One of our bullets fired at {@code shot}'s target ended; it hit {@code victim}, or nobody if null. */
    private static void bulletEnded(FiredShot shot, String victim) {
        if (shot != null) {
            BATTLE.power.ended(shot.target(), shot.target().equals(victim));
            BATTLE.hitRates.aimed(shot.target(), shot.target().equals(victim));
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

    /** Sets this turn's movement ({@link #movement}) and returns where it takes us by the end of the next turn. */
    private double[] move(EnemyState target, long now) {
        Move m = movement(target, now);
        setAhead(m.distance());
        setTurnRightRadians(m.rightTurn());
        return predictNext(m);
    }

    /**
     * This turn's movement: in a melee and in a team's endgame the safest place ({@link MeleeMovement}); in a duel
     * surfing the nearest enemy wave, or the wave the enemy can fire next once its gun is cool, the ways around a
     * robot that drives at us, ramming a disabled enemy once none of its waves is in the air, or orbiting the target.
     */
    private Move movement(EnemyState target, long now) {
        Phase phase = phase();
        // The team's endgame moves as a melee does: the finisher draws us to the last enemy.
        if (phase == Phase.MELEE || phase == Phase.TEAM_ENDGAME) {
            List<MeleeMovement.Robot> robots = new ArrayList<>();
            for (EnemyState e : world.enemies()) {
                robots.add(new MeleeMovement.Robot(e.x(), e.y(), e.energy(), true));
            }
            for (World.Teammate t : world.recentTeammates(now, MAX_TEAMMATE_AGE)) {
                robots.add(new MeleeMovement.Robot(t.x(), t.y(), 0, false));
            }
            if (teammatesAlive > 0) {
                meleeMovement.gangDefence(BATTLE.gangs.turn(getX(), getY(), robots));
            }
            meleeMovement.tracing(overlay.painting(now));
            Move m = meleeMovement.command(now, getX(), getY(), Angles.fromRobocode(getHeadingRadians()), getEnergy(),
                    robots, waves, ourEngineState());
            if (meleeMovement.logLine() != null) {
                setDebugProperty(MeleeMovement.LOG, meleeMovement.logLine());
            }
            return m;
        }
        boolean ram = finishesByRamming(target);
        if (ram) {
            overlay.state(Trace.State.RAM);
        }
        // Only for the overlay: a battle without the GUI computes nothing more.
        if (overlay.painting(now) && surfer.antiRam(target.name(), now)) {
            overlay.state(Trace.State.ANTI_RAM);
        }
        Optional<Move> surf = surfer.command(ourEngineState(), now);
        if (surf.isPresent()) {
            return surf.get();
        }
        Optional<Move> heat = ram ? Optional.empty() : surfer.gunHeatCommand(ourEngineState(), target.name(), now);
        if (heat.isPresent()) {
            return heat.get();
        }
        Optional<Move> evade = ram ? Optional.empty() : surfer.evade(ourEngineState(), target.name(), now);
        if (evade.isPresent()) {
            return evade.get();
        }
        if (!ram) {
            overlay.state(Trace.State.LONG_ORBIT);
        }
        double heading = ram ? Angles.bearing(getX(), getY(), target.x(), target.y())
                : orbit.desiredHeading(getX(), getY(), target.x(), target.y(), now,
                world.recentTeammates(now, MAX_TEAMMATE_AGE));
        double body = Angles.fromRobocode(getHeadingRadians());
        double right = Angles.robocodeTurn(body, heading);
        double distance = ram ? target.distanceTo(getX(), getY()) + RAM_OVERSHOOT : 100;
        if (Math.abs(right) > Math.PI / 2) {
            // Drive backwards instead of turning around.
            right = Angles.robocodeTurn(body, heading + Math.PI);
            distance = -distance;
        }
        return new Move(distance, right);
    }

    /**
     * Our position at the end of the next turn under this movement, by the engine's own rules; exact unless we collide
     * with a robot.
     */
    private double[] predictNext(Move m) {
        EngineRobot me = ourEngineState();
        me.distanceRemaining = m.distance();
        me.bodyTurnRemaining = m.rightTurn();
        RobotMotion.move(me, (int) field.width(), (int) field.height(), getGunCoolingRate());
        return new double[] {me.x, me.y};
    }

    /**
     * Our state at the end of this turn in the engine's convention, without commands; after a shot set in this turn,
     * with its heat and its power paid (engine-model G-49).
     */
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
        if (energyChecked != e.getTime()) {
            checkInactivity(e.getTime());
        }
        double bearing = Angles.fromRobocode(getHeadingRadians() + e.getBearingRadians());
        double x = getX() + e.getDistance() * Math.cos(bearing);
        double y = getY() + e.getDistance() * Math.sin(bearing);
        if (isTeammate(e.getName())) {
            world.observeTeammate(new World.Teammate(e.getName(), x, y, Angles.fromRobocode(e.getHeadingRadians()),
                    e.getVelocity(), e.getEnergy(), e.getTime()));
            waves.teammateSeen(e.getName(), e.getTime(), x, y);
            return;
        }
        EnemySighting sighting = new EnemySighting(e.getName(), x, y, Angles.fromRobocode(e.getHeadingRadians()),
                e.getVelocity(), e.getEnergy(), e.getTime(), getRoundNum());
        enemySeen(sighting, true);
        shield.scanned(e.getName(), e.getTime(), x, y, sighting.heading(), e.getVelocity(), e.getEnergy());
        Optional<DetectedShot> shot = energy.scanned(sighting);
        shot.ifPresent(shield::shotDetected);
        if (shot.isPresent() && shielding(e.getName())) {
            trialVerdict(BATTLE.shieldTrial.fired(e.getName()));
        }
        outbox.sighting(sighting);
    }

    /**
     * A scan of an enemy, ours ({@code ours}) or a teammate's, for every component that follows the enemies' positions;
     * the fire detectors take it afterwards ({@link #energy}).
     */
    private void enemySeen(EnemySighting s, boolean ours) {
        world.observeEnemy(s);
        if (teamRadar != null) {
            teamRadar.scanned(s.name(), s.x(), s.y(), s.time());
        }
        meleeScan(s);
        guns.scanned(s.name(), s.time(), s.x(), s.y(), s.heading(), s.velocity());
        waves.seen(s.name(), s.time(), s.x(), s.y(), s.heading(), s.velocity(), ours);
    }

    /**
     * A scan of an enemy, ours or a teammate's, for the melee gun while more than one enemy lives; its situation
     * includes the robot closest to it, as far as we know: us, or another robot where it was seen last.
     */
    private void meleeScan(EnemySighting s) {
        if (phase() != Phase.MELEE) {
            return;
        }
        int enemies = enemiesAlive();
        double x = s.x();
        double y = s.y();
        double cx = getX();
        double cy = getY();
        for (EnemyState other : world.enemies()) {
            if (!other.name().equals(s.name())
                    && Math.hypot(other.x() - x, other.y() - y) < Math.hypot(cx - x, cy - y)) {
                cx = other.x();
                cy = other.y();
            }
        }
        for (World.Teammate t : world.recentTeammates(s.time(), MAX_TEAMMATE_AGE)) {
            if (Math.hypot(t.x() - x, t.y() - y) < Math.hypot(cx - x, cy - y)) {
                cx = t.x();
                cy = t.y();
            }
        }
        meleeGun.scanned(s.name(), s.time(), x, y, s.heading(), s.velocity(), cx, cy, enemies);
        if (logsGun()) {
            setDebugProperty(MeleeGunLog.SCAN, MeleeGunLog.scanLine(s.name(), s.time(), x, y, s.heading(),
                    s.velocity(), cx, cy, enemies));
        }
    }

    @Override
    public void onBulletHit(BulletHitEvent e) {
        expectedEnergy += Rules.getBulletHitBonus(e.getBullet().getPower());
        FiredShot shot = inFlight.remove(e.getBullet());
        logMeleeEnd(shot, 'h', e.getName());
        bulletEnded(shot, e.getName());
        meleeEnded(shot, !isTeammate(e.getName()));
        waves.ourBulletEnded(e.getBullet());
        if (!isTeammate(e.getName())) {
            energy.hitByUs(e.getName(), e.getBullet().getPower(), e.getTime());
            overlay.hitEnemy(e.getTime(), e.getBullet().getPower());
        }
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        expectedEnergy -= Rules.getBulletDamage(e.getPower());
        if (!isTeammate(e.getName())) {
            EnemyWaves.HitOrigin origin = waves.learnHit(e.getName(), e.getTime(), e.getBullet().getX(),
                    e.getBullet().getY(), Angles.fromRobocode(e.getHeadingRadians()), e.getPower());
            overlay.hitUs(e.getTime(), e.getBullet().getX(), e.getBullet().getY(), e.getName(), e.getPower(), origin);
            energy.hitUs(e.getName(), e.getPower(), e.getTime());
            shield.hitByBullet(e.getName(), e.getTime(), e.getBullet().getX(), e.getBullet().getY(),
                    Angles.fromRobocode(e.getHeadingRadians()), e.getPower());
            if (shielding(e.getName())) {
                trialVerdict(BATTLE.shieldTrial.hit(e.getName(), Rules.getBulletDamage(e.getPower())));
            }
        }
    }

    @Override
    public void onBulletMissed(BulletMissedEvent e) {
        FiredShot shot = inFlight.remove(e.getBullet());
        logMeleeEnd(shot, 'm', null);
        bulletEnded(shot, null);
        meleeEnded(shot, false);
    }

    /** One of our bullets ended, an enemy hit or not: the melee gun's record for the overlay. */
    private static void meleeEnded(FiredShot shot, boolean hitEnemy) {
        if (shot != null && shot.melee()) {
            BATTLE.hitRates.melee(hitEnemy);
        }
    }

    /** For the melee bench: how a bullet the melee gun aimed ended ({@link MeleeGunLog#END}). */
    private void logMeleeEnd(FiredShot shot, char how, String robot) {
        if (shot != null && shot.melee() && logsGun()) {
            setDebugProperty(MeleeGunLog.END, MeleeGunLog.endLine(shot.planTime(), how, robot));
        }
    }

    @Override
    public void onBulletHitBullet(BulletHitBulletEvent e) {
        FiredShot shot = inFlight.remove(e.getBullet());
        if (shot != null) {
            guns.shotDown(shot.target(), shot.planTime());
        }
        logMeleeEnd(shot, 'b', e.getHitBullet().getName());
        waves.ourBulletEnded(e.getBullet());
        if (!isTeammate(e.getHitBullet().getName())) {
            waves.bulletHitBullet(e.getHitBullet().getName(), e.getTime(), e.getHitBullet().getX(),
                    e.getHitBullet().getY(), Angles.fromRobocode(e.getHitBullet().getHeadingRadians()),
                    e.getHitBullet().getPower());
            shield.bulletHitBullet(e.getHitBullet().getName(), e.getTime(), e.getHitBullet().getX(),
                    e.getHitBullet().getY(), Angles.fromRobocode(e.getHitBullet().getHeadingRadians()),
                    e.getHitBullet().getPower());
            if (shielding(e.getHitBullet().getName())) {
                trialVerdict(BATTLE.shieldTrial.shotDown(e.getHitBullet().getName()));
            }
        }
    }

    @Override
    public void onHitRobot(HitRobotEvent e) {
        expectedEnergy -= Rules.ROBOT_HIT_DAMAGE;
        if (!isTeammate(e.getName())) {
            energy.rammed(e.getName(), e.getTime());
        }
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        hitWall = true;
    }

    /**
     * Called only while painting is enabled for this robot in the GUI, before the robot acts: the overlay is painted,
     * and the shots planned and fired from now on are traced too ({@link OverlayFeed}).
     */
    @Override
    public void onPaint(Graphics2D g) {
        if (overlay != null) {
            overlay.paint(g, getTime(), getX(), getY(), getEnergy(), BATTLE.kind, phase(), enemiesAlive(), getName(),
                    radarTurn, inFlight.keySet());
        }
    }

    /**
     * A key pressed in the battle view switches a layer of the overlay, or in a team which robots draw their own layers
     * ({@link Overlay#key}). Every live robot gets the key, painted or not (the round's start offers the layers), so
     * the live ones keep the same choice; a dead one misses it (bot.md "Debug overlay").
     */
    @Override
    public void onKeyPressed(KeyEvent e) {
        Overlay.key(e.getKeyChar());
    }

    @Override
    public void onMessageReceived(MessageEvent e) {
        if (!(e.getMessage() instanceof TeamPacket packet) || packet.state().round() != getRoundNum()) {
            return;
        }
        TeammateState s = packet.state();
        world.teammateReported(s);
        overlay.teammateReported(s.name(), s.time(), s.painted());
        teamRadar.reported(new TeamRadar.Watcher(s.name(), s.nextX(), s.nextY(), s.nextRadarHeading(), s.time() + 1));
        waves.teammateSeen(s.name(), s.time() + 1, s.nextX(), s.nextY());
        for (EnemySighting sighting : packet.sightings()) {
            if (world.dead(sighting.name())) {
                // Its death came first: a packet late by its sender's skipped turn. The scans of the turn before a
                // death arrive in the death's turn ahead of it (engine-model G-52: messages 75, deaths 70): taken
                // here, they are cleared by onRobotDeath, except in the team's energy feed.
                continue;
            }
            enemySeen(sighting, false);
            energy.scanned(sighting);
        }
        for (TeamEvent event : packet.events()) {
            energy.reported(event);
        }
        for (TeamShot shot : packet.shots()) {
            waves.teammateFired(shot.shooter(), shot.fireTime(), shot.x(), shot.y(), shot.heading(), shot.power(),
                    e.getTime());
            overlay.teamShot(shot, e.getTime());
        }
    }

    @Override
    public void onRobotDeath(RobotDeathEvent e) {
        if (isTeammate(e.getName())) {
            teammatesAlive--;
        }
        world.robotDied(e.getName());
        if (teamRadar != null) {
            teamRadar.died(e.getName());
        }
        guns.robotDied(e.getName());
        meleeGun.robotDied(e.getName());
        if (logsGun()) {
            setDebugProperty(MeleeGunLog.DEATH, e.getName());
        }
        waves.robotDied(e.getName());
    }
}
