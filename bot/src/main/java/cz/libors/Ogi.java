package cz.libors;

import cz.libors.ogi.enemy.DetectedShot;
import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.CircularAim;
import cz.libors.ogi.gun.GuessFactorGun;
import cz.libors.ogi.gun.GunLog;
import cz.libors.ogi.gun.GunModel;
import cz.libors.ogi.gun.HitRecord;
import cz.libors.ogi.gun.MeleeGun;
import cz.libors.ogi.gun.MeleeGunLog;
import cz.libors.ogi.gun.MeleeGunParameters;
import cz.libors.ogi.gun.MirrorAim;
import cz.libors.ogi.gun.RacePower;
import cz.libors.ogi.move.MeleeMovement;
import cz.libors.ogi.move.OrbitMovement;
import cz.libors.ogi.move.WaveSurfer;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.Inactivity;
import cz.libors.ogi.physics.RobotMotion;
import cz.libors.ogi.radar.MeleeRadar;
import cz.libors.ogi.radar.TeamRadar;
import cz.libors.ogi.shield.Shield;
import cz.libors.ogi.shield.ShieldPlanner;
import cz.libors.ogi.team.EnemySighting;
import cz.libors.ogi.team.TeamEvent;
import cz.libors.ogi.team.TeamFeed;
import cz.libors.ogi.team.TeamPacket;
import cz.libors.ogi.team.TeamShot;
import cz.libors.ogi.team.TeammateState;
import cz.libors.ogi.trace.Overlay;
import cz.libors.ogi.trace.PositionTrails;
import cz.libors.ogi.trace.ShotTrails;
import cz.libors.ogi.trace.Trace;
import cz.libors.ogi.trace.TurnClock;
import cz.libors.ogi.world.EnemyState;
import cz.libors.ogi.world.Names;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Ogi, a team-first Robocode bot. One robot class serves 1v1, melee and team battles.
 *
 * <p>Radar lock in a duel, towards the stalest enemy in a melee, the enemies shared out between the radars of a team;
 * guess-factor and circular targeting chosen by virtual hits; wave surfing in a duel (orbiting the target while no
 * enemy wave is in the air), minimum risk with the likely shooters' waves in a melee; shared scans.
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
    /**
     * Our bullets at each enemy over the battle (statics last for the battle, as the gun's data do); the copies of one
     * robot in an enemy team together.
     */
    private static final Map<String, HitRecord> HIT_RECORDS = new HashMap<>();
    /** Our bullets aimed at each enemy over the battle, by its own name, {ended, hit it}: for the overlay. */
    private static final Map<String, int[]> AIMED_HITS = new HashMap<>();
    /** The overlay shows our hit rate on an enemy once this many of our bullets aimed at it ended. */
    private static final int INFO_MIN_BULLETS = 5;
    /** What the melee gun learned in this battle. */
    private static final MeleeGun.Memory MELEE_MEMORY = new MeleeGun.Memory();
    /** The gun searches its data only this many turns before it can fire. */
    private static final int AIM_LEAD_TURNS = 4;
    /** In a melee the melee gun aims ({@link MeleeGun}) in this many turns before the gun is cool, and while it is. */
    private static final int MELEE_AIM_TURNS = 2;
    /** The overlay shows the guess-factor gun's weight at this many guess factors. */
    private static final int TRACED_BINS = 47;
    /** The melee gun's last aim stays on the overlay this many turns: the bullet's flight, about. */
    private static final int MELEE_AIM_SHOWN = 20;
    /** The melee gun's name in the overlay. */
    private static final String MELEE_GUN = "melee";
    /** The melee gun's bullets over the battle, for the overlay: {hits on an enemy, ended}; shot down left out. */
    private static final int[] MELEE_BULLETS = new int[2];
    /**
     * In a melee, while the radar spins at the round's start and the gun is too hot to fire soon, the gun spins along
     * and carries the radar (engine-model G-09): 65° a turn instead of 45°.
     */
    private static final boolean FAST_FIRST_SWEEP = true;
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
    /**
     * While shielding we stand where the round put us and return there after each step aside; pushed further than this
     * (by a robot, or while ramming) we stand where we are.
     */
    private static final double SHIELD_HOME_DRIFT = 2;
    /**
     * Closer to the enemy than this plus this many pixels per unit of the speed of its next bullet (as its last shot
     * this round, before that the mean over the battle), our shot cannot meet its bullet in time: we back away first,
     * still shielding. With these our fastest bullet, fired up to two turns after the enemy's, meets it before it is
     * within the planner's clearance: about 105 px against a bullet of power 0.1, 80 px against one of power 3. A
     * retreat costs more than it saves when it need not be: guns that learn or smooth our movement miss for a few
     * shots after it, and some enemies move away by themselves; but an enemy low on energy that stands close and
     * fires weak bullets would hit us with every one.
     */
    private static final double SHIELD_MIN_DISTANCE = 38;
    private static final double SHIELD_DISTANCE_PER_SPEED = 3.4;
    /** The power assumed for an enemy's shots before its first one. */
    private static final double SHIELD_FIRST_POWER = 2;
    /**
     * The trial: every duel starts with the shield, and once the damage the enemy's bullets did to us while we shielded
     * exceeds this much plus this much per shot it fired then, we surf it for the rest of the battle. Not the other way
     * round: guns that learn our movement learn a few rounds of surfing, and a shield tried after them fails. In shield mode
     * we score about 60 a round (survival), so 0.25 a shot at about 40 shots a round keeps a share of about 80 %. The
     * start allows two full-power hits: the aim models learn from the first bullets.
     */
    private static final double SHIELD_TRIAL_DAMAGE = 35;
    private static final double SHIELD_TRIAL_RATE = 0.25;
    /**
     * We surf the enemy too once we lost this many rounds while shielding: an enemy that hits with many weak bullets
     * wins the energy race though its damage stays within the budget.
     */
    private static final int SHIELD_TRIAL_ROUNDS = 2;
    /**
     * We surf the enemy too once this many of its bullets ended while we shielded and fewer than this share of them
     * were shot down: against strong bots of many weak bullets the budget above held for hundreds of hits over several
     * rounds, while where the shield works it had shot down 85 % and more of the first 20 (2026-10-17).
     * Not on the first bullets alone: before the aim models learn, a bot we later shoot down hits us too.
     */
    private static final int SHIELD_FAIL_BULLETS = 20;
    private static final double SHIELD_FAIL_DOWN_SHARE = 0.5;
    /**
     * We surf the enemy too once this many of its bullets hit us while we shielded before we shot one down: against
     * 275 bots the shield works on we were hit at most 3 times before the first one went down; some strong bots answer
     * a standing robot with weak bullets (6 hits for 2–12 damage), which the budget took 8–11 hits to notice
     * (2026-10-17).
     */
    private static final int SHIELD_FAIL_HITS = 6;
    /**
     * Per enemy, for the battle: {the damage its bullets did to us while we shielded, its shots then, rounds lost, its
     * bullets we shot down then, its bullets that hit us then}.
     */
    private static final Map<String, double[]> SHIELD_TRIALS = new HashMap<>();
    /** The enemies we stopped shielding against or never try it against, for the battle. */
    private static final Set<String> SHIELD_DROPPED = new HashSet<>();
    /**
     * Whether this battle started as a melee (statics last for the battle). Its duel at the end is surfed: the last
     * enemy has seen us move all round, and a failed trial would cost the round we fought for.
     */
    private static boolean meleeBattle;
    /** The battle's kind for the overlay ({@link Overlay#battle}), from its first round's start; −1 before. */
    private static int battleKind = -1;

    /** Nearer than this to where we stand, along the body, we do not move back: the rest is the body's turning. */
    private static final double SHIELD_HOME_SLACK = 0.05;
    /** How far ahead a retreat must stay inside the field, and by how much. */
    private static final double RETREAT_LOOKAHEAD = 60;
    private static final double RETREAT_MARGIN = 30;

    private World world;
    private Field field;
    private OrbitMovement movement;
    private MeleeMovement melee;
    private final MeleeRadar meleeRadar = new MeleeRadar();
    /**
     * Our robot-turns in this battle's team rounds, ours and our teammates' as each robot sees them, and those with
     * {@link MeleeMovement#GANG_SIZE} or more enemies within {@link MeleeMovement#GANG_RADIUS} (statics last for the
     * battle). In recorded battles (2026-10-16) the teams that gang held one of our robots so 12–27 % of the time,
     * the others under 1 %.
     */
    private static long teamRobotTurns;
    private static long gangedRobotTurns;
    /** The movement's gang defence is on once this share of our robot-turns was ganged, over at least so many. */
    private static final double GANG_SHARE = 0.04;
    private static final long GANG_MIN_TURNS = 200;
    /** The enemies each teammate's radar watches ({@link TeamRadar}); null outside a team. */
    private TeamRadar teamRadar;
    /** The radar's turn commanded in this turn, mathematical; NaN before the first. */
    private double radarTurn = Double.NaN;
    /** Our teammates alive this round; 0 outside a team. */
    private int teammatesAlive;
    private GuessFactorGun gun;
    private MirrorAim mirror;
    private MeleeGun meleeGun;
    /** Whether the gun spins along with the radar this turn ({@link #FAST_FIRST_SWEEP}). */
    private boolean sweeping;
    /** The melee gun's aim planned for the next turn's shot (mathematical), and the turn it was planned in. */
    private double meleeAim;
    private long meleeAimTime = -1;
    private WaveSurfer surfer;
    private Shield shield;
    /** The enemy we shielded against in the last turn, or null. */
    private String shieldedEnemy;

    /** Where we stand while shielding; NaN until the round's first shielding turn. */
    private double homeX = Double.NaN;
    private double homeY = Double.NaN;
    private Random aimSpread;
    /**
     * Where in the target's escape range the random gun aims: drawn once per bullet and held until the bullet fires,
     * so that the turret reaches it in the turns before the gun is cool. A draw in every turn would make the turret
     * chase it, and the bullets would fire where it happened to fall within the turret's reach.
     */
    private Random randomAim;
    private double randomDraw;
    private final Trace trace = new Trace();
    /** Our bullets' waves for the overlay, fed only while the robot paints. */
    private final ShotTrails shotTrails = new ShotTrails();
    /** Where we and the enemies have been, for the overlay, fed only while the robot paints. */
    private final PositionTrails positionTrails = new PositionTrails();
    /** The turn of the last {@link #onPaint}: Robocode paints before the robot acts, so in this turn we paint. */
    private long paintedAt = -1;
    /**
     * The special states of the last turn, for the overlay's info layer ({@link #stateView}): shooting the enemy's
     * bullets down, the surfer's ways against a robot that drives at us, the orbit with no wave to surf, ramming a
     * disabled enemy, the energy race's power.
     */
    private boolean shieldState;
    private boolean antiRamState;
    private boolean orbitState;
    private boolean ramState;
    private boolean raceState;
    /** The melee gun's last aim while painting, for the overlay; shown for {@link #MELEE_AIM_SHOWN} turns. */
    private Trace.MeleeAimView meleeAimView;
    /** Our last bullet's target and the gun that aimed it, for the overlay. */
    private String lastShotTarget;
    private String lastShotGun;
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
    /** Our scans of enemies in this turn and the bullets we fired in it, for this turn's {@link TeamPacket}. */
    private final List<EnemySighting> sightingsToShare = new ArrayList<>();
    private final List<TeamShot> shotsToShare = new ArrayList<>();
    /** The events of this turn that changed an enemy's energy, for this turn's {@link TeamPacket}. */
    private final List<TeamEvent> eventsToShare = new ArrayList<>();
    /**
     * In a team battle, the team's scans of the enemies and the events that changed their energy, which the enemies'
     * fire detectors take a turn late, when the teammates' reports of the turn are in; null outside a team battle.
     */
    private TeamFeed feed;
    /** Where {@link #feed} hands each turn: to the surfer's fire detectors. */
    private final TeamFeed.Sink detection = new TeamFeed.Sink() {
        @Override
        public void zapped(long time) {
            surfer.zapped();
        }

        @Override
        public void event(TeamEvent e) {
            switch (e.kind()) {
                case HIT_BY_US -> surfer.ourBulletHit(e.enemy(), e.power());
                case HIT_US -> surfer.enemyHit(e.enemy(), e.power());
                case RAM -> surfer.rammed(e.enemy());
            }
        }

        @Override
        public void scanned(EnemySighting s) {
            surfer.energySeen(s.name(), s.time(), s.x(), s.y(), s.velocity(), s.energy(), getTime());
        }
    };
    /** Where this turn's commands take us by the end of the next turn, {x, y}; NaN before the first plan. */
    private double[] next = {Double.NaN, Double.NaN};

    @Override
    public void run() {
        if (battleKind < 0) {
            battleKind = getTeammates() != null ? Overlay.TEAM : getOthers() > 1 ? Overlay.MELEE : Overlay.DUEL;
        }
        world = new World();
        field = new Field(getBattleFieldWidth(), getBattleFieldHeight());
        movement = new OrbitMovement(field, new Random(roundSeed()));
        melee = new MeleeMovement(field, new Random(roundSeed() * 7 + 3));
        melee.logging(logsMovement());
        teammatesAlive = getTeammates() == null ? 0 : getTeammates().length;
        feed = getTeammates() == null ? null : new TeamFeed();
        teamRadar = getTeammates() == null ? null : new TeamRadar();
        gun = new GuessFactorGun(field);
        mirror = new MirrorAim(field);
        meleeGun = new MeleeGun(field, MELEE_MEMORY,
                getTeammates() == null ? MeleeGunParameters.DEFAULT : MeleeGunParameters.TEAM);
        aimSpread = new Random(~roundSeed());
        randomAim = new Random(roundSeed() * 31 + 17);
        randomDraw = randomAim.nextDouble();
        surfer = new WaveSurfer(field, getGunCoolingRate());
        if (getTeammates() != null) {
            surfer.teamBattle();
        }
        shield = new Shield(field, getGunCoolingRate());
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

    private void act() {
        long now = getTime();
        next = new double[] {Double.NaN, Double.NaN};
        shieldState = false;
        antiRamState = false;
        orbitState = false;
        ramState = false;
        raceState = false;
        if (energyChecked != now) {
            checkInactivity(now);
        }
        surfer.ourState(now, getX(), getY(), Angles.fromRobocode(getHeadingRadians()), getVelocity());
        shield.ourState(now, getX(), getY(), Angles.fromRobocode(getHeadingRadians()), getVelocity());
        if (feed != null) {
            // The teammates' reports of the last turn are in; with none alive, ours of this turn are all there is.
            feed.flush(teammatesAlive > 0 ? now - 1 : now, detection);
        }
        surfer.update(now);
        mirror.ours(now, getX(), getY());
        Optional<EnemyState> target = world.closestEnemy(getX(), getY(), now, MAX_TARGET_AGE);
        steerRadar(target.orElse(null), now);
        if (target.isEmpty()) {
            gun.update(now, getX(), getY(), null);
            logPassedWaves();
            search();
            return;
        }
        EnemyState t = target.get();
        if (getOthers() == 1 && t.time() == now) {
            mirror.scanned(t.name(), now, t.x(), t.y());
        }
        // For the waves of the next scans: the surfer learns only from shots fired while we surf.
        surfer.learning(!shielding(t.name()));
        shieldedEnemy = shielding(t.name()) ? t.name() : null;
        if (shielding(t.name())) {
            shieldState = true;
            shieldAct(t, now);
            return;
        }
        if (!distanceChosen) {
            distanceChosen = true;
            if (getOthers() == 1 && hitsRarely(t.name())) {
                surfer.preferredDistance(NEAR_DISTANCE);
            }
        }
        next = move(t, now);
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
                    getTime(), getRoundNum());
            // The teammates plan the radars with this report in the next turn, and so do we.
            teamRadar.reported(new TeamRadar.Watcher(getName(), nextX, nextY, nextRadar, getTime() + 1));
            try {
                broadcastMessage(new TeamPacket(me, List.copyOf(sightingsToShare), List.copyOf(shotsToShare),
                        List.copyOf(eventsToShare)));
            } catch (IOException ex) {
                out.println("Broadcast failed: " + ex.getMessage());
            }
        }
        sightingsToShare.clear();
        shotsToShare.clear();
        eventsToShare.clear();
    }

    /**
     * Once a turn, before the turn's scans: an unexplained loss of exactly the inactivity penalty means the engine took
     * it from every robot, the enemies too, which their fire detectors must not take for a shot.
     */
    private void checkInactivity(long now) {
        if (!Double.isNaN(expectedEnergy) && !hitWall && energyChecked == now - 1
                && Math.abs(getEnergy() - (expectedEnergy - Inactivity.ZAP)) < 1e-6) {
            if (feed != null) {
                feed.zapped(now);
            } else {
                surfer.zapped();
            }
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
     * Whether we shoot this enemy's bullets down instead of surfing them: in a duel, until its trial fails; never in a
     * battle that started as a melee.
     */
    private boolean shielding(String name) {
        meleeBattle |= getOthers() > 1;
        return SHIELD && getOthers() == 1 && !meleeBattle && !SHIELD_DROPPED.contains(name);
    }

    /**
     * One of the enemy's bullets hit us while we shielded ({@code damage}), we shot one down, it fired, or it won a
     * round: the trial's account.
     */
    private void shieldTrial(String name, double damage, boolean down, boolean shot, boolean lost) {
        double[] trial = SHIELD_TRIALS.get(name);
        if (trial == null) {
            trial = new double[5];
            SHIELD_TRIALS.put(name, trial);
        }
        trial[0] += damage;
        trial[1] += shot ? 1 : 0;
        trial[2] += lost ? 1 : 0;
        trial[3] += down ? 1 : 0;
        trial[4] += damage > 0 ? 1 : 0;
        double ended = trial[3] + trial[4];
        boolean failing = ended >= SHIELD_FAIL_BULLETS && trial[3] < SHIELD_FAIL_DOWN_SHARE * ended
                || trial[3] == 0 && trial[4] >= SHIELD_FAIL_HITS;
        if ((trial[0] > SHIELD_TRIAL_DAMAGE + SHIELD_TRIAL_RATE * trial[1] || trial[2] >= SHIELD_TRIAL_ROUNDS
                || failing) && SHIELD_DROPPED.add(name)) {
            setDebugProperty("shieldTrial", String.format(Locale.ROOT,
                    "dropped damage %.1f shots %.0f lost %.0f down %.0f hit %.0f", trial[0], trial[1], trial[2],
                    trial[3], trial[4]));
        }
    }

    @Override
    public void onDeath(DeathEvent e) {
        if (shieldedEnemy != null && getOthers() > 0) {
            shieldTrial(shieldedEnemy, 0, false, false, true);
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
                surfer.ourBulletFired(bullet, now, bullet.getX(), bullet.getY(),
                        Angles.fromRobocode(bullet.getHeadingRadians()), bullet.getPower());
                shield.fired(now);
                fired = bullet.getPower();
            }
        }
        String event = shield.takeEvent();
        if (event != null) {
            setDebugProperty("shield", event);
        }
        double bearing = Angles.bearing(getX(), getY(), target.x(), target.y());
        double heading = Angles.fromRobocode(getHeadingRadians());
        if (finishesByRamming(target) && !shield.hasTargets()) {
            gun.update(now, getX(), getY(), null);
            setAhead(0);
            double gunHeading = Angles.fromRobocode(getGunHeadingRadians());
            double within = Math.atan2(RobotMotion.HALF_SIZE / 2.0, target.distanceTo(getX(), getY()));
            if (fired == 0 && getGunHeat() == 0 && getEnergy() > Rules.MIN_BULLET_POWER
                    && Math.abs(Angles.normalizeRelative(bearing - gunHeading)) < within) {
                paid(setFireBullet(Rules.MIN_BULLET_POWER));
            }
            setTurnGunRightRadians(Angles.robocodeTurn(gunHeading, bearing));
            return;
        }
        double bodyTurn;
        double[] moves;
        if (target.distanceTo(getX(), getY()) < SHIELD_MIN_DISTANCE + SHIELD_DISTANCE_PER_SPEED
                * Rules.getBulletSpeed(shield.nextPower(target.name(), SHIELD_FIRST_POWER))) {
            double away = retreatHeading(target);
            bodyTurn = Angles.robocodeTurn(heading, away);
            double distance = 100;
            if (Math.abs(bodyTurn) > Math.PI / 2) {
                bodyTurn = Angles.robocodeTurn(heading, away + Math.PI);
                distance = -distance;
            }
            moves = new double[] {distance};
            homeX = Double.NaN;
        } else {
            if (Double.isNaN(homeX) || Math.hypot(getX() - homeX, getY() - homeY) > SHIELD_HOME_DRIFT) {
                homeX = getX();
                homeY = getY();
            }
            bodyTurn = Angles.robocodeTurn(heading, bearing + Math.PI / 2);
            if (Math.abs(bodyTurn) > Math.PI / 2) {
                bodyTurn = Angles.robocodeTurn(heading, bearing - Math.PI / 2);
            }
            moves = shieldMoves(bodyTurn);
        }
        setTurnRightRadians(bodyTurn);
        if (fired == 0 && shield.sinceLastShot(now) > SHIELD_IDLE_TURNS) {
            boolean shot = fire(target, now);
            gun.update(now, getX(), getY(), shot ? target.name() : null);
            setAhead(moves[0]);
            aim(target, now, getX(), getY());
            return;
        }
        gun.update(now, getX(), getY(), null);
        EngineRobot me = ourEngineState();
        me.adjustGunForBodyTurn = true;
        me.adjustRadarForGunTurn = true;
        me.adjustRadarForBodyTurn = true;
        if (fired > 0) {
            RobotMotion.fire(me, fired);
        }
        ShieldPlanner.Plan plan = shield.plan(me, now, moves, bodyTurn, Math.min(Rules.MAX_BULLET_POWER,
                getEnergy() - fired - Rules.MIN_BULLET_POWER));
        if (plan == null) {
            setAhead(moves[0]);
            setTurnGunRightRadians(Angles.robocodeTurn(Angles.fromRobocode(getGunHeadingRadians()), bearing));
        } else {
            setAhead(shield.isCommitted(now) ? plan.distance() : moves[0]);
            setTurnGunRightRadians(plan.gunTurn());
        }
    }

    /**
     * The distances we may set this turn while shielding, with {@code bodyTurn} set: back to where we stand, or to
     * {@link ShieldPlanner#STEPS} to either side of it along the body, which this turn's turn has set across the line
     * to the enemy.
     */
    private double[] shieldMoves(double bodyTurn) {
        EngineRobot turned = ourEngineState();
        turned.bodyTurnRemaining = bodyTurn;
        RobotMotion.move(turned, (int) field.width(), (int) field.height(), getGunCoolingRate());
        double h = Angles.fromRobocode(turned.bodyHeading);
        double home = (homeX - turned.x) * Math.cos(h) + (homeY - turned.y) * Math.sin(h);
        if (Math.abs(home) < SHIELD_HOME_SLACK) {
            // Back from a step aside, or only turned: we stand here now.
            home = 0;
            homeX = getX();
            homeY = getY();
        }
        double[] moves = new double[ShieldPlanner.STEPS.length];
        for (int i = 0; i < moves.length; i++) {
            moves[i] = home + ShieldPlanner.STEPS[i];
        }
        return moves;
    }

    /**
     * Straight away from the target, or the nearest heading to it that keeps us inside the field: moving along the
     * line to the enemy changes little in the angles its gun learns.
     */
    private double retreatHeading(EnemyState target) {
        double away = Angles.bearing(target.x(), target.y(), getX(), getY());
        for (double turn = 0; turn <= Math.PI / 2; turn += 0.1) {
            for (int side = 1; side >= -1; side -= 2) {
                double h = away + side * turn;
                double x = getX() + RETREAT_LOOKAHEAD * Math.cos(h);
                double y = getY() + RETREAT_LOOKAHEAD * Math.sin(h);
                if (x > RETREAT_MARGIN && x < field.width() - RETREAT_MARGIN && y > RETREAT_MARGIN
                        && y < field.height() - RETREAT_MARGIN) {
                    return h;
                }
            }
        }
        return away;
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
        for (GunModel.Pass pass : gun.passed()) {
            setDebugProperty(GunLog.PASS, GunLog.line(pass));
        }
    }

    private void steerRadar(EnemyState target, long now) {
        int enemies = getOthers() - teammatesAlive;
        double radar = Angles.fromRobocode(getRadarHeadingRadians());
        if (enemies > 1) {
            List<MeleeRadar.Seen> seen = new ArrayList<>();
            for (EnemyState e : world.enemies()) {
                seen.add(new MeleeRadar.Seen(e.name(), e.x(), e.y(), e.time()));
            }
            boolean gunSoon = getGunHeat() <= 2 * getGunCoolingRate();
            double turn;
            if (target == null && !MeleeRadar.spinning(seen, enemies)) {
                // No enemy seen for a while: the radar looks all round instead of where they were.
                turn = Double.POSITIVE_INFINITY;
            } else {
                List<MeleeRadar.Seen> watched = teamWatch(now, seen, enemies, target, gunSoon);
                turn = watched != null
                        ? TeamRadar.sweep(radar, TeamRadar.arc(now, getX(), getY(), watched))
                        : meleeRadar.turn(now, getX(), getY(), radar, seen, enemies,
                        target == null ? null : target.name(), gunSoon);
            }
            turnRadar(turn);
            boolean sweep = FAST_FIRST_SWEEP && MeleeRadar.spinning(seen, enemies)
                    && getGunHeat() > (MELEE_AIM_TURNS + 1) * getGunCoolingRate();
            if (sweep != sweeping) {
                setAdjustRadarForGunTurn(!sweep);
                sweeping = sweep;
            }
            if (sweeping) {
                setTurnGunRightRadians(-turn);
            }
            return;
        }
        if (enemies == 1 && teammatesAlive > 0 && target != null) {
            // The last enemy, with teammates alive: each of us keeps it in the swept arc every turn, as in a duel.
            turnRadar(TeamRadar.sweep(radar, TeamRadar.arc(now, getX(), getY(),
                    List.of(new MeleeRadar.Seen(target.name(), target.x(), target.y(), target.time())))));
            return;
        }
        if (getOthers() == 1 && target != null && now - target.time() <= 1) {
            double bearing = Angles.bearing(getX(), getY(), target.x(), target.y());
            double turn = Angles.robocodeTurn(radar, bearing);
            // Overshooting keeps the target inside the swept arc every tick.
            turnRadar(-2 * turn);
        } else {
            turnRadar(Double.NEGATIVE_INFINITY);
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
        double[] cmd = MeleeMovement.driveTo(getX(), getY(), Angles.fromRobocode(getHeadingRadians()),
                getBattleFieldWidth() / 2, getBattleFieldHeight() / 2);
        setAhead(cmd[0]);
        setTurnRightRadians(cmd[1]);
    }

    /**
     * Counts this turn's robots of ours with {@link MeleeMovement#GANG_SIZE} or more enemies within
     * {@link MeleeMovement#GANG_RADIUS}, and tells whether the enemies gang ({@link #GANG_SHARE}).
     */
    private boolean countGangs(long now) {
        List<double[]> ours = new ArrayList<>();
        ours.add(new double[] {getX(), getY()});
        for (World.Teammate t : world.recentTeammates(now, MAX_TEAMMATE_AGE)) {
            ours.add(new double[] {t.x(), t.y()});
        }
        for (double[] o : ours) {
            int near = 0;
            for (EnemyState e : world.enemies()) {
                if (e.distanceTo(o[0], o[1]) <= MeleeMovement.GANG_RADIUS) {
                    near++;
                }
            }
            teamRobotTurns++;
            gangedRobotTurns += near >= MeleeMovement.GANG_SIZE ? 1 : 0;
        }
        return teamRobotTurns >= GANG_MIN_TURNS && gangedRobotTurns >= GANG_SHARE * teamRobotTurns;
    }

    /** Commands the radar's turn, mathematical (counter-clockwise positive). */
    private void turnRadar(double turn) {
        radarTurn = turn;
        setTurnRadarRightRadians(-turn);
    }

    /**
     * The enemies this robot's radar watches under the team's plan ({@link TeamRadar}), as its world last saw them;
     * null when the melee radar decides: outside a team, until every enemy has been seen this round, while the gun's
     * target goes first before a shot, and when the plan gives this robot no enemy.
     */
    private List<MeleeRadar.Seen> teamWatch(long now, List<MeleeRadar.Seen> seen, int enemies, EnemyState target,
                                            boolean gunSoon) {
        if (teamRadar == null || teammatesAlive == 0 || MeleeRadar.spinning(seen, enemies)
                || gunSoon && target != null && now - target.time() > 1) {
            return null;
        }
        List<String> names = teamRadar.watch(now, getName());
        if (names == null || names.isEmpty()) {
            return null;
        }
        List<MeleeRadar.Seen> watched = new ArrayList<>();
        for (MeleeRadar.Seen s : seen) {
            if (names.contains(s.name())) {
                watched.add(s);
            }
        }
        return watched.isEmpty() ? null : watched;
    }

    /** Fires last turn's plan if the gun is cool and reached its aim; returns whether it fired. */
    private boolean fire(EnemyState target, long now) {
        Optional<GuessFactorGun.Plan> plan = gun.planFor(target.name(), now);
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
            surfer.ourBulletFired(bullet, now, bullet.getX(), bullet.getY(),
                    Angles.fromRobocode(bullet.getHeadingRadians()), bullet.getPower());
            boolean melee = meleeAimTime == now - 1;
            String aimedAt = melee && meleeGun.aimedAt() != null ? meleeGun.aimedAt() : target.name();
            inFlight.put(bullet, new FiredShot(aimedAt, plan.get().time(), melee));
            lastShotTarget = aimedAt;
            lastShotGun = melee ? MELEE_GUN : GuessFactorGun.gunName(plan.get().decision().gun());
            if (paintedAt == now) {
                shotTrails.fired(now, bullet.getX(), bullet.getY(), bullet.getPower(),
                        () -> bullet.isActive() ? new double[] {bullet.getX(), bullet.getY()} : null,
                        () -> world.enemy(aimedAt).map(e -> new double[] {e.x(), e.y()}).orElse(null));
            }
            if (teammatesAlive > 0) {
                shotsToShare.add(new TeamShot(getName(), bullet.getX(), bullet.getY(),
                        Angles.fromRobocode(bullet.getHeadingRadians()), bullet.getPower(), now, getRoundNum()));
            }
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
    /** Our teammates' bullets reported while we paint, for the overlay's bullet powers (layer B). */
    private final List<TeamShot> teamShotsShown = new ArrayList<>();

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
        meleeAimTime = -1;
        if (getOthers() - teammatesAlive > 1 && getGunHeat() <= MELEE_AIM_TURNS * getGunCoolingRate() + 1e-9) {
            meleeGun.tracing(paintedAt == now);
            double melee = meleeGun.aim(now + 1, nextX, nextY, Rules.getBulletSpeed(planned));
            if (paintedAt == now && meleeGun.traced() != null) {
                meleeAimView = meleeAimView(now, meleeGun.traced());
            }
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
        if (!Double.isNaN(aim) && !sweeping) {
            setTurnGunRightRadians(Angles.robocodeTurn(Angles.fromRobocode(getGunHeadingRadians()), aim));
        }
        if (paintedAt == now && plannedShot && getGunHeat() <= getGunCoolingRate() + 1e-9) {
            shotTrails.planned(tracedPlan(target, now, nextX, nextY, Rules.getBulletSpeed(planned)));
        }
    }

    /**
     * The melee gun's chance to hit someone at the bearings of the positions it predicted for the enemy it aimed at,
     * {bearings, chances}, for our wave's ticks: the other enemies' positions lie off the wave's arc (the melee aim
     * layer shows them all); null without a trace.
     */
    private static double[][] aimedOnly(MeleeGun.Traced t) {
        if (t == null) {
            return null;
        }
        int n = 0;
        for (String e : t.enemies()) {
            n += e.equals(t.aimedAt()) ? 1 : 0;
        }
        double[][] w = new double[2][n];
        for (int i = 0, k = 0; i < t.enemies().length; i++) {
            if (t.enemies()[i].equals(t.aimedAt())) {
                w[0][k] = t.bearings()[i];
                w[1][k++] = t.chance()[i];
            }
        }
        return w;
    }

    /** The melee gun's aim as the overlay shows it: the enemies by index. */
    private static Trace.MeleeAimView meleeAimView(long now, MeleeGun.Traced t) {
        List<String> names = new ArrayList<>(t.enemyX().keySet());
        names.sort(null);
        int[] enemy = new int[t.enemies().length];
        for (int i = 0; i < enemy.length; i++) {
            enemy[i] = names.indexOf(t.enemies()[i]);
        }
        double[] ex = new double[names.size()];
        double[] ey = new double[names.size()];
        for (int k = 0; k < names.size(); k++) {
            ex[k] = t.enemyX().get(names.get(k))[0];
            ey[k] = t.enemyX().get(names.get(k))[1];
        }
        return new Trace.MeleeAimView(now, t.originX(), t.originY(), t.aim(), t.xs(), t.ys(), t.density(), t.weights(),
                enemy, ex, ey, t.aimedAt() == null ? -1 : names.indexOf(t.aimedAt()));
    }

    /**
     * What the gun expected of the shot planned in this turn, for the overlay: the enemy aimed at (the melee gun's
     * choice in a melee), the guns' weights over bearings, and where head-on, linear and circular targeting aim.
     */
    private ShotTrails.Planned tracedPlan(EnemyState target, long now, double ox, double oy, double speed) {
        EnemyState aimed = target;
        double[][] weights;
        if (meleeAimTime == now) {
            String name = meleeGun.aimedAt();
            if (name != null) {
                aimed = world.enemy(name).orElse(target);
            }
            weights = aimedOnly(meleeGun.traced());
        } else {
            weights = gun.planCoverage(target.name(), TRACED_BINS);
        }
        double headOn = Angles.bearing(ox, oy, aimed.x(), aimed.y());
        EnemyState straight = new EnemyState(aimed.name(), aimed.x(), aimed.y(), aimed.heading(), aimed.velocity(), 0,
                aimed.energy(), aimed.time());
        double[] aims = new double[3];
        aims[Trace.HEAD_ON] = headOn;
        aims[Trace.LINEAR] = CircularAim.aim(ox, oy, straight, speed, now + 1, field);
        aims[Trace.CIRCULAR] = CircularAim.aim(ox, oy, aimed, speed, now + 1, field);
        return new ShotTrails.Planned(now, aimed.name(), aimed.x(), aimed.y(), headOn,
                Math.asin(RobotMotion.MAX_VELOCITY / speed), weights == null ? null : weights[0],
                weights == null ? null : weights[1], aims);
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
            raceState = true;
            HitRecord record = HIT_RECORDS.get(Names.base(target.name()));
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
        HitRecord record = HIT_RECORDS.get(Names.base(name));
        return record != null && record.ended() >= READ_MIN_BULLETS && record.rate() >= READ_HIT_RATE;
    }

    /** Whether our bullets hit this enemy rarely enough over the battle for the nearer distance. */
    private static boolean hitsRarely(String name) {
        return hitsBelow(name, NEAR_HIT_RATE);
    }

    /** Whether our bullets hit this enemy less often than {@code rate} over the battle, once enough of them ended. */
    private static boolean hitsBelow(String name, double rate) {
        HitRecord record = HIT_RECORDS.get(Names.base(name));
        return record != null && record.ended() >= NEAR_MIN_BULLETS && record.rate() < rate;
    }

    /** One of our bullets fired at {@code shot}'s target ended; it hit {@code victim}, or nobody if null. */
    private static void bulletEnded(FiredShot shot, String victim) {
        if (shot != null) {
            HIT_RECORDS.computeIfAbsent(Names.base(shot.target()), n -> new HitRecord())
                    .ended(shot.target().equals(victim));
            int[] aimed = AIMED_HITS.computeIfAbsent(shot.target(), n -> new int[2]);
            aimed[0]++;
            aimed[1] += shot.target().equals(victim) ? 1 : 0;
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
     * Sets this turn's movement and returns where it takes us by the end of the next turn: in a melee the safest place
     * ({@link MeleeMovement}); otherwise surfing the nearest enemy wave, or in a duel the wave the enemy can fire next
     * once its gun is cool, ramming a disabled enemy once none is in the air, or orbiting the target.
     */
    private double[] move(EnemyState target, long now) {
        if (getOthers() > 1) {
            if (teammatesAlive > 0) {
                melee.gangDefence(countGangs(now));
            }
            List<MeleeMovement.Robot> robots = new ArrayList<>();
            for (EnemyState e : world.enemies()) {
                robots.add(new MeleeMovement.Robot(e.x(), e.y(), e.energy(), true));
            }
            for (World.Teammate t : world.recentTeammates(now, MAX_TEAMMATE_AGE)) {
                robots.add(new MeleeMovement.Robot(t.x(), t.y(), 0, false));
            }
            melee.tracing(paintedAt == now);
            double[] cmd = melee.command(now, getX(), getY(), Angles.fromRobocode(getHeadingRadians()), getEnergy(),
                    robots, surfer, ourEngineState());
            if (melee.logLine() != null) {
                setDebugProperty(MeleeMovement.LOG, melee.logLine());
            }
            setAhead(cmd[0]);
            setTurnRightRadians(cmd[1]);
            return predictNext(cmd[0], cmd[1]);
        }
        boolean ram = finishesByRamming(target);
        ramState = ram;
        // Only for the overlay: a battle without the GUI computes nothing more.
        antiRamState = paintedAt == now && surfer.antiRam(target.name(), now);
        Optional<double[]> surf = surfer.command(ourEngineState(), now);
        if (surf.isPresent()) {
            setAhead(surf.get()[0]);
            setTurnRightRadians(surf.get()[1]);
            return predictNext(surf.get()[0], surf.get()[1]);
        }
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
        orbitState = !ram;
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
        if (energyChecked != e.getTime()) {
            checkInactivity(e.getTime());
        }
        double bearing = Angles.fromRobocode(getHeadingRadians() + e.getBearingRadians());
        double x = getX() + e.getDistance() * Math.cos(bearing);
        double y = getY() + e.getDistance() * Math.sin(bearing);
        if (isTeammate(e.getName())) {
            world.observeTeammate(new World.Teammate(e.getName(), x, y, Angles.fromRobocode(e.getHeadingRadians()),
                    e.getVelocity(), e.getEnergy(), e.getTime()));
            surfer.teammateSeen(e.getName(), e.getTime(), x, y);
            return;
        }
        EnemySighting sighting = new EnemySighting(e.getName(), x, y, Angles.fromRobocode(e.getHeadingRadians()),
                e.getVelocity(), e.getEnergy(), e.getTime(), getRoundNum());
        world.observeEnemy(sighting);
        if (teamRadar != null) {
            teamRadar.scanned(e.getName(), x, y, e.getTime());
        }
        meleeScan(sighting);
        gun.scanned(e.getName(), e.getTime(), x, y, sighting.heading(), e.getVelocity());
        shield.scanned(e.getName(), e.getTime(), x, y, sighting.heading(), e.getVelocity(), e.getEnergy());
        if (feed != null) {
            surfer.seen(e.getName(), e.getTime(), x, y, sighting.heading(), e.getVelocity(), true);
            feed.sighting(sighting);
        } else {
            Optional<DetectedShot> shot = surfer.scanned(e.getName(), e.getTime(), x, y, sighting.heading(),
                    e.getVelocity(), e.getEnergy());
            shot.ifPresent(shield::shotDetected);
            if (shot.isPresent() && shielding(e.getName())) {
                shieldTrial(e.getName(), 0, false, true, false);
            }
        }
        if (teammatesAlive > 0) {
            sightingsToShare.add(sighting);
        }
    }

    /** In a team battle, an event that changed an enemy's energy: for the fire detectors and for the teammates. */
    private void energyEvent(String enemy, TeamEvent.Kind kind, double power, long time) {
        TeamEvent event = new TeamEvent(enemy, kind, power, time, getRoundNum());
        feed.event(event);
        if (teammatesAlive > 0) {
            eventsToShare.add(event);
        }
    }

    /**
     * A scan of an enemy, ours or a teammate's, for the melee gun while more than one enemy lives; its situation
     * includes the robot closest to it, as far as we know: us, or another robot where it was seen last.
     */
    private void meleeScan(EnemySighting s) {
        int enemies = getOthers() - teammatesAlive;
        if (enemies <= 1) {
            return;
        }
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
        surfer.ourBulletEnded(e.getBullet());
        if (!isTeammate(e.getName())) {
            if (feed != null) {
                energyEvent(e.getName(), TeamEvent.Kind.HIT_BY_US, e.getBullet().getPower(), e.getTime());
            } else {
                surfer.ourBulletHit(e.getName(), e.getBullet().getPower());
            }
        }
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        expectedEnergy -= Rules.getBulletDamage(e.getPower());
        if (!isTeammate(e.getName())) {
            if (feed != null) {
                surfer.learnHit(e.getName(), e.getTime(), e.getBullet().getX(), e.getBullet().getY(),
                        Angles.fromRobocode(e.getHeadingRadians()), e.getPower());
                energyEvent(e.getName(), TeamEvent.Kind.HIT_US, e.getPower(), e.getTime());
            } else {
                surfer.hitByBullet(e.getName(), e.getTime(), e.getBullet().getX(), e.getBullet().getY(),
                        Angles.fromRobocode(e.getHeadingRadians()), e.getPower());
            }
            shield.hitByBullet(e.getName(), e.getTime(), e.getBullet().getX(), e.getBullet().getY(),
                    Angles.fromRobocode(e.getHeadingRadians()), e.getPower());
            if (shielding(e.getName())) {
                shieldTrial(e.getName(), Rules.getBulletDamage(e.getPower()), false, false, false);
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
            MELEE_BULLETS[0] += hitEnemy ? 1 : 0;
            MELEE_BULLETS[1]++;
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
            gun.shotDown(shot.target(), shot.planTime());
        }
        logMeleeEnd(shot, 'b', e.getHitBullet().getName());
        surfer.ourBulletEnded(e.getBullet());
        if (!isTeammate(e.getHitBullet().getName())) {
            surfer.bulletHitBullet(e.getHitBullet().getName(), e.getTime(), e.getHitBullet().getX(),
                    e.getHitBullet().getY(), Angles.fromRobocode(e.getHitBullet().getHeadingRadians()),
                    e.getHitBullet().getPower());
            shield.bulletHitBullet(e.getHitBullet().getName(), e.getTime(), e.getHitBullet().getX(),
                    e.getHitBullet().getY(), Angles.fromRobocode(e.getHitBullet().getHeadingRadians()),
                    e.getHitBullet().getPower());
            if (shielding(e.getHitBullet().getName())) {
                shieldTrial(e.getHitBullet().getName(), 0, true, false, false);
            }
        }
    }

    @Override
    public void onHitRobot(HitRobotEvent e) {
        expectedEnergy -= Rules.ROBOT_HIT_DAMAGE;
        if (!isTeammate(e.getName())) {
            if (feed != null) {
                energyEvent(e.getName(), TeamEvent.Kind.RAM, 0, e.getTime());
            } else {
                surfer.rammed(e.getName());
            }
        }
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        hitWall = true;
    }

    /**
     * Called only while painting is enabled for this robot in the GUI, before the robot acts: the shots planned and
     * fired from now on are traced too.
     */
    @Override
    public void onPaint(Graphics2D g) {
        if (gun == null) {
            return;
        }
        long now = getTime();
        paintedAt = now;
        Overlay.battle(battleKind);
        Overlay.duel(getOthers() - teammatesAlive == 1);
        trace.start(now, getBattleFieldWidth(), getBattleFieldHeight());
        positionTrails.ours(now, getX(), getY());
        for (EnemyState e : world.enemies()) {
            positionTrails.enemy(e.name(), e.time(), e.x(), e.y());
        }
        positionTrails.describe(now, trace);
        gun.describe(now, trace);
        surfer.describe(now, trace);
        shotTrails.describe(now, trace);
        trace.lastShot(lastShotTarget, lastShotGun);
        if (MELEE_BULLETS[1] > 0) {
            trace.meleeHitRate((double) MELEE_BULLETS[0] / MELEE_BULLETS[1]);
        }
        if (teamRadar != null) {
            trace.radar(teamRadar.describe(now, getName()));
        }
        if (getOthers() > 1) {
            trace.move(melee.traced());
        }
        if (meleeAimView != null && now - meleeAimView.time() <= MELEE_AIM_SHOWN) {
            trace.meleeAim(meleeAimView);
        }
        trace.powers(powerView(now));
        trace.info(infoView(now));
        trace.states(stateView());
        Overlay.paint(g, trace);
    }

    /**
     * Our special states in the last turn, for the info layer (painted before the robot acts, so the last turn's):
     * the shield, the anti-ram ways, the orbit, ramming, the energy race's power, the radar looking for enemies (the
     * round's first look in a melee or a team, or no enemy seen lately), and the gang defence's roles.
     */
    private Trace.StateView stateView() {
        List<String> states = new ArrayList<>();
        int role = getOthers() > 1 ? melee.pullRole() : MeleeMovement.NO_PULL;
        double[] pulled = melee.pullTarget();
        boolean[] on = {shieldState, antiRamState, orbitState, ramState, raceState, Double.isInfinite(radarTurn),
                role == MeleeMovement.GANGED, role == MeleeMovement.HELPER};
        for (int i = 0; i < on.length; i++) {
            if (on[i]) {
                states.add(Trace.STATES[i]);
            }
        }
        return new Trace.StateView(getX(), getY(), states, role == MeleeMovement.GANGED,
                role == MeleeMovement.HELPER ? pulled[0] : Double.NaN, role == MeleeMovement.HELPER ? pulled[1]
                : Double.NaN);
    }

    /**
     * Beside each enemy for the overlay: the turns until its gun is cool, our hit rate on it, its on us and the age of
     * our side's last scan of it.
     */
    private Trace.InfoView infoView(long now) {
        List<double[]> enemies = new ArrayList<>();
        for (EnemyState e : world.enemies()) {
            int[] aimed = AIMED_HITS.get(e.name());
            double rate = aimed != null && aimed[0] >= INFO_MIN_BULLETS ? (double) aimed[1] / aimed[0] : Double.NaN;
            // The scan's age with two enemies or more: a duel's radar locks.
            enemies.add(new double[] {e.x(), e.y(), surfer.coolIn(e.name(), now), rate,
                    surfer.hitsOnUsPerShot(e.name(), INFO_MIN_BULLETS), getOthers() - teammatesAlive > 1
                    ? now - e.time() : -1});
        }
        return new Trace.InfoView(enemies.toArray(new double[0][]));
    }

    /**
     * The bullets' powers for the overlay: our bullets in flight as the engine reports them; our teammates' where they
     * have flown since their shot, until they leave the field or reach a robot where we saw it last (their end we
     * learn from nobody); each enemy's last shot as fire detection saw it.
     */
    private Trace.PowerView powerView(long now) {
        List<double[]> ours = new ArrayList<>();
        for (Bullet b : inFlight.keySet()) {
            if (b.isActive()) {
                ours.add(new double[] {b.getX(), b.getY(), b.getPower()});
            }
        }
        List<World.Teammate> mates = world.recentTeammates(now, MAX_TEAMMATE_AGE);
        for (Iterator<TeamShot> it = teamShotsShown.iterator(); it.hasNext(); ) {
            TeamShot shot = it.next();
            // A bullet moves in the turn it is fired, from where its robot stood (engine-model G-03).
            double flown = Rules.getBulletSpeed(shot.power()) * (now - shot.fireTime());
            double x = shot.x() + flown * Math.cos(shot.heading());
            double y = shot.y() + flown * Math.sin(shot.heading());
            boolean ended = !field.contains(x, y, 0);
            for (EnemyState e : world.enemies()) {
                ended |= Math.abs(e.x() - x) <= Field.ROBOT_HALF_SIZE && Math.abs(e.y() - y) <= Field.ROBOT_HALF_SIZE;
            }
            for (World.Teammate t : mates) {
                ended |= !t.name().equals(shot.shooter()) && Math.abs(t.x() - x) <= Field.ROBOT_HALF_SIZE
                        && Math.abs(t.y() - y) <= Field.ROBOT_HALF_SIZE;
            }
            if (ended) {
                it.remove();
            } else {
                ours.add(new double[] {x, y, shot.power()});
            }
        }
        List<double[]> enemies = new ArrayList<>();
        for (EnemyState e : world.enemies()) {
            double power = surfer.shotPower(e.name());
            if (!Double.isNaN(power)) {
                enemies.add(new double[] {e.x(), e.y(), power});
            }
        }
        return new Trace.PowerView(ours.toArray(new double[0][]), enemies.toArray(new double[0][]));
    }

    /** A key pressed in the battle view switches a layer of the overlay ({@link Overlay#key}). */
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
        teamRadar.reported(new TeamRadar.Watcher(s.name(), s.nextX(), s.nextY(), s.nextRadarHeading(), s.time() + 1));
        surfer.teammateSeen(s.name(), s.time() + 1, s.nextX(), s.nextY());
        for (EnemySighting sighting : packet.sightings()) {
            if (world.dead(sighting.name())) {
                // Scanned in the turn it died.
                continue;
            }
            world.observeEnemy(sighting);
            teamRadar.scanned(sighting.name(), sighting.x(), sighting.y(), sighting.time());
            meleeScan(sighting);
            gun.scanned(sighting.name(), sighting.time(), sighting.x(), sighting.y(), sighting.heading(),
                    sighting.velocity());
            surfer.seen(sighting.name(), sighting.time(), sighting.x(), sighting.y(), sighting.heading(),
                    sighting.velocity(), false);
            feed.sighting(sighting);
        }
        for (TeamEvent event : packet.events()) {
            feed.event(event);
        }
        for (TeamShot shot : packet.shots()) {
            surfer.teammateFired(shot.shooter(), shot.fireTime(), shot.x(), shot.y(), shot.heading(), shot.power(),
                    e.getTime());
            if (paintedAt >= e.getTime() - 1) {
                teamShotsShown.add(shot);
            }
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
        gun.robotDied(e.getName());
        meleeGun.robotDied(e.getName());
        if (logsGun()) {
            setDebugProperty(MeleeGunLog.DEATH, e.getName());
        }
        surfer.robotDied(e.getName());
    }
}
