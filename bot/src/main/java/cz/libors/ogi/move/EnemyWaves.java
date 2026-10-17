package cz.libors.ogi.move;

import cz.libors.ogi.enemy.DetectedShot;
import cz.libors.ogi.enemy.FireDetector;
import cz.libors.ogi.enemy.Neighbours;
import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.RobotHistory;
import cz.libors.ogi.gun.TargetingFeatures;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.RobotMotion;
import cz.libors.ogi.trace.Trace;
import cz.libors.ogi.wave.BulletPath;
import cz.libors.ogi.wave.HitRange;
import cz.libors.ogi.wave.Wave;
import cz.libors.ogi.world.Names;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The enemies' waves in the air, as we learn of them: every bullet a shot could have fired in one turn.
 *
 * <p>Shots come from {@link FireDetector}, one per enemy and round, fed in turn order with what changes the enemy's
 * energy. A wave's frame is the bearing from its origin to where we were at the end of the turn before the shot,
 * which is what the enemy aimed with, and our lateral direction then. Bullets that hit us, or that one of our bullets
 * hit, teach the enemy's {@link DangerModel}, together with how we moved when it aimed ({@link TargetingFeatures} of
 * our own history), and where we were when a wave passed us teaches its flattener. A new wave gets the model's profile
 * for that situation. In a melee or a team each wave also gets the chance that its shooter aimed at us, and our
 * teammates' bullets in the air are kept, each as a wave with one known heading.
 *
 * <p>Bullet shadows: where one of our bullets in the air will cross an enemy wave, the enemy's bullets at those angles
 * would hit ours (engine-model G-04: segments of the same turn that cross always collide; a segment and the other
 * bullet's segment of the turn before collide if the engine happens to move that bullet first), so that part of a hit
 * range is safe, or half safe ({@link EnemyWave#damage}).
 *
 * <p>The duel's {@link WaveSurfer} chooses its way against these waves; in a melee {@link #meleeDanger} rates a way to
 * a destination. The enemies' fire records (their shots, their hits on us, their last power) go to the bullet power and
 * the overlay.
 *
 * <p>Danger models, ram detectors, our lateral histories and the enemies' fire records last for the battle (a
 * {@link Memory}, which the bot keeps in a static, engine-model G-22); waves and fire detectors are per round.
 */
public final class EnemyWaves {

    /** How long our bullets are followed for shadows, at most. */
    static final int MAX_SHADOW_TURNS = 150;

    /** How closely a bullet's power must match its wave's. */
    private static final double POWER_TOLERANCE = 0.01;

    /** In a melee, a way to a destination is predicted at most this many turns. */
    static final int MELEE_HORIZON = 30;

    /** In a melee, waves whose shooter aimed at us with less chance than this are left out of a way's danger. */
    static final double MIN_AIM_CHANCE = 0.1;

    /**
     * The overlay shows the danger of this many waves ({@link #describe}): those that reach us first among the waves
     * whose shooter aimed at us with at least {@link #DESCRIBED_AIM_CHANCE}.
     */
    static final int DESCRIBED = 2;

    static final double DESCRIBED_AIM_CHANCE = 0.25;

    /** The chance that a melee shooter aimed at us falls with this power of 1 + the enemies it had closer than us. */
    static final double AIM_EXPONENT = 2;

    /** How far in time another enemy's scan may lie from a shot to say where it was then. */
    private static final int AIM_SCAN_TURNS = 16;

    /** The power assumed for an enemy's next shot before its first one. */
    private static final double FIRST_POWER = 2;

    /** The prior of an enemy's hit rate on us: this many hits in {@link #PRIOR_SHOTS} shots. */
    private static final double PRIOR_HITS = 1;

    private static final double PRIOR_SHOTS = 10;

    /**
     * What we learn of each enemy's fire over the battle. The copies of one robot in an enemy team share the danger
     * models and the fire records ({@link Names#base}); the rest is kept by each robot's own name.
     */
    public static final class Memory {
        /** How each enemy aims, by base name. */
        final Map<String, DangerModel> models = new HashMap<>();
        /** Whether each enemy drives at us, by its own name. */
        final Map<String, RamDetector> ramDetectors = new HashMap<>();
        /** Our lateral velocity as each enemy saw it, by its own name: what averaged and pattern guns aim with. */
        final Map<String, LateralHistory> lateral = new HashMap<>();
        /** The power of each enemy's last wave, by its own name: what its next shot is assumed to be. */
        final Map<String, Double> lastPowers = new HashMap<>();
        /** By base name: {its shots we detected, its hits on us, its bullets ours shot down}. */
        final Map<String, int[]> fireRecords = new HashMap<>();
    }

    private final Field field;

    private final double coolingRate;

    private final Memory memory;

    private final Map<String, FireDetector> detectors = new HashMap<>();

    private final Neighbours neighbours = new Neighbours();

    private final List<EnemyWave> waves = new ArrayList<>();

    /** Waves whose bullet we learned: no longer surfed, but followed until they pass us, for the flattener. */
    private final List<EnemyWave> spent = new ArrayList<>();

    private final List<BulletPath> ourBullets = new ArrayList<>();

    /**
     * Our teammates' bullets in the air, each as the hit range of a wave whose one heading is known (the range's
     * reference): in a melee we keep out of their way.
     */
    private final List<HitRange> teamBullets = new ArrayList<>();

    /** The power of each enemy's last shot fire detection saw in this round, for the overlay. */
    private final Map<String, Double> shotPowers = new HashMap<>();

    /** Our own states at the end of recent turns: {x, y, heading, velocity}, by turn. */
    private final Map<Long, double[]> ourStates = new HashMap<>();

    private final RobotHistory ours = new RobotHistory();

    /**
     * Each enemy's scanned states of recent turns, {x, y, heading (mathematical), velocity}, by turn: where it aimed
     * from, and where a robot that drives at us starts in a prediction.
     */
    private final Map<String, Map<Long, double[]>> enemyPositions = new HashMap<>();

    /** In a team battle, our teammates' positions of recent turns, {x, y}, by turn: whom else an enemy may aim at. */
    private final Map<String, Map<Long, double[]>> teammatePositions = new HashMap<>();

    /** Whether the battle is between teams: then an enemy aims at us or at our teammates, never at its own. */
    private boolean teamBattle;

    /** Per shooter, the direction of its last wave: the fallback when our lateral velocity is 0 when it aims. */
    private final Map<String, Integer> lastDirection = new HashMap<>();

    /** Whether the waves detected from now on teach the danger model ({@link #learning(boolean)}). */
    private boolean learning = true;

    /** Waves with a memory of their own, as for a battle of one round. */
    public EnemyWaves(Field field, double coolingRate) {
        this(field, coolingRate, new Memory());
    }

    public EnemyWaves(Field field, double coolingRate, Memory memory) {
        this.field = field;
        this.coolingRate = coolingRate;
        this.memory = memory;
    }

    Field field() {
        return field;
    }

    double coolingRate() {
        return coolingRate;
    }

    /** The waves in the air whose bullet we have not learned yet: the ones to surf. */
    List<EnemyWave> inAir() {
        return waves;
    }

    /** Adds the shadows of a bullet we only imagine to every wave in the air ({@link #unshadow}). */
    void shadowAll(BulletPath b) {
        for (EnemyWave w : waves) {
            addShadows(w, b);
        }
    }

    /** Takes the shadows of the bullet {@code id} off every wave in the air. */
    void unshadow(Object id) {
        for (EnemyWave w : waves) {
            w.shadows.remove(id);
        }
    }

    /** Our state at the end of turn {@code time}; call it every turn before {@link #update}. Heading mathematical. */
    public void ourState(long time, double x, double y, double heading, double velocity) {
        ourStates.put(time, new double[] {x, y, heading, velocity});
        ourStates.remove(time - 64);
        ours.add(time, x, y, heading, velocity);
        // This turn's scans came first, so each enemy seen now has its position of this turn.
        enemyPositions.forEach((name, positions) -> {
            double[] e = positions.get(time);
            if (e != null) {
                memory.lateral.computeIfAbsent(name, n -> new LateralHistory())
                        .add(time, velocity * Math.sin(heading - Angles.bearing(e[0], e[1], x, y)));
            }
        });
    }

    /**
     * Whether the waves detected from now on teach the danger model. A robot that stands and shoots bullets down
     * (bullet shielding) sees bullets aimed at a standing target, which say nothing about how the enemy aims at a
     * surfer.
     */
    public void learning(boolean learning) {
        this.learning = learning;
    }

    /** Our scan of an enemy, heading mathematical; a shot it reveals becomes a wave, and is returned. */
    public Optional<DetectedShot> scanned(String name, long time, double x, double y, double heading,
                                          double velocity, double energy) {
        seen(name, time, x, y, heading, velocity, true);
        return energySeen(name, time, x, y, velocity, energy, time);
    }

    /**
     * Where an enemy was at the end of turn {@code time}, heading mathematical: from our scan ({@code ours}), or from a
     * teammate's, which arrives a turn later and does not feed the ram detector, whose distances are to us.
     */
    public void seen(String name, long time, double x, double y, double heading, double velocity, boolean ours) {
        neighbours.seen(name, time, x, y);
        Map<Long, double[]> positions = enemyPositions.computeIfAbsent(name, n -> new HashMap<>());
        positions.put(time, new double[] {x, y, heading, velocity});
        positions.remove(time - 64);
        if (!ours) {
            return;
        }
        // Scans come before this turn's own state (onScannedRobot runs before act()).
        double[] us = ourStates.containsKey(time) ? ourStates.get(time) : ourStates.get(time - 1);
        if (us != null) {
            double distance = Math.hypot(us[0] - x, us[1] - y);
            double approach = velocity * Math.cos(heading - Angles.bearing(x, y, us[0], us[1]));
            ramDetector(name).observe(distance, approach);
        }
    }

    /**
     * An enemy's energy at the end of turn {@code time}, where it stood; a shot it reveals becomes a wave, followed
     * against our positions from the shot up to the end of turn {@code followedTo - 1}, and is returned. Our own scan
     * passes its own turn; a team passes the turn it hands the scan on in, after our state of that turn
     * ({@link cz.libors.ogi.team.TeamFeed}).
     */
    public Optional<DetectedShot> energySeen(String name, long time, double x, double y, double velocity,
                                             double energy, long followedTo) {
        FireDetector detector = detector(name);
        if (neighbours.nearOther(name, time)) {
            detector.nearOtherRobot();
        }
        Optional<DetectedShot> shot = detector.scanned(time, x, y, velocity, energy);
        shot.ifPresent(s -> {
            shotPowers.put(name, s.power());
            addWave(s, followedTo);
        });
        return shot;
    }

    /** In a team battle: an enemy's shot was aimed at us or at our teammates ({@link #aimChance}). */
    public void teamBattle() {
        teamBattle = true;
    }

    /**
     * A teammate at the end of turn {@code time}, from our scan or its own report: it can ram an enemy out of our
     * sight, and an enemy may aim at it instead of us.
     */
    public void teammateSeen(String name, long time, double x, double y) {
        neighbours.seen(name, time, x, y);
        Map<Long, double[]> positions = teammatePositions.computeIfAbsent(name, n -> new HashMap<>());
        positions.put(time, new double[] {x, y});
        positions.remove(time - 64);
    }

    /**
     * A teammate fired a bullet in turn {@code fireTime} from ({@code x}, {@code y}) along {@code heading}
     * (mathematical); we learn it in turn {@code now}, a turn later. It is followed against our positions since the
     * shot, and in a melee our ways keep out of it ({@link #meleeDanger}).
     */
    public void teammateFired(String shooter, long fireTime, double x, double y, double heading, double power,
                              long now) {
        HitRange range = new HitRange(new Wave(shooter, x, y, fireTime, power), heading, (int) field.width(),
                (int) field.height());
        for (long t = fireTime; t < now; t++) {
            double[] s = ourStates.get(t);
            if (s != null) {
                range.observe(t, s[0], s[1]);
            }
        }
        if (!range.passed()) {
            teamBullets.add(range);
        }
    }

    public void ourBulletHit(String name, double power) {
        detector(name).ourBulletHit(power);
    }

    /** An enemy bullet of this power hit us or a teammate: the enemy regained 3 × power. */
    public void enemyHit(String name, double power) {
        detector(name).hitUs(power);
    }

    public void rammed(String name) {
        detector(name).rammed();
    }

    /**
     * The engine's inactivity penalty took 0.1 energy from every robot in this turn (engine-model G-38): call it
     * before the turn's scans. Bullets that collide damage nobody, so in a bullet shield's rounds against weak bullets
     * the robots lose less than 10 energy in 450 turns, their shots' cost alone.
     */
    public void zapped() {
        detectors.values().forEach(FireDetector::zapped);
    }

    /** An enemy bullet hit us at the end of its move in turn {@code time}; heading mathematical. */
    public void hitByBullet(String shooter, long time, double x, double y, double heading, double power) {
        detector(shooter).hitUs(power);
        learnHit(shooter, time, x, y, heading, power);
    }

    /**
     * The same, but the shooter's energy is told apart elsewhere ({@link #enemyHit}): in a team, together with the
     * teammates' hits.
     */
    public void learnHit(String shooter, long time, double x, double y, double heading, double power) {
        fireRecord(shooter)[1]++;
        learn(shooter, time, x, y, heading, power, 0);
    }

    /**
     * One of our bullets hit an enemy bullet in turn {@code time}; heading mathematical. The engine reports both
     * bullets where they were before their last move: one move back for a bullet that had moved in that turn, two for
     * one that had not moved yet (engine-model G-04).
     */
    public void bulletHitBullet(String shooter, long time, double x, double y, double heading, double power) {
        fireRecord(shooter)[2]++;
        learn(shooter, time, x, y, heading, power, 1.5);
    }

    /**
     * We fired a bullet in turn {@code time} from our position at the end of that turn; heading mathematical. It
     * shadows the enemy waves it will cross until {@link #ourBulletEnded} or until it leaves the field.
     */
    public void ourBulletFired(Object id, long time, double x, double y, double heading, double power) {
        BulletPath b = BulletPath.fired(id, time, x, y, heading, power, field, MAX_SHADOW_TURNS);
        ourBullets.add(b);
        for (EnemyWave w : waves) {
            addShadows(w, b);
        }
    }

    /** Our bullet hit a robot or a bullet: it shadows nothing more. */
    public void ourBulletEnded(Object id) {
        ourBullets.removeIf(b -> b.id().equals(id));
        for (EnemyWave w : waves) {
            w.shadows.remove(id);
        }
    }

    /**
     * The enemy's hits on us per shot over the battle, its bullets that ours shot down left out, starting from a prior
     * of {@link #PRIOR_HITS} in {@link #PRIOR_SHOTS}. Its shots still in the air at a round's end count as misses.
     */
    public double hitRateOnUs(String name) {
        int[] r = memory.fireRecords.getOrDefault(Names.base(name), new int[3]);
        return (r[1] + PRIOR_HITS) / (Math.max(0, r[0] - r[2]) + PRIOR_SHOTS);
    }

    /** The turns until the enemy's gun is cool by fire detection's model of its gun heat, −1 if we know nothing. */
    public long coolIn(String name, long now) {
        FireDetector detector = detectors.get(name);
        return detector == null ? -1 : detector.coolIn(now);
    }

    /** The power of the enemy's last shot fire detection saw in this round, NaN before its first. */
    public double shotPower(String name) {
        return shotPowers.getOrDefault(name, Double.NaN);
    }

    /**
     * The share of the enemy's shots over the battle that hit us, its bullets ours shot down left out, without a
     * prior; NaN before {@code minShots}. In a melee or a team its shots are aimed at anyone.
     */
    public double hitsOnUsPerShot(String name, int minShots) {
        int[] r = memory.fireRecords.getOrDefault(Names.base(name), new int[3]);
        int shots = Math.max(0, r[0] - r[2]);
        return shots < minShots ? Double.NaN : (double) r[1] / shots;
    }

    /** The power of the enemy's last shot in the battle, or {@link #FIRST_POWER} before its first. */
    public double lastPower(String name) {
        return memory.lastPowers.getOrDefault(name, FIRST_POWER);
    }

    private int[] fireRecord(String name) {
        return memory.fireRecords.computeIfAbsent(Names.base(name), n -> new int[3]);
    }

    /** Whether this enemy drives at us, to ram us or to fight at point blank ({@link RamDetector}). */
    public boolean drivesAtUs(String name) {
        RamDetector detector = memory.ramDetectors.get(name);
        return detector != null && detector.drivesAtUs();
    }

    /** A dead robot fires no more, but its bullets in the air still fly and hit, so its waves stay. */
    public void robotDied(String name) {
        detectors.remove(name);
        // Its waves keep their own origins; a dead robot neither rams nor marks a close fight any more.
        enemyPositions.remove(name);
        teammatePositions.remove(name);
    }

    /**
     * Advances the waves to our position at the end of turn {@code now} and drops those that have passed us; where we
     * were in each of them goes to its shooter's flattener, also in those whose bullet hit us or one of our bullets.
     */
    public void update(long now) {
        ourBullets.removeIf(b -> b.endTime() < now);
        double[] us = ourStates.get(now);
        if (us == null) {
            return;
        }
        advance(waves, now, us);
        // A gun that learns our movement learns these waves too, whatever became of their bullets.
        advance(spent, now, us);
        for (Iterator<HitRange> it = teamBullets.iterator(); it.hasNext(); ) {
            HitRange range = it.next();
            range.observe(now, us[0], us[1]);
            if (range.passed()) {
                it.remove();
            }
        }
    }

    private void advance(List<EnemyWave> list, long now, double[] us) {
        for (Iterator<EnemyWave> it = list.iterator(); it.hasNext(); ) {
            EnemyWave w = it.next();
            w.range.observe(now, us[0], us[1]);
            if (w.range.passed()) {
                if (w.range.hits() && w.learns) {
                    double middle = (w.range.minOffset() + w.range.maxOffset()) / 2;
                    model(w.wave.shooter()).visit(w.features, w.direction * middle / w.escape);
                }
                it.remove();
            }
        }
    }

    /**
     * The wave {@code shooter} can fire next turn if its gun is cool by the end of this one: from where it is now, with
     * the power of its last wave, aimed at us as we are now; null while its gun is hot. Rebuilt every turn, never kept.
     */
    EnemyWave nextWave(String shooter, long now) {
        FireDetector detector = detectors.get(shooter);
        double[] enemy = enemyAt(shooter, now);
        double[] us = ourStates.get(now);
        RobotHistory seen = ours.until(now);
        if (detector == null || enemy == null || us == null || seen.isEmpty() || seen.time() != now
                || !detector.coolAt(now)) {
            return null;
        }
        Wave wave = new Wave(shooter, enemy[0], enemy[1], now + 1,
                memory.lastPowers.getOrDefault(shooter, FIRST_POWER));
        double reference = Angles.bearing(wave.x(), wave.y(), us[0], us[1]);
        double[] features = new double[TargetingFeatures.COUNT];
        int direction = TargetingFeatures.compute(wave.x(), wave.y(), seen, wave.speed(), field,
                lastDirection.getOrDefault(shooter, 1), features);
        double[] aims = SimpleAims.aims(enemy[0], enemy[1], seen, wave.speed(), field, memory.lateral.get(shooter));
        double[] simple = new double[SimpleAims.COUNT];
        for (int g = 0; g < SimpleAims.COUNT; g++) {
            simple[g] = direction * Angles.normalizeRelative(aims[g] - reference) / wave.maxEscapeAngle();
        }
        HitRange range = new HitRange(wave, reference, (int) field.width(), (int) field.height());
        EnemyWave w = new EnemyWave(wave, range, direction, features, model(shooter).predict(features, simple),
                false);
        for (BulletPath b : ourBullets) {
            addShadows(w, b);
        }
        return w;
    }

    /** The enemy's last scanned position up to 8 turns old, or null. */
    double[] enemyAt(String name, long now) {
        Map<Long, double[]> positions = enemyPositions.get(name);
        if (positions == null) {
            return null;
        }
        for (long t = now; t >= now - 8; t--) {
            double[] p = positions.get(t);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    /**
     * A shadow's bearings as an interval of guess factors. The second end is taken relative to the first, so a
     * shadow across the bearing opposite the reference stays as narrow as it is instead of wrapping around to cover
     * the whole wave.
     */
    static double[] shadowRange(double reference, int direction, double escape, double from, double to) {
        double g1 = direction * Angles.normalizeRelative(from - reference) / escape;
        double g2 = g1 + direction * Angles.normalizeRelative(to - from) / escape;
        return new double[] {Math.min(g1, g2), Math.max(g1, g2)};
    }

    /** Adds the guess factors of {@code w} that bullet {@code b} shadows, with the chance of a collision there. */
    private void addShadows(EnemyWave w, BulletPath b) {
        List<double[]> found = new ArrayList<>();
        for (double[] angles : b.shadows(w.wave)) {
            double[] range = shadowRange(w.range.reference(), w.direction, w.escape, angles[0], angles[1]);
            found.add(new double[] {range[0], range[1], angles[2]});
        }
        if (!found.isEmpty()) {
            w.shadows.put(b.id(), found);
        }
    }

    private void addWave(DetectedShot shot, long followedTo) {
        fireRecord(shot.shooter())[0]++;
        // The enemy aimed with what it saw at the end of the turn before the shot.
        long aimed = ourStates.containsKey(shot.fireTime() - 1) ? shot.fireTime() - 1 : shot.fireTime();
        double[] us = ourStates.get(aimed);
        RobotHistory seen = ours.until(aimed);
        if (us == null || seen.isEmpty() || seen.time() != aimed) {
            return;
        }
        Wave wave = new Wave(shot.shooter(), shot.x(), shot.y(), shot.fireTime(), shot.power());
        double reference = Angles.bearing(wave.x(), wave.y(), us[0], us[1]);
        double[] features = new double[TargetingFeatures.COUNT];
        int direction = TargetingFeatures.compute(wave.x(), wave.y(), seen, wave.speed(), field,
                lastDirection.getOrDefault(shot.shooter(), 1), features);
        lastDirection.put(shot.shooter(), direction);
        memory.lastPowers.put(shot.shooter(), shot.power());
        double[] from = enemyPositions.getOrDefault(shot.shooter(), Map.of()).getOrDefault(aimed,
                new double[] {wave.x(), wave.y()});
        double[] aims = SimpleAims.aims(from[0], from[1], seen, wave.speed(), field,
                memory.lateral.get(shot.shooter()));
        double[] simple = new double[SimpleAims.COUNT];
        for (int g = 0; g < SimpleAims.COUNT; g++) {
            simple[g] = direction * Angles.normalizeRelative(aims[g] - reference) / wave.maxEscapeAngle();
        }
        HitRange range = new HitRange(wave, reference, (int) field.width(), (int) field.height());
        // Catch up on the turns since the shot.
        for (long t = shot.fireTime(); t < followedTo; t++) {
            double[] s = ourStates.get(t);
            if (s != null) {
                range.observe(t, s[0], s[1]);
            }
        }
        EnemyWave w = new EnemyWave(wave, range, direction, features, model(shot.shooter()).predict(features, simple),
                learning);
        w.aimedAtUs = aimChance(shot.shooter(), from[0], from[1], us, aimed);
        for (BulletPath b : ourBullets) {
            addShadows(w, b);
        }
        waves.add(w);
    }

    /**
     * The chance that a shooter at ({@code x}, {@code y}) aimed at us in turn {@code t}, where we were at {@code us}:
     * 1 / (1 + the robots it could aim at that it had closer than us), each where it was seen nearest to that turn
     * (the power is {@link #AIM_EXPONENT}). Most melee robots aim at the enemy closest to them: in a melee the other
     * enemies, in a team battle our teammates, never its own.
     */
    private double aimChance(String shooter, double x, double y, double[] us, long t) {
        double ours = Math.hypot(us[0] - x, us[1] - y);
        int closer = 0;
        Map<String, Map<Long, double[]>> others = teamBattle ? teammatePositions : enemyPositions;
        for (Map.Entry<String, Map<Long, double[]>> e : others.entrySet()) {
            if (e.getKey().equals(shooter)) {
                continue;
            }
            double[] p = null;
            for (int k = 0; k <= AIM_SCAN_TURNS && p == null; k++) {
                p = e.getValue().get(t - k);
                if (p == null) {
                    p = e.getValue().get(t + k);
                }
            }
            if (p != null && Math.hypot(p[0] - x, p[1] - y) < ours) {
                closer++;
            }
        }
        return 1 / Math.pow(1 + closer, AIM_EXPONENT);
    }

    /**
     * Whether a wave counts in a melee way's danger: its shooter aimed at us with a chance of
     * {@link #MIN_AIM_CHANCE} or more, and it can reach us within {@link #MELEE_HORIZON} turns wherever we drive.
     */
    private static boolean meleeRelevant(EnemyWave w, EngineRobot me, long now) {
        return w.aimedAtUs >= MIN_AIM_CHANCE && reachable(w.wave, me, now);
    }

    /**
     * In a melee, the damage we expect from the enemy waves in the air on the way to ({@code px}, {@code py}): we drive
     * straight there and stand, predicted by the engine's rules until the waves have passed, at most
     * {@link #MELEE_HORIZON} turns. Each wave counts its share of the shooter's predicted aim inside its hit range
     * times its bullet's damage, as in a duel, times the chance that the shooter aimed at us; waves whose chance is
     * below {@link #MIN_AIM_CHANCE}, and waves that cannot reach us within the horizon, are left out. A teammate's
     * bullet within the horizon counts its whole damage if the way crosses its line, else nothing: its heading is
     * known.
     *
     * @param me our state at the end of this turn, in the engine's convention, without commands
     */
    public double meleeDanger(EngineRobot me, long now, double px, double py) {
        int n = 0;
        for (EnemyWave w : waves) {
            if (meleeRelevant(w, me, now)) {
                n++;
            }
        }
        int f = 0;
        for (HitRange b : teamBullets) {
            if (reachable(b.wave(), me, now)) {
                f++;
            }
        }
        if (n + f == 0) {
            return 0;
        }
        EnemyWave[] relevant = new EnemyWave[n];
        // The enemy waves' ranges first, then the teammates' bullets'.
        HitRange[] ranges = new HitRange[n + f];
        int i = 0;
        for (EnemyWave w : waves) {
            if (meleeRelevant(w, me, now)) {
                relevant[i] = w;
                ranges[i++] = w.range.copy();
            }
        }
        for (HitRange b : teamBullets) {
            if (reachable(b.wave(), me, now)) {
                ranges[i++] = b.copy();
            }
        }
        EngineRobot r = me.copy();
        boolean open = true;
        for (long t = now + 1; t <= now + MELEE_HORIZON && open; t++) {
            Move cmd = Move.to(r.x, r.y, Angles.fromRobocode(r.bodyHeading), px, py);
            r.distanceRemaining = cmd.distance();
            r.bodyTurnRemaining = cmd.rightTurn();
            RobotMotion.move(r, (int) field.width(), (int) field.height(), coolingRate);
            open = false;
            for (HitRange range : ranges) {
                if (!range.passed()) {
                    range.observe(t, r.x, r.y);
                    open |= !range.passed();
                }
            }
        }
        double sum = 0;
        for (int k = 0; k < n; k++) {
            sum += relevant[k].aimedAtUs * relevant[k].damage(ranges[k]);
        }
        for (int k = n; k < n + f; k++) {
            // The bullet flies along the range's reference.
            if (ranges[k].minOffset() <= 0 && ranges[k].maxOffset() >= 0) {
                sum += BulletMotion.damage(ranges[k].wave().power());
            }
        }
        return sum;
    }

    /** Whether a wave can reach us within {@link #MELEE_HORIZON} turns wherever we drive. */
    private static boolean reachable(Wave wave, EngineRobot me, long now) {
        double gap = Math.hypot(me.x - wave.x(), me.y - wave.y()) - wave.radius(now);
        return gap < (wave.speed() + RobotMotion.MAX_VELOCITY) * MELEE_HORIZON + RobotMotion.HALF_SIZE;
    }

    /**
     * Matches a bullet of {@code shooter} that ended in turn {@code time} at this position to its wave, by power and
     * travelled distance (the engine reported it {@code movesBack} bullet moves short of the wave's radius, within one
     * move), and learns its guess factor.
     */
    private void learn(String shooter, long time, double x, double y, double heading, double power,
                       double movesBack) {
        for (Iterator<EnemyWave> it = waves.iterator(); it.hasNext(); ) {
            EnemyWave w = it.next();
            if (!w.wave.shooter().equals(shooter) || Math.abs(w.wave.power() - power) > POWER_TOLERANCE) {
                continue;
            }
            double travelled = Math.hypot(x - w.wave.x(), y - w.wave.y());
            double expected = w.wave.radius(time) - movesBack * w.wave.speed();
            if (Math.abs(travelled - expected) <= w.wave.speed()) {
                if (w.learns) {
                    model(shooter).learn(w.features, w.prediction, w.guessFactor(heading));
                }
                it.remove();
                spent.add(w);
                return;
            }
        }
    }

    /**
     * Adds the enemy waves in flight to the trace: the {@link #DESCRIBED} that reach us first among those aimed at us
     * with a chance of at least {@link #DESCRIBED_AIM_CHANCE} with the danger model's profile, our bullets' shadows
     * and the simple guns' aims; the others plain.
     */
    public void describe(long now, Trace trace) {
        List<EnemyWave> detailed = new ArrayList<>();
        if (!ours.isEmpty()) {
            List<EnemyWave> candidates = new ArrayList<>();
            for (EnemyWave w : waves) {
                if (w.aimedAtUs >= DESCRIBED_AIM_CHANCE && w.prediction != null && turnsToReach(w, now) > -1) {
                    candidates.add(w);
                }
            }
            candidates.sort((a, b) -> Double.compare(turnsToReach(a, now), turnsToReach(b, now)));
            detailed.addAll(candidates.subList(0, Math.min(DESCRIBED, candidates.size())));
        }
        for (EnemyWave w : waves) {
            boolean hits = w.range.hits();
            double reference = w.range.reference();
            double hitLow = hits ? reference + w.range.minOffset() : Double.NaN;
            double hitHigh = hits ? reference + w.range.maxOffset() : Double.NaN;
            double[] bearings = null;
            double[] values = null;
            boolean[] shadowed = null;
            double[] aims = null;
            if (detailed.contains(w)) {
                double[] profile = w.prediction.profile();
                bearings = new double[profile.length];
                values = profile.clone();
                shadowed = new boolean[profile.length];
                for (int i = 0; i < profile.length; i++) {
                    double gf = (i - DangerModel.BINS / 2) / (double) (DangerModel.BINS / 2);
                    bearings[i] = reference + w.direction * gf * w.escape;
                    for (List<double[]> intervals : w.shadows.values()) {
                        for (double[] s : intervals) {
                            shadowed[i] |= gf >= s[0] && gf <= s[1];
                        }
                    }
                }
                double[] simple = w.prediction.simple();
                aims = new double[] {simpleAim(w, simple, SimpleAims.HEAD_ON), simpleAim(w, simple, SimpleAims.LINEAR),
                        simpleAim(w, simple, SimpleAims.CIRCULAR)};
            }
            trace.add(new Trace.WaveView(w.wave.x(), w.wave.y(), w.wave.radius(now), true, w.wave.power(), reference,
                    w.escape, hitLow, hitHigh, bearings, values, shadowed, aims, Double.NaN, Double.NaN));
        }
    }

    /** The turns until the wave reaches our latest position, negative once it has passed it. */
    private double turnsToReach(EnemyWave w, long now) {
        return (Math.hypot(ours.x() - w.wave.x(), ours.y() - w.wave.y()) - w.wave.radius(now)) / w.wave.speed();
    }

    /** The bearing at which simple gun {@code gun} aimed along the wave, NaN if it did not. */
    private static double simpleAim(EnemyWave w, double[] simple, int gun) {
        return simple == null || gun >= simple.length || Double.isNaN(simple[gun]) ? Double.NaN
                : w.range.reference() + w.direction * simple[gun] * w.escape;
    }

    private FireDetector detector(String name) {
        return detectors.computeIfAbsent(name,
                n -> new FireDetector(n, coolingRate, (int) field.width(), (int) field.height()));
    }

    DangerModel model(String name) {
        return memory.models.computeIfAbsent(Names.base(name), n -> new DangerModel(DangerParameters.DEFAULT));
    }

    private RamDetector ramDetector(String name) {
        return memory.ramDetectors.computeIfAbsent(name, n -> new RamDetector());
    }
}
