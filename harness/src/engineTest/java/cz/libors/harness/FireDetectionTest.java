package cz.libors.harness;

import cz.libors.harness.ProbeTrace.Command;
import cz.libors.harness.recording.Recording;
import cz.libors.harness.recording.Recording.BulletTick;
import cz.libors.harness.recording.Recording.RobotTick;
import cz.libors.ogi.enemy.DetectedShot;
import cz.libors.ogi.enemy.FireDetector;
import cz.libors.ogi.enemy.Neighbours;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.RobotMotion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures {@link FireDetector} against the true bullets of the probe recordings (step 2c).
 *
 * <p>Every probe acts as an observer: its detectors get only what that probe saw (its own state, its scans and its
 * events), exactly as a bot would. Its own unexplained energy loss of 0.1 reveals the inactivity penalty. The
 * detections are matched with the true shots of each enemy: a detection matches the first unmatched shot whose fire
 * turn lies in the detection's fire-time window.
 *
 * <p>Reported per scenario: precision, recall over all shots and over clean shots (the observer scanned the shooter
 * in the fire turn and the turn after), fire-time, origin and power errors, and the causes of false and missed shots
 * as far as the recording shows them.
 */
class FireDetectionTest {

    /** A shot that really happened. */
    private record TrueShot(int shooter, long fireTime, double power, double x, double y) {
    }

    /** Totals for one group of recordings. */
    private static final class Stats {
        int shots;
        int cleanShots;
        int detections;
        int matched;
        int matchedClean;
        int exactTime;
        double originError;
        double maxOriginError;
        double powerError;
        double maxPowerError;
        final Map<String, Integer> falseCauses = new TreeMap<>();
        final Map<String, Integer> missedCauses = new TreeMap<>();
        final List<String> examples = new ArrayList<>();

        double precision() {
            return detections == 0 ? 1 : (double) matched / detections;
        }

        double recall() {
            return shots == 0 ? 1 : (double) matched / shots;
        }

        double cleanRecall() {
            return cleanShots == 0 ? 1 : (double) matchedClean / cleanShots;
        }
    }

    @Test
    void detectorFindsEnemyShots() throws IOException {
        Map<String, Stats> byScenario = new LinkedHashMap<>();
        for (ProbeTrace trace : ProbeTrace.loadAll()) {
            String scenario = trace.name.substring(0, trace.name.lastIndexOf("-s"));
            Stats stats = byScenario.computeIfAbsent(scenario, k -> new Stats());
            for (Recording.RobotInfo observer : trace.recording.robots()) {
                evaluate(trace, observer.index(), stats);
            }
        }
        System.out.println("Fire detection (observer = every probe; clean = shooter scanned in the fire turn and the "
                + "turn after):");
        byScenario.forEach((name, s) -> {
            System.out.println(format(name, s));
            s.examples.forEach(e -> System.out.println("               " + e));
        });

        // Regression guards, set from the measurement of 2026-10-08 (watch-duel 100 % / 99.9 %, watch-melee 98.3 % /
        // 92.1 %).
        Stats duel = byScenario.get("watch-duel");
        assertTrue(duel.cleanShots > 500, "Too few clean duel shots: " + duel.cleanShots);
        assertTrue(duel.precision() >= 0.99, format("watch-duel", duel));
        assertTrue(duel.cleanRecall() >= 0.99, format("watch-duel", duel));
        assertTrue(duel.exactTime == duel.matchedClean, format("watch-duel", duel));
        Stats melee = byScenario.get("watch-melee");
        assertTrue(melee.precision() >= 0.97, format("watch-melee", melee));
        assertTrue(melee.recall() >= 0.90, format("watch-melee", melee));
    }

    private static void evaluate(ProbeTrace trace, int observer, Stats stats) {
        List<Recording.RobotInfo> robots = trace.recording.robots();
        for (Recording.RoundEnd round : trace.recording.roundEnds()) {
            int r = round.round();
            Map<String, FireDetector> detectors = new HashMap<>();
            Neighbours neighbours = new Neighbours();
            // The observer's last known position of every robot: turn, x, y.
            Map<String, double[]> known = new HashMap<>();
            List<DetectedShot> detections = new ArrayList<>();
            Set<Long> scannedAt = new HashSet<>();
            for (int turn = 1; turn <= round.turns(); turn++) {
                RobotTick self = trace.tick(r, turn, observer);
                if (self.state().equals("DEAD") || trace.command(r, turn, observer) == null) {
                    continue;
                }
                List<String[]> events = trace.events(r, turn, observer);
                for (String[] e : events) {
                    switch (e[0]) {
                        case "BulletHit" -> detector(detectors, trace, e[2]).ourBulletHit(Double.parseDouble(e[4]));
                        case "HitByBullet" -> detector(detectors, trace, e[2]).hitUs(Double.parseDouble(e[3]));
                        case "HitRobot" -> detector(detectors, trace, e[2]).rammed();
                        default -> {
                        }
                    }
                }
                if (zapped(trace, r, turn, observer, events)) {
                    detectors.values().forEach(FireDetector::zapped);
                }
                for (String[] e : events) {
                    if (e[0].equals("ScannedRobot")) {
                        double absolute = self.bodyHeading() + Double.parseDouble(e[3]);
                        double distance = Double.parseDouble(e[4]);
                        known.put(e[2], new double[] {turn, self.x() + distance * Math.sin(absolute),
                                self.y() + distance * Math.cos(absolute)});
                    }
                }
                for (String[] e : events) {
                    if (e[0].equals("ScannedRobot")) {
                        double[] p = known.get(e[2]);
                        FireDetector detector = detector(detectors, trace, e[2]);
                        // As the bot does it (EnemyWaves.scanned), scan by scan in event order.
                        neighbours.seen(e[2], turn, p[1], p[2]);
                        if (neighbours.nearOther(e[2], turn)) {
                            detector.nearOtherRobot();
                        }
                        detector.scanned(turn, p[1], p[2], Double.parseDouble(e[6]), Double.parseDouble(e[7]))
                                .ifPresent(detections::add);
                        scannedAt.add(ProbeTrace.key(r, turn, trace.robotIndex(e[2])));
                    }
                }
            }
            for (Recording.RobotInfo enemy : robots) {
                if (enemy.index() != observer) {
                    match(trace, r, observer, enemy.index(), detections, scannedAt, stats);
                }
            }
        }
    }

    /**
     * The observer's own energy accounting: what it fired, what hit it, what its bullets regained, rams and its own
     * wall damage (from the engine model, as the bot can compute it from its commands). An unexplained loss of 0.1
     * is the inactivity penalty.
     */
    private static boolean zapped(ProbeTrace trace, int round, int turn, int robot, List<String[]> events) {
        RobotTick before = trace.tick(round, turn - 1, robot);
        RobotTick now = trace.tick(round, turn, robot);
        Command cmd = trace.command(round, turn - 1, robot);
        if (before == null || before.state().equals("DEAD") || cmd == null) {
            return false;
        }
        EngineRobot self = new EngineRobot();
        RobotMotionReplayTest.load(self, before);
        RobotMotionReplayTest.load(self, cmd);
        double known = cmd.fired() ? RobotMotion.fire(self, cmd.firePower()) : 0;
        self.energy = 1000;
        RobotMotion.move(self, trace.width, trace.height, trace.gunCoolingRate);
        known += 1000 - self.energy;
        for (String[] e : events) {
            switch (e[0]) {
                case "HitByBullet" -> known += BulletMotion.damage(Double.parseDouble(e[3]));
                case "BulletHit" -> known -= BulletMotion.hitBonus(Double.parseDouble(e[4]));
                case "HitRobot" -> known += RobotMotion.ROBOT_HIT_DAMAGE;
                default -> {
                }
            }
        }
        double unexplained = before.energy() - now.energy() - known;
        return Math.abs(unexplained - 0.1) < 1e-6 || (now.energy() == 0 && unexplained > 1e-6 && unexplained < 0.1);
    }

    private static FireDetector detector(Map<String, FireDetector> detectors, ProbeTrace trace, String name) {
        return detectors.computeIfAbsent(name, n -> new FireDetector(n, trace.gunCoolingRate, trace.width,
                trace.height));
    }

    private static void match(ProbeTrace trace, int round, int observer, int enemy, List<DetectedShot> all,
                              Set<Long> scannedAt, Stats stats) {
        String enemyName = trace.recording.robots().get(enemy).name();
        List<DetectedShot> detections = all.stream().filter(d -> d.shooter().equals(enemyName)).toList();
        List<TrueShot> shots = new ArrayList<>();
        for (TreeMap<Integer, BulletTick> track : trace.bulletTracks().values()) {
            BulletTick first = track.firstEntry().getValue();
            if (first.round() != round || first.owner() != enemy) {
                continue;
            }
            RobotTick watcher = trace.tick(round, first.turn(), observer);
            if (watcher.state().equals("DEAD") || trace.command(round, first.turn(), observer) == null) {
                continue;
            }
            RobotTick shooter = trace.tick(round, first.turn() - 1, enemy);
            shots.add(new TrueShot(enemy, first.turn() - 1, first.power(), shooter.x(), shooter.y()));
        }
        shots.sort((a, b) -> Long.compare(a.fireTime(), b.fireTime()));
        stats.shots += shots.size();
        stats.detections += detections.size();
        Set<TrueShot> matched = new HashSet<>();
        for (DetectedShot d : detections) {
            TrueShot best = null;
            for (TrueShot s : shots) {
                if (!matched.contains(s) && s.fireTime() >= d.earliestFireTime() && s.fireTime() <= d.latestFireTime()
                        && (best == null || Math.abs(s.fireTime() - d.fireTime())
                        < Math.abs(best.fireTime() - d.fireTime()))) {
                    best = s;
                }
            }
            if (best == null) {
                // The unexplained energy change happened after the previous scan.
                String cause = cause(trace, round, enemy, observer, d.earliestFireTime(), d.detectedAt());
                stats.falseCauses.merge(cause, 1, Integer::sum);
                if (stats.examples.size() < 6 && cause.equals("nothing visible")) {
                    stats.examples.add(trace.name + " round " + round + " observer " + observer + " false " + d
                            + "; true shots near: " + shots.stream()
                            .filter(x -> Math.abs(x.fireTime() - d.detectedAt()) < 40).toList());
                }
                continue;
            }
            matched.add(best);
            stats.matched++;
            if (d.fireTime() == best.fireTime()) {
                stats.exactTime++;
            }
            double originError = Math.hypot(d.x() - best.x(), d.y() - best.y());
            double powerError = Math.abs(d.power() - best.power());
            stats.originError += originError;
            stats.maxOriginError = Math.max(stats.maxOriginError, originError);
            stats.powerError += powerError;
            stats.maxPowerError = Math.max(stats.maxPowerError, powerError);
        }
        for (TrueShot s : shots) {
            boolean clean = scannedAt.contains(ProbeTrace.key(round, (int) s.fireTime(), enemy))
                    && scannedAt.contains(ProbeTrace.key(round, (int) s.fireTime() + 1, enemy));
            if (clean) {
                stats.cleanShots++;
                if (matched.contains(s)) {
                    stats.matchedClean++;
                }
            }
            if (!matched.contains(s)) {
                String cause = clean ? cause(trace, round, enemy, observer, s.fireTime(), s.fireTime() + 1)
                        : "not scanned in the fire turn and the turn after";
                stats.missedCauses.merge(cause, 1, Integer::sum);
            }
        }
    }

    /** What else happened to the enemy in turns (from, to], as far as the recording shows. */
    private static String cause(ProbeTrace trace, int round, int enemy, int observer, long from, long to) {
        List<String> causes = new ArrayList<>();
        for (long t = from + 1; t <= to; t++) {
            RobotTick e = trace.tick(round, (int) t, enemy);
            if (e != null && e.state().equals("HIT_WALL")) {
                causes.add("wall");
            }
            for (String[] ev : trace.events(round, (int) t, enemy, "HitRobot")) {
                if (trace.robotIndex(ev[2]) != observer) {
                    causes.add("ram with another robot");
                }
            }
        }
        for (BulletTick b : trace.robotHits()) {
            if (b.round() == round && b.turn() > from && b.turn() <= to) {
                if (b.victim() == enemy && b.owner() != observer) {
                    causes.add("hit by another robot");
                }
                if (b.owner() == enemy && b.victim() != observer) {
                    causes.add("its bullet hit another robot");
                }
            }
        }
        return causes.isEmpty() ? "nothing visible" : String.join(" + ", new TreeSet<>(causes));
    }

    private static String format(String name, Stats s) {
        return String.format(Locale.ROOT,
                "%-12s shots %5d (clean %5d)  detections %5d  precision %6.2f %%  recall %6.2f %% (clean %6.2f %%)"
                        + "  exact time %5d/%d  origin error mean %.2g max %.2g  power error mean %.2g max %.2g%n"
                        + "             false: %s%n             missed: %s",
                name, s.shots, s.cleanShots, s.detections, 100 * s.precision(), 100 * s.recall(),
                100 * s.cleanRecall(), s.exactTime, s.matched, s.matched == 0 ? 0 : s.originError / s.matched,
                s.maxOriginError, s.matched == 0 ? 0 : s.powerError / s.matched, s.maxPowerError, s.falseCauses,
                s.missedCauses);
    }
}
