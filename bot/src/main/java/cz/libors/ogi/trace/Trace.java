package cz.libors.ogi.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * What the bot decided in the current turn and the state behind it, as its consumers see it: the overlay now, later
 * the harness and a replay viewer. Components describe themselves into it; consumers only read it. Geometry is
 * mathematical.
 */
public final class Trace {

    /** The indices of {@link WaveView#aims()}. */
    public static final int HEAD_ON = 0;
    public static final int LINEAR = 1;
    public static final int CIRCULAR = 2;

    /**
     * A wave in flight, an enemy's or one of our bullets'. Bearings are absolute (mathematical, from the wave's origin).
     *
     * @param enemy     whether an enemy fired it
     * @param power     its bullet's power (an enemy's as its energy drop showed it)
     * @param reference the bearing from the origin to the wave's target when it was aimed: a head-on aim
     * @param escape    the maximum escape angle at the bullet's speed: the target can be only within it either side
     * @param hitLow    the lowest bearing of an enemy wave that hits us as it passes, NaN while none does
     * @param hitHigh   the highest such bearing
     * @param bearings  bearings of the wave with what the aiming side expects at each ({@code values}): for an enemy
     *                  wave the share of its aim our danger model puts there, for ours the gun's weight; null for a
     *                  plain wave
     * @param values    the expectation at each bearing; only their order matters
     * @param shadowed  per bearing, whether our bullets shadow it; null if none does
     * @param aims      where head-on, linear and circular targeting aim along the wave ({@link #HEAD_ON},
     *                  {@link #LINEAR}, {@link #CIRCULAR}), NaN where unknown; null for a plain wave
     * @param bulletX   our bullet's position while it flies, NaN for an enemy wave or an ended bullet
     */
    public record WaveView(double x, double y, double radius, boolean enemy, double power, double reference,
                           double escape, double hitLow,
                           double hitHigh, double[] bearings, double[] values, boolean[] shadowed, double[] aims,
                           double bulletX, double bulletY) {
    }

    /**
     * The gun's plan for the next turn.
     *
     * @param time     the turn the plan was made in; it is the shot of the turn after
     * @param aims     each gun's aim, NaN if it had none
     * @param hitRates each gun's virtual hit rate, NaN before it was scored: the guess-factor, circular and mirror
     *                 guns are chosen by it
     * @param bulletRates each gun's hit rate over the waves with a bullet, NaN before: the anti-surfer and random guns
     *                 replace the chosen one by it, against a target that is hard to hit
     * @param bulletTries the waves with a bullet each gun was scored on
     * @param hardToHit whether the target is hard to hit, so that the anti-surfer and random guns may replace
     */
    public record GunView(String target, long time, double originX, double originY, double targetX, double targetY,
                          double reference, double escape, double power, int direction, double[] aims, int chosen,
                          String[] gunNames, double[] hitRates, int data, double[] bulletRates, int[] bulletTries,
                          boolean hardToHit) {
    }

    /**
     * The melee movement's choice in this turn ({@code MeleeMovement}).
     *
     * @param candidates every candidate destination, {x, y, risk}; the lower the risk, the safer
     * @param waved      the candidates whose way was also scored against the enemy waves, {x, y}
     * @param pullX      the robot the destinations are drawn to (the gang defence), NaN if none
     */
    public record MoveView(double x, double y, double[][] candidates, double[][] waved, double destinationX,
                           double destinationY, double pullX, double pullY) {
    }

    /**
     * The melee gun's last aim: where it predicts each enemy to be when the bullet arrives.
     *
     * @param time      the turn of the aim
     * @param aim       the heading chosen, NaN without one
     * @param xs        the predicted positions ({@code ys})
     * @param density   per position, the weight of all positions covering its bearing from the origin: where the
     *                  most lies, the gun aims
     * @param weights   per position, its own weight
     * @param enemy     per position, the index of its enemy in {@code enemyX}
     * @param enemyX    each enemy's position at its last scan ({@code enemyY})
     * @param aimedAt   the index of the enemy that weighed most at the aim, −1 if none
     */
    public record MeleeAimView(long time, double originX, double originY, double aim, double[] xs, double[] ys,
                               double[] density, double[] weights, int[] enemy, double[] enemyX, double[] enemyY,
                               int aimedAt) {
    }

    /**
     * Where we and the enemies we scanned have been ({@link PositionTrails}).
     *
     * @param ours    our positions, {x, y, age in turns}
     * @param enemies the enemies' scanned positions, {x, y, age in turns}
     */
    public record PositionView(double[][] ours, double[][] enemies) {
    }

    /**
     * What the overlay shows beside one enemy (layer I), at its last scan's position.
     *
     * @param coolIn    the turns until its gun is cool by fire detection, −1 if unknown
     * @param ourRate   the share of our bullets aimed at it that hit it over the battle, NaN before enough ended
     * @param theirRate the share of its shots that hit us over the battle, NaN before enough
     * @param scanAge   the turns since our side's last scan of it, −1: not shown
     */
    public record EnemyInfo(double x, double y, long coolIn, double ourRate, double theirRate, long scanAge) {
    }

    /** What the overlay shows beside each enemy (layer I). */
    public record InfoView(List<EnemyInfo> enemies) {
    }

    /** Our special states, in the order they are listed above us (layer I). */
    public enum State {
        /** Shooting the enemy's bullets down. */
        SHIELD("shield"),
        /** The surfer's ways against a robot that drives at us. */
        ANTI_RAM("anti-ram"),
        /** The orbit with no wave to surf. */
        LONG_ORBIT("long-orbit"),
        /** Ramming a disabled enemy. */
        RAM("ram"),
        /** The energy race's power. */
        SAVING_ENERGY("saving-energy"),
        /** The radar looking for enemies: the round's first look in a melee or a team, or no enemy seen lately. */
        SEARCHING("searching"),
        /** The gang defence draws us to a teammate. */
        GANGED("ganged"),
        /** The gang defence draws us to a ganged teammate. */
        GANG_BUDDY("gang-buddy");

        private final String label;

        State(String label) {
            this.label = label;
        }

        /** The state's name as the overlay writes it. */
        public String label() {
            return label;
        }
    }

    /**
     * Our special states in the last turn (layer I).
     *
     * @param states those that were on, in the order of {@link State}
     * @param ganged whether the gang defence draws us to a teammate: we are ganged
     * @param helpX  the ganged teammate the gang defence draws us to as its helper, NaN if none
     */
    public record StateView(double x, double y, List<State> states, boolean ganged, double helpX, double helpY) {
    }

    /** A bullet's power where it is (layer B). */
    public record PowerMark(double x, double y, double power) {
    }

    /**
     * The bullets' powers (layer B).
     *
     * @param ours    our and our teammates' bullets in flight
     * @param enemies each enemy's last shot as fire detection saw it, at the enemy's last scan
     */
    public record PowerView(List<PowerMark> ours, List<PowerMark> enemies) {
    }

    /**
     * The team's radar plan, the same on every robot: which robot watches which enemies.
     *
     * @param watchers our robots in the plan, in the plan's order
     * @param enemies  the enemies the team knows
     */
    public record RadarView(List<RadarWatcher> watchers, List<RadarEnemy> enemies) {
    }

    /**
     * One of our robots in the radar plan, as it reported itself.
     *
     * @param me           whether it is the robot that paints
     * @param radarHeading where its radar points at the end of the turn, mathematical
     * @param arcCentre    the centre of the arc its radar sweeps over its enemies, NaN if it watches none
     * @param arcHalfWidth half that arc's width
     * @param reach        the distance to the farthest enemy it watches
     */
    public record RadarWatcher(String name, double x, double y, double radarHeading, boolean me, double arcCentre,
                               double arcHalfWidth, double reach) {
    }

    /**
     * An enemy as the team last scanned it.
     *
     * @param age     turns since the team's last scan of it
     * @param watcher the index of the robot that watches it in {@link RadarView#watchers()}, −1 if none does
     */
    public record RadarEnemy(String name, double x, double y, long age, int watcher) {
    }

    /**
     * A gun plan as one line for the harness, which reads it from the debug property {@code gun} in a recording:
     * the plan's turn, whether last turn's plan was fired in this turn (1 or 0), the chosen gun's index, each gun's
     * aim, the origin, the reference, the direction, the power, the data size, each gun's virtual hit rate and the
     * target's name. Comma-separated; angles are mathematical, in radians; numbers as {@link Double#toString}.
     */
    public static String gunLine(GunView g, boolean fired) {
        StringBuilder b = new StringBuilder(256);
        b.append(g.time()).append(',').append(fired ? 1 : 0).append(',').append(g.chosen());
        for (double aim : g.aims()) {
            b.append(',').append(aim);
        }
        b.append(',').append(g.originX()).append(',').append(g.originY()).append(',').append(g.reference())
                .append(',').append(g.direction()).append(',').append(g.power()).append(',').append(g.data());
        for (double rate : g.hitRates()) {
            b.append(',').append(rate);
        }
        return b.append(',').append(g.target()).toString();
    }

    private long time;
    private double fieldWidth;
    private double fieldHeight;
    private final List<WaveView> waves = new ArrayList<>();
    private final List<GunView> guns = new ArrayList<>();
    private RadarView radar;
    private MoveView move;
    private PositionView positions;
    private MeleeAimView meleeAim;
    private PowerView powers;
    private InfoView info;
    private StateView states;
    private String lastShotTarget;
    private String lastShotGun;
    private double meleeHitRate = Double.NaN;

    /** Clears the trace for a new turn. */
    public void start(long turn, double width, double height) {
        time = turn;
        fieldWidth = width;
        fieldHeight = height;
        waves.clear();
        guns.clear();
        radar = null;
        move = null;
        positions = null;
        meleeAim = null;
        powers = null;
        info = null;
        states = null;
        lastShotTarget = null;
        lastShotGun = null;
        meleeHitRate = Double.NaN;
    }

    public long time() {
        return time;
    }

    public double fieldWidth() {
        return fieldWidth;
    }

    public double fieldHeight() {
        return fieldHeight;
    }

    public void add(WaveView wave) {
        waves.add(wave);
    }

    public void add(GunView gun) {
        guns.add(gun);
    }

    public void radar(RadarView view) {
        radar = view;
    }

    public void meleeAim(MeleeAimView view) {
        meleeAim = view;
    }

    /** The melee gun's last aim, null if none is shown. */
    public MeleeAimView meleeAim() {
        return meleeAim;
    }

    public void info(InfoView view) {
        info = view;
    }

    /** What is shown beside each enemy, null if nothing. */
    public InfoView info() {
        return info;
    }

    public void states(StateView view) {
        states = view;
    }

    /** Our special states, null if not traced. */
    public StateView states() {
        return states;
    }

    public void powers(PowerView view) {
        powers = view;
    }

    /** The bullets' powers, null if none are shown. */
    public PowerView powers() {
        return powers;
    }

    public void positions(PositionView view) {
        positions = view;
    }

    /** Where we and the enemies have been, null without trails. */
    public PositionView positions() {
        return positions;
    }

    public void move(MoveView view) {
        move = view;
    }

    /** The melee movement's choice, null outside a melee or before one was traced. */
    public MoveView move() {
        return move;
    }

    /** Our last bullet: the enemy it was fired at and the gun that aimed it ("melee" for the melee gun). */
    public void lastShot(String target, String gun) {
        lastShotTarget = target;
        lastShotGun = gun;
    }

    /** The share of the melee gun's bullets over the battle that hit an enemy; NaN before one ended. */
    public void meleeHitRate(double rate) {
        meleeHitRate = rate;
    }

    public String lastShotTarget() {
        return lastShotTarget;
    }

    public String lastShotGun() {
        return lastShotGun;
    }

    public double meleeHitRate() {
        return meleeHitRate;
    }

    public List<WaveView> waves() {
        return waves;
    }

    public List<GunView> guns() {
        return guns;
    }

    /** The team's radar plan, null outside a team battle or without a plan. */
    public RadarView radar() {
        return radar;
    }
}
