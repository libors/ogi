package cz.libors.ogi.shield;

import cz.libors.ogi.enemy.DetectedShot;
import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.wave.Wave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bullet shielding in a duel: we stand and shoot each enemy bullet down with one of ours.
 *
 * <p>Every shot the fire detector finds with an exact fire time becomes a target. Its heading is expected from the
 * enemy's {@link ShieldAim}, and each turn the first target not yet shot at gets a {@link ShieldPlanner} plan: the
 * gun turns towards it, and once the gun can fire in the next turn the plan is committed and fired then. Each enemy
 * bullet that hits us or one of ours teaches the enemy's {@code ShieldAim} where it aims.
 *
 * <p>The aim models and the powers last for the battle (a {@link Memory}, which the bot keeps in a static,
 * engine-model G-22); targets and histories are per round.
 */
public final class Shield {

    /** Turns of our states and of enemy scans kept. */
    private static final int HISTORY = 64;
    /** A target is dropped once its bullet is this far past our centre. */
    private static final double PASSED = 40;
    /** How closely a bullet's power must match its wave's. */
    private static final double POWER_TOLERANCE = 0.01;
    /** The margin we want against an enemy whose aim we have seen too few bullets of, in radians. */
    private static final double UNKNOWN_MARGIN = 1e-4;

    /** What lasts for the battle, per enemy by its own name: how it aims, and its shots' powers. */
    public static final class Memory {
        final Map<String, ShieldAim> aims = new HashMap<>();
        /** {the sum of its shots' powers, their count}. */
        final Map<String, double[]> powers = new HashMap<>();
    }

    /** An enemy bullet we mean to shoot down. */
    private static final class Target {
        final Wave wave;
        final ShieldAim.Bullet bullet;
        final double expected;
        final int model;
        /** Our distance from its origin when it was detected. */
        final double distance;
        boolean shot;
        double margin = Double.NaN;
        double power = Double.NaN;
        /** Why it was not shot at, as of the last turn it was planned for. */
        String state = "unplanned";
        /** The state of each turn it was planned for, by its first letter, for the debug properties. */
        final StringBuilder history = new StringBuilder();

        Target(Wave wave, ShieldAim.Bullet bullet, double expected, int model, double distance) {
            this.wave = wave;
            this.bullet = bullet;
            this.expected = expected;
            this.model = model;
            this.distance = distance;
        }
    }

    private final Field field;
    private final double coolingRate;
    private final Memory memory;
    /** Our states {x, y, heading, velocity, lateral direction to the enemy} at the end of recent turns. */
    private final Map<Long, double[]> ours = new HashMap<>();
    /** The last lateral direction we moved in relative to the enemy: 1 counter-clockwise, −1 clockwise. */
    private double direction = 1;
    private double[] lastEnemy;
    private double enemyEnergy = Double.NaN;
    /** Each enemy's scans {x, y, heading, velocity} of recent turns; headings mathematical. */
    private final Map<String, Map<Long, double[]>> enemies = new HashMap<>();
    private final List<Target> targets = new ArrayList<>();
    private ShieldPlanner.Plan committed;
    private Target committedTarget;
    private long committedFor = -1;
    private long lastDetected = -1;
    /** The power of the enemy's last shot this round, NaN before its first. */
    private double lastPower = Double.NaN;
    private String event;

    /** A shield with a memory of its own, as for a battle of one round. */
    public Shield(Field field, double coolingRate) {
        this(field, coolingRate, new Memory());
    }

    public Shield(Field field, double coolingRate, Memory memory) {
        this.field = field;
        this.coolingRate = coolingRate;
        this.memory = memory;
    }

    /** Our state at the end of turn {@code time}; heading mathematical. */
    public void ourState(long time, double x, double y, double heading, double velocity) {
        if (lastEnemy != null) {
            double lateral = velocity * Math.sin(heading - Angles.bearing(lastEnemy[0], lastEnemy[1], x, y));
            if (Math.abs(lateral) > 1e-9) {
                direction = Math.signum(lateral);
            }
        }
        ours.put(time, new double[] {x, y, heading, velocity, direction});
        ours.remove(time - HISTORY);
    }

    /** Our scan of an enemy; heading mathematical. */
    public void scanned(String name, long time, double x, double y, double heading, double velocity, double energy) {
        Map<Long, double[]> scans = enemies.get(name);
        if (scans == null) {
            scans = new HashMap<>();
            enemies.put(name, scans);
        }
        scans.put(time, new double[] {x, y, heading, velocity});
        scans.remove(time - HISTORY);
        lastEnemy = new double[] {x, y};
        enemyEnergy = energy;
    }

    /** A shot the fire detector found; it becomes a target if its fire time is exact and we saw both robots. */
    public void shotDetected(DetectedShot shot) {
        lastDetected = shot.detectedAt();
        lastPower = shot.power();
        double[] powers = memory.powers.get(shot.shooter());
        if (powers == null) {
            powers = new double[2];
            memory.powers.put(shot.shooter(), powers);
        }
        powers[0] += shot.power();
        powers[1]++;
        Map<Long, double[]> scans = enemies.get(shot.shooter());
        if (!shot.exactTime() || scans == null) {
            return;
        }
        double[] before = scans.get(shot.fireTime() - 1);
        double[] origin = scans.get(shot.fireTime());
        double[] us = ours.get(shot.fireTime() - 1);
        if (before == null || origin == null || us == null) {
            return;
        }
        Wave wave = new Wave(shot.shooter(), shot.x(), shot.y(), shot.fireTime(), shot.power());
        ShieldAim.Bullet bullet = ShieldAim.bullet(before, origin, us, us[4], wave.maxEscapeAngle());
        ShieldAim aim = aim(shot.shooter());
        targets.add(new Target(wave, bullet, aim.predict(bullet), aim.model(),
                Math.hypot(us[0] - shot.x(), us[1] - shot.y())));
    }

    /** The plan committed last turn for a shot in turn {@code now}. */
    public Optional<ShieldPlanner.Plan> due(long now) {
        return committedFor == now ? Optional.of(committed) : Optional.empty();
    }

    /** The plan due in turn {@code now} was fired. */
    public void fired(long now) {
        if (committedFor == now) {
            committedTarget.history.append('f');
            committedTarget.shot = true;
            committedTarget.margin = committed.margin();
            committedTarget.power = committed.power();
        }
    }

    /**
     * This turn's plan against the first target we have not shot at and can still reach, or null if there is none.
     * It is committed for the next turn if the gun will be ready then; otherwise its gun turn only prepares the shot.
     *
     * @param me       our state at the end of this turn (engine convention), the gun heat after this turn's shot, the
     *                 adjust flags set and no commands
     * @param moves    the distances we may set this turn, the preferred (standing, or back to where we stand) first
     * @param bodyTurn this turn's body turn (engine convention)
     * @param maxPower the strongest shield shot we can afford
     */
    public ShieldPlanner.Plan plan(EngineRobot me, long now, double[] moves, double bodyTurn, double maxPower) {
        committedFor = -1;
        for (Iterator<Target> it = targets.iterator(); it.hasNext(); ) {
            Target t = it.next();
            if (t.wave.radius(now) > Math.hypot(me.x - t.wave.x(), me.y - t.wave.y()) + PASSED) {
                it.remove();
            }
        }
        for (Target t : targets) {
            if (t.shot) {
                continue;
            }
            if (me.gunHeat > coolingRate + 1e-9) {
                // The gun cannot fire in the next turn: no plan yet, only the gun turned towards the shot, which
                // leaves near the line to the bullet's origin. A plan costs a fraction of a millisecond warm and
                // several cold.
                t.state = "hot";
                t.history.append('h');
                double heading = Angles.bearing(me.x, me.y, t.wave.x(), t.wave.y());
                return new ShieldPlanner.Plan(0, moves[0], bodyTurn,
                        Angles.robocodeTurn(Angles.fromRobocode(me.gunHeading), heading), Double.NaN, heading,
                        Double.NaN, -1, false, false);
            }
            double power = t.wave.power();
            // The energy race: if every shot meets its bullet, each of the enemy's shots costs it p and our answer
            // costs us q. Its energy has paid for this bullet, ours not yet for our answer, so we can answer all its
            // shots while E_enemy / p <= (E_us - q) / q, that is q <= p * E_us / (E_enemy + p).
            double target = Double.isNaN(enemyEnergy) ? power : power * me.energy / (enemyEnergy + power);
            Optional<ShieldPlanner.Plan> plan = ShieldPlanner.plan(t.wave, t.expected, me, now, moves, bodyTurn,
                    Math.min(maxPower, power), target,
                    aim(t.wave.shooter()).neededMargin(ShieldPlanner.SURE_MARGIN, UNKNOWN_MARGIN), field, coolingRate);
            if (plan.isEmpty()) {
                t.state = "unreachable";
                t.history.append('u');
                continue;
            }
            t.state = !plan.get().cool() ? "hot" : !plan.get().ready() ? "turn" : plan.get().margin() <= 0 ? "margin"
                    : "committed";
            t.history.append(t.state.charAt(0));
            if (plan.get().ready() && plan.get().margin() > 0) {
                committed = plan.get();
                committedTarget = t;
                committedFor = now + 1;
            }
            return plan.get();
        }
        return null;
    }

    /** Whether an enemy bullet we have not shot at yet is in the air. */
    public boolean hasTargets() {
        for (Target t : targets) {
            if (!t.shot) {
                return true;
            }
        }
        return false;
    }

    /** Whether the plan returned by the last {@link #plan} call is committed for the next turn. */
    public boolean isCommitted(long now) {
        return committedFor == now + 1;
    }

    /**
     * An enemy bullet hit one of ours in turn {@code time}; heading mathematical. The engine reports it where it was
     * before its last move: one move back if it had moved in that turn, two if it had not moved yet (engine-model
     * G-04).
     */
    public void bulletHitBullet(String shooter, long time, double x, double y, double heading, double power) {
        resolve(shooter, time, x, y, heading, power, 1.5, "down");
    }

    /** An enemy bullet hit us at the end of its move in turn {@code time}; heading mathematical. */
    public void hitByBullet(String shooter, long time, double x, double y, double heading, double power) {
        resolve(shooter, time, x, y, heading, power, 0, "hit");
    }

    /** The mean power of this enemy's shots over the battle, or {@code fallback} before its first. */
    public double typicalPower(String name, double fallback) {
        double[] powers = memory.powers.get(name);
        return powers == null ? fallback : powers[0] / powers[1];
    }

    /**
     * The power the enemy's next shot most likely has: its last one's this round, before that the mean over the
     * battle, or {@code fallback} before its first.
     */
    public double nextPower(String name, double fallback) {
        return Double.isNaN(lastPower) ? typicalPower(name, fallback) : lastPower;
    }

    /** Turns since the last enemy shot was detected, or {@code now} if none was this round. */
    public long sinceLastShot(long now) {
        return lastDetected < 0 ? now : now - lastDetected;
    }

    /** What happened to the last bullet resolved, once, for the debug properties; null if nothing since. */
    public String takeEvent() {
        String e = event;
        event = null;
        return e;
    }

    /**
     * Matches a bullet that ended in turn {@code time} at this position, which the engine reported {@code movesBack}
     * bullet moves short of the wave's radius (within one move), to its target, and learns its heading.
     */
    private void resolve(String shooter, long time, double x, double y, double heading, double power,
                         double movesBack, String outcome) {
        for (Iterator<Target> it = targets.iterator(); it.hasNext(); ) {
            Target t = it.next();
            double expected = t.wave.radius(time) - movesBack * t.wave.speed();
            if (!t.wave.shooter().equals(shooter) || Math.abs(t.wave.power() - power) > POWER_TOLERANCE
                    || Math.abs(Math.hypot(x - t.wave.x(), y - t.wave.y()) - expected) > t.wave.speed()) {
                continue;
            }
            ShieldAim aim = aim(shooter);
            aim.observe(t.bullet, heading);
            event = String.format(Locale.ROOT,
                    "%d %s %s model %d margin %.2e error %.2e distance %.0f power %.2f next %d %.3e share %.2f %s",
                    t.wave.fireTime(), outcome, t.shot ? "shot" : t.state, t.model, t.margin,
                    Angles.normalizeRelative(heading - t.expected), t.distance, t.power, aim.model(), aim.offset(),
                    aim.matchedShare(), t.history.length() == 0 ? "-" : t.history);
            if (t == committedTarget) {
                committedFor = -1;
            }
            it.remove();
            return;
        }
    }

    private ShieldAim aim(String name) {
        // No lambda: its first call costs milliseconds, enough to skip a turn.
        ShieldAim aim = memory.aims.get(name);
        if (aim == null) {
            aim = new ShieldAim();
            memory.aims.put(name, aim);
        }
        return aim;
    }
}
