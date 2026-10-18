package cz.libors;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.CircularAim;
import cz.libors.ogi.gun.MeleeGun;
import cz.libors.ogi.gun.VirtualGuns;
import cz.libors.ogi.move.EnemyWaves;
import cz.libors.ogi.move.MeleeMovement;
import cz.libors.ogi.physics.RobotMotion;
import cz.libors.ogi.radar.TeamRadar;
import cz.libors.ogi.team.TeamShot;
import cz.libors.ogi.trace.HitRates;
import cz.libors.ogi.trace.Overlay;
import cz.libors.ogi.trace.PositionTrails;
import cz.libors.ogi.trace.ShotTrails;
import cz.libors.ogi.trace.Trace;
import cz.libors.ogi.world.BattleKind;
import cz.libors.ogi.world.EnemyState;
import cz.libors.ogi.world.Names;
import cz.libors.ogi.world.Phase;
import cz.libors.ogi.world.World;
import robocode.Bullet;
import robocode.Rules;

import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What the debug overlay shows (bot.md "Debug overlay"), gathered for one round: the components describe themselves
 * into a {@link Trace}, this adds what only the robot knows (our special states, our bullets and their plans, the
 * melee gun's last aim, the numbers beside each enemy) and keeps the overlay's own state, and {@link Overlay} paints
 * it. Robocode paints only while painting is on for this robot in the GUI, before the robot acts: what is fed only
 * {@link #painting} costs a battle without the GUI nothing.
 */
final class OverlayFeed {

    /** The overlay shows our hit rate on an enemy once this many of our bullets aimed at it ended. */
    private static final int INFO_MIN_BULLETS = 5;
    /** The overlay shows the guess-factor gun's weight at this many guess factors. */
    private static final int TRACED_BINS = 47;
    /** The melee gun's last aim stays on the overlay this many turns: the bullet's flight, about. */
    private static final int MELEE_AIM_SHOWN = 20;
    /** The melee gun's name in the overlay. */
    private static final String MELEE_GUN = "melee";

    private final Field field;
    private final World world;
    private final VirtualGuns guns;
    private final MeleeGun meleeGun;
    private final MeleeMovement meleeMovement;
    private final EnemyWaves waves;
    /** Null outside a team. */
    private final TeamRadar teamRadar;
    private final HitRates hitRates;
    private final Trace trace = new Trace();
    /** Our bullets' waves, fed only while the robot paints. */
    private final ShotTrails shotTrails = new ShotTrails();
    /** Where we and the enemies have been, fed only while the robot paints. */
    private final PositionTrails positionTrails = new PositionTrails();
    /** The turn of the last {@link #paint}: Robocode paints before the robot acts, so in this turn we paint. */
    private long paintedAt = -1;
    /** Our special states of the last turn: painted before the robot acts, so the last turn's. */
    private final EnumSet<Trace.State> states = EnumSet.noneOf(Trace.State.class);
    /** The melee gun's last aim while painting; shown for {@link #MELEE_AIM_SHOWN} turns. */
    private Trace.MeleeAimView meleeAimView;
    /** Our last bullet's target and the gun that aimed it. */
    private String lastShotTarget;
    private String lastShotGun;
    /** Our teammates' bullets reported while we paint, for the bullet powers (layer B). */
    private final List<TeamShot> teamShots = new ArrayList<>();
    /** The turn of each teammate's last report that it painted. */
    private final Map<String, Long> paintingTeammates = new HashMap<>();
    /** Our team's size, 0 outside a team. */
    private int members;

    OverlayFeed(Field field, World world, VirtualGuns guns, MeleeGun meleeGun, MeleeMovement meleeMovement,
                EnemyWaves waves, TeamRadar teamRadar, HitRates hitRates) {
        this.field = field;
        this.world = world;
        this.guns = guns;
        this.meleeGun = meleeGun;
        this.meleeMovement = meleeMovement;
        this.waves = waves;
        this.teamRadar = teamRadar;
        this.hitRates = hitRates;
    }

    /** The trace of the last {@link #paint}, for tests. */
    Trace trace() {
        return trace;
    }

    /** Whether the robot paints in turn {@code now}: then the components keep what the overlay shows. */
    boolean painting(long now) {
        return paintedAt == now;
    }

    /** A turn starts acting: its special states start anew. */
    void turnStarted() {
        states.clear();
    }

    /** One of our special states is on in this turn. */
    void state(Trace.State state) {
        states.add(state);
    }

    /** The melee gun aimed in turn {@code now}; its trace is kept while painting. */
    void meleeAimed(long now) {
        if (painting(now) && meleeGun.traced() != null) {
            meleeAimView = meleeAimView(now, meleeGun.traced());
        }
    }

    /**
     * The shot planned in turn {@code now} from ({@code ox}, {@code oy}) at {@code speed}, while painting;
     * {@code melee}: the melee gun aimed it.
     */
    void planned(EnemyState target, long now, double ox, double oy, double speed, boolean melee) {
        if (painting(now)) {
            shotTrails.planned(tracedPlan(target, now, ox, oy, speed, melee));
        }
    }

    /** We fired this bullet at {@code aimedAt}, aimed by the melee gun or the virtual guns' {@code gun}. */
    void fired(long now, Bullet bullet, String aimedAt, boolean melee, int gun) {
        lastShotTarget = aimedAt;
        lastShotGun = melee ? MELEE_GUN : VirtualGuns.gunName(gun);
        if (painting(now)) {
            shotTrails.fired(now, bullet.getX(), bullet.getY(), bullet.getPower(),
                    () -> bullet.isActive() ? new double[] {bullet.getX(), bullet.getY()} : null,
                    () -> world.enemy(aimedAt).map(e -> new double[] {e.x(), e.y()}).orElse(null));
        }
    }

    /** We play in a team of this many robots. */
    void team(int size) {
        members = size;
    }

    /** A teammate reported its state of turn {@code time}: whether it painted then. */
    void teammateReported(String name, long time, boolean painted) {
        if (painted) {
            paintingTeammates.merge(name, time, Math::max);
        } else {
            paintingTeammates.remove(name);
        }
    }

    /** A teammate's bullet reported in turn {@code time}, kept if we painted in the turn before. */
    void teamShot(TeamShot shot, long time) {
        if (paintedAt >= time - 1) {
            teamShots.add(shot);
        }
    }

    /**
     * Paints the overlay of turn {@code now}, before the robot acts: our position ({@code x}, {@code y}), the radar's
     * last turn and our bullets in flight.
     */
    void paint(Graphics2D g, long now, double x, double y, BattleKind kind, Phase phase, int enemies, String me,
               double radarTurn, Collection<Bullet> ourBullets) {
        paintedAt = now;
        Overlay.battle(kind);
        Overlay.duel(enemies == 1);
        trace.start(now, field.width(), field.height());
        positionTrails.ours(now, x, y);
        for (EnemyState e : world.enemies()) {
            positionTrails.enemy(e.name(), e.time(), e.x(), e.y());
        }
        positionTrails.describe(now, trace);
        guns.describe(now, trace);
        waves.describe(now, trace);
        shotTrails.describe(now, trace);
        trace.lastShot(lastShotTarget, lastShotGun);
        trace.meleeHitRate(hitRates.meleeRate());
        if (teamRadar != null) {
            trace.radar(teamRadar.describe(now, me));
        }
        if (phase == Phase.MELEE || phase == Phase.TEAM_ENDGAME) {
            trace.move(meleeMovement.traced());
        }
        if (meleeAimView != null && now - meleeAimView.time() <= MELEE_AIM_SHOWN) {
            trace.meleeAim(meleeAimView);
        }
        trace.powers(powerView(now, ourBullets));
        trace.info(infoView(now, phase));
        trace.states(stateView(x, y, phase, radarTurn));
        if (members > 0) {
            trace.painters(paintersView(now, me));
        }
        Overlay.paint(g, trace);
    }

    /**
     * We and the teammates alive whose report of this turn or the last said they painted: a report reaches us a turn
     * late (engine-model G-23), and in a turn it may come after our painting.
     */
    private Trace.PaintersView paintersView(long now, String me) {
        TreeSet<Integer> painted = new TreeSet<>();
        painted.add(Names.number(me));
        paintingTeammates.forEach((name, time) -> {
            if (time >= now - 2 && !world.dead(name)) {
                painted.add(Names.number(name));
            }
        });
        return new Trace.PaintersView(Names.number(me), members,
                painted.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * Our special states in the last turn, for the info layer, with the radar looking for enemies (the round's first
     * look in a melee or a team, or no enemy seen lately) and the gang defence's roles.
     */
    private Trace.StateView stateView(double x, double y, Phase phase, double radarTurn) {
        int role = phase == Phase.MELEE || phase == Phase.TEAM_ENDGAME ? meleeMovement.pullRole()
                : MeleeMovement.NO_PULL;
        double[] pulled = meleeMovement.pullTarget();
        EnumSet<Trace.State> on = EnumSet.copyOf(states);
        if (Double.isInfinite(radarTurn)) {
            on.add(Trace.State.SEARCHING);
        }
        if (role == MeleeMovement.GANGED) {
            on.add(Trace.State.GANGED);
        }
        if (role == MeleeMovement.HELPER) {
            on.add(Trace.State.GANG_BUDDY);
        }
        return new Trace.StateView(x, y, List.copyOf(on), role == MeleeMovement.GANGED,
                role == MeleeMovement.HELPER ? pulled[0] : Double.NaN, role == MeleeMovement.HELPER ? pulled[1]
                : Double.NaN);
    }

    /**
     * Beside each enemy: the turns until its gun is cool, our hit rate on it, its on us and the age of our side's last
     * scan of it.
     */
    private Trace.InfoView infoView(long now, Phase phase) {
        List<Trace.EnemyInfo> enemies = new ArrayList<>();
        for (EnemyState e : world.enemies()) {
            // The scan's age with two enemies or more: a duel's radar locks.
            enemies.add(new Trace.EnemyInfo(e.x(), e.y(), waves.coolIn(e.name(), now),
                    hitRates.rate(e.name(), INFO_MIN_BULLETS), waves.hitsOnUsPerShot(e.name(), INFO_MIN_BULLETS),
                    phase == Phase.MELEE ? now - e.time() : -1));
        }
        return new Trace.InfoView(enemies);
    }

    /**
     * The bullets' powers: our bullets in flight as the engine reports them; our teammates' where they have flown
     * since their shot, until they leave the field or reach a robot where we saw it last (their end we learn from
     * nobody); each enemy's last shot as fire detection saw it.
     */
    private Trace.PowerView powerView(long now, Collection<Bullet> ourBullets) {
        List<Trace.PowerMark> ours = new ArrayList<>();
        for (Bullet b : ourBullets) {
            if (b.isActive()) {
                ours.add(new Trace.PowerMark(b.getX(), b.getY(), b.getPower()));
            }
        }
        List<World.Teammate> mates = world.recentTeammates(now, Ogi.MAX_TEAMMATE_AGE);
        for (Iterator<TeamShot> it = teamShots.iterator(); it.hasNext(); ) {
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
                ours.add(new Trace.PowerMark(x, y, shot.power()));
            }
        }
        List<Trace.PowerMark> enemies = new ArrayList<>();
        for (EnemyState e : world.enemies()) {
            double power = waves.shotPower(e.name());
            if (!Double.isNaN(power)) {
                enemies.add(new Trace.PowerMark(e.x(), e.y(), power));
            }
        }
        return new Trace.PowerView(ours, enemies);
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
     * What the gun expected of the shot planned in this turn: the enemy aimed at (the melee gun's choice in a melee),
     * the guns' weights over bearings, and where head-on, linear and circular targeting aim.
     */
    private ShotTrails.Planned tracedPlan(EnemyState target, long now, double ox, double oy, double speed,
                                         boolean melee) {
        EnemyState aimed = target;
        double[][] weights;
        if (melee) {
            String name = meleeGun.aimedAt();
            if (name != null) {
                aimed = world.enemy(name).orElse(target);
            }
            weights = aimedOnly(meleeGun.traced());
        } else {
            weights = guns.planCoverage(target.name(), TRACED_BINS);
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
}
