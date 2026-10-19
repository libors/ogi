package cz.libors.harness.dangerbench;

import cz.libors.harness.recording.Recording;
import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.RobotHistory;
import cz.libors.ogi.gun.TargetingFeatures;
import cz.libors.ogi.move.LateralHistory;
import cz.libors.ogi.move.SimpleAims;
import cz.libors.ogi.physics.RadarScan;
import cz.libors.ogi.physics.RobotBox;
import cz.libors.ogi.wave.HitRange;
import cz.libors.ogi.wave.Wave;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The opponent's bullets of one recorded battle, as the surfer would have seen them, in the order it would have: per
 * turn first the bullets it learns from (those that hit us or one of our bullets, engine-model G-40: hit events come
 * before scans), then the waves it detects, then the waves that passed us (the surfer advances its waves when it
 * acts).
 *
 * <p>Every bullet becomes a {@link Shot}, whether it was observable or not, so the bench can score a danger model on
 * where the enemy really aimed. A shot is seen as the surfer sees a detected wave with radar lock (fire-detection.md):
 * fired in turn f − 1 from the enemy's position then, detected in turn f (its first turn in the recording), aimed
 * from our state of turn f − 2. Its features and frame come from the bot's own {@link TargetingFeatures} on our
 * {@link RobotHistory} as it was then.
 *
 * @param subject  the robot that surfs, as the recording names it
 * @param events   {@link Shot}, {@link Learn} and {@link Visit} in replay order; a {@link RoundStart} before each
 *                 round
 */
public record DangerBattle(Path file, String subject, String opponent, List<Object> events) {

    /** A round starts: the waves and our lateral direction start anew, the enemy's model does not. */
    public record RoundStart(int round) {
    }

    /**
     * An enemy bullet, detected as a wave.
     *
     * @param features    our movement as the enemy saw it when it aimed
     * @param guessFactor where the bullet really went, in the wave's frame
     * @param simple      the guess factors at which the {@link SimpleAims} would have aimed
     * @param halfWidth   half our width seen from its origin, in guess factors
     * @param hitUs       whether it hit us
     * @param where       where the wave came from and where we were when the enemy aimed
     */
    public record Shot(int id, double[] features, double guessFactor, double[] simple, double halfWidth,
                       boolean hitUs, Where where) {
    }

    /**
     * Where a shot was aimed, for reports by situation: the round, the turn it was fired in, its origin, our position
     * when the enemy aimed and our lateral velocity then (the surfer's convention: positive counter-clockwise around
     * the origin).
     */
    public record Where(int round, int fireTurn, double originX, double originY, double x, double y, double lateral) {
    }

    /** The surfer learns a bullet: it hit us or one of our bullets after its wave was detected. */
    public record Learn(int shot) {
    }

    /** A shot's wave passed us, which had been at this guess factor in it; the flattener learns that. */
    public record Visit(int shot, double guessFactor) {
    }

    private record Bullet(int firstTurn, double power, double heading, String end, int endTurn, int victim) {
    }

    /** Reads the battle of the robot whose name starts with {@code subjectPrefix} against the other robot. */
    public static DangerBattle read(Path file, String subjectPrefix) throws IOException {
        Recording recording = Recording.read(file);
        Recording.RobotInfo subject = null;
        Recording.RobotInfo opponent = null;
        for (Recording.RobotInfo robot : recording.robots()) {
            if (subject == null && robot.name().startsWith(subjectPrefix)) {
                subject = robot;
            } else {
                opponent = robot;
            }
        }
        if (subject == null || opponent == null || recording.robots().size() != 2) {
            throw new IOException(file + ": not a 1v1 battle of " + subjectPrefix + "…");
        }
        Field field = new Field(Double.parseDouble(recording.battle().get("width")),
                Double.parseDouble(recording.battle().get("height")));
        Map<Integer, Map<Integer, Recording.RobotTick>> ours = new TreeMap<>();
        Map<Integer, Map<Integer, Recording.RobotTick>> theirs = new HashMap<>();
        for (Recording.RobotTick r : recording.robotTicks()) {
            Map<Integer, Map<Integer, Recording.RobotTick>> into = r.robot() == subject.index() ? ours : theirs;
            into.computeIfAbsent(r.round(), k -> new HashMap<>()).put(r.turn(), r);
        }
        Map<Integer, Map<Integer, Bullet>> bullets = bullets(recording, opponent.index());

        List<Object> events = new ArrayList<>();
        int id = 0;
        // Our lateral velocity as the opponent saw it, for the battle, as the surfer keeps it.
        LateralHistory lateral = new LateralHistory();
        for (int round : ours.keySet()) {
            events.add(new RoundStart(round));
            Map<Integer, Recording.RobotTick> us = ours.get(round);
            Map<Integer, Recording.RobotTick> them = theirs.getOrDefault(round, Map.of());
            Map<Integer, List<Bullet>> fired = new TreeMap<>();
            Map<Integer, List<Integer>> learned = new HashMap<>();
            Map<Integer, List<Visit>> visited = new HashMap<>();
            for (Bullet b : bullets.getOrDefault(round, Map.of()).values()) {
                fired.computeIfAbsent(b.firstTurn(), k -> new ArrayList<>()).add(b);
            }
            RobotHistory history = new RobotHistory();
            int lastDirection = 1;
            int lastTurn = us.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
            for (int t = 0; t <= lastTurn; t++) {
                for (int shot : learned.getOrDefault(t, List.of())) {
                    events.add(new Learn(shot));
                }
                for (Bullet b : fired.getOrDefault(t, List.of())) {
                    Recording.RobotTick aim = us.get(t - 2);
                    Recording.RobotTick origin = them.get(t - 1);
                    Recording.RobotTick now = us.get(t);
                    if (aim == null || origin == null || now == null || now.state().equals("DEAD")) {
                        continue;
                    }
                    RobotHistory seen = history.until(t - 2);
                    Wave wave = new Wave(opponent.name(), origin.x(), origin.y(), t - 1, b.power());
                    double[] features = new double[TargetingFeatures.COUNT];
                    int direction = TargetingFeatures.compute(wave.x(), wave.y(), seen, wave.speed(), field,
                            lastDirection, features);
                    lastDirection = direction;
                    double reference = Angles.bearing(wave.x(), wave.y(), aim.x(), aim.y());
                    double escape = wave.maxEscapeAngle();
                    double gf = direction * Angles.normalizeRelative(Angles.fromRobocode(b.heading()) - reference)
                            / escape;
                    // Where the enemy aimed from: its position when it aimed, as the surfer's scan saw it.
                    Recording.RobotTick from = them.getOrDefault(t - 2, origin);
                    double[] aims = SimpleAims.aims(from.x(), from.y(), seen, wave.speed(), field, lateral);
                    double[] simple = new double[SimpleAims.COUNT];
                    for (int g = 0; g < SimpleAims.COUNT; g++) {
                        simple[g] = direction * Angles.normalizeRelative(aims[g] - reference) / escape;
                    }
                    double distance = Math.hypot(aim.x() - wave.x(), aim.y() - wave.y());
                    boolean hitUs = b.end().equals("HIT_VICTIM") && b.victim() == subject.index();
                    double lateralVelocity = aim.velocity() * Math.sin(Angles.fromRobocode(aim.bodyHeading())
                            - Angles.bearing(wave.x(), wave.y(), aim.x(), aim.y()));
                    Where where = new Where(round, t - 1, wave.x(), wave.y(), aim.x(), aim.y(), lateralVelocity);
                    events.add(new Shot(id, features, gf, simple, Field.ROBOT_HALF_SIZE / distance / escape, hitUs,
                            where));
                    // The surfer learns a bullet that ends after its wave appeared: by hitting us or our bullet.
                    boolean learns = (hitUs || b.end().equals("HIT_BULLET")) && b.endTurn() > t;
                    if (learns) {
                        learned.computeIfAbsent(b.endTurn(), k -> new ArrayList<>()).add(id);
                    }
                    // The wave passes us, also when the surfer learned its bullet before.
                    HitRange range = new HitRange(wave, reference, (int) field.width(), (int) field.height());
                    for (int p = t - 1; p <= lastTurn; p++) {
                        Recording.RobotTick at = us.get(p);
                        if (at == null || at.state().equals("DEAD")) {
                            break;
                        }
                        range.observe(p, at.x(), at.y());
                        if (p >= t && range.passed()) {
                            if (range.hits()) {
                                double middle = (range.minOffset() + range.maxOffset()) / 2;
                                visited.computeIfAbsent(p, k -> new ArrayList<>())
                                        .add(new Visit(id, direction * middle / escape));
                            }
                            break;
                        }
                    }
                    id++;
                }
                Recording.RobotTick r = us.get(t);
                if (r != null) {
                    history.add(t, r.x(), r.y(), Angles.fromRobocode(r.bodyHeading()), r.velocity());
                    Recording.RobotTick e = them.get(t);
                    // As online (EnemyWaves.ourState): only in turns our radar saw the enemy, and while we live.
                    if (e != null && !e.state().equals("DEAD") && !r.state().equals("DEAD")
                            && scanned(us.get(t - 1), r, e)) {
                        double heading = Angles.fromRobocode(r.bodyHeading());
                        lateral.add(t, r.velocity() * Math.sin(heading - Angles.bearing(e.x(), e.y(), r.x(), r.y())));
                    }
                }
                events.addAll(visited.getOrDefault(t, List.of()));
            }
        }
        return new DangerBattle(file, subject.name(), opponent.name(), List.copyOf(events));
    }

    /** Whether our radar saw the enemy in this turn, by the engine's scan (engine-model G-13). */
    private static boolean scanned(Recording.RobotTick before, Recording.RobotTick after, Recording.RobotTick enemy) {
        if (before == null) {
            return false;
        }
        boolean moved = before.bodyHeading() != after.bodyHeading() || before.gunHeading() != after.gunHeading()
                || before.radarHeading() != after.radarHeading() || before.x() != after.x() || before.y() != after.y();
        return moved && RadarScan.sees(RadarScan.sweep(after.x(), after.y(), before.radarHeading(),
                after.radarHeading()), new RobotBox(enemy.x(), enemy.y()));
    }

    /** The opponent's bullets per round and id: where they started and how they ended. */
    private static Map<Integer, Map<Integer, Bullet>> bullets(Recording recording, int owner) {
        Map<Integer, Map<Integer, Bullet>> bullets = new HashMap<>();
        for (Recording.BulletTick b : recording.bulletTicks()) {
            if (b.owner() != owner || b.bulletId() == 0) {
                continue;
            }
            Map<Integer, Bullet> round = bullets.computeIfAbsent(b.round(), k -> new HashMap<>());
            Bullet known = round.get(b.bulletId());
            boolean ended = !b.state().equals("FIRED") && !b.state().equals("MOVING");
            if (known == null) {
                round.put(b.bulletId(), new Bullet(b.turn(), b.power(), b.heading(), ended ? b.state() : "",
                        ended ? b.turn() : -1, b.victim()));
            } else if (ended && known.end().isEmpty()) {
                round.put(b.bulletId(), new Bullet(known.firstTurn(), known.power(), known.heading(), b.state(),
                        b.turn(), b.victim()));
            }
        }
        return bullets;
    }
}
