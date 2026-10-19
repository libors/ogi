package cz.libors.probe;

import robocode.Bullet;
import robocode.BulletHitBulletEvent;
import robocode.BulletHitEvent;
import robocode.BulletMissedEvent;
import robocode.MessageEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;
import robocode.SkippedTurnEvent;
import robocode.StatusEvent;
import robocode.TeamRobot;
import robocode.util.Utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Probe robot for skipped turns (engine-model G-50), for a battle without the engine's debug mode. From turn
 * {@value #FIRST_SPIN} on it computes past the turn limit in every {@value #SPIN_EVERY}th turn, so the engine skips
 * its next turns; it starts with {@value #FIRST_SPIN_MILLIS} ms and doubles that while no turn is skipped. It spins its
 * radar and fires power-3 bullets at an enemy it scanned once its gun points there; a teammate's messages reach it.
 *
 * <p>After every {@code execute()} it prints what reached it in that turn, one tab-separated line: {@code skipprobe},
 * round, time, {@code getOthers()}, the number of {@code StatusEvent}s, then lists separated by {@code |}: the skipped
 * turns, the robots whose death it was told, the robots it scanned, the team messages as {@code sender@sentTurn}; last
 * its bullets of the round: fired, ended by their {@code Bullet} objects, ended without an end event. It prints
 * rather than sets debug properties: the engine reads printed text out with every turn snapshot, while debug
 * properties travel with the commands of a turn, which arrive late after a spin.
 */
public class SkipProbe extends TeamRobot {

    private static final int FIRST_SPIN = 20;
    private static final int SPIN_EVERY = 10;
    private static final int FIRST_SPIN_MILLIS = 12;
    private static final long MAX_SPIN_NANOS = 200_000_000L;
    /** Radians between the gun and the target's bearing at which it fires. */
    private static final double AIM_TOLERANCE = 0.005;
    /** A target not scanned for this many turns is given up: its death may have been lost. */
    private static final int TARGET_AGE = 20;

    private long spinNanos = FIRST_SPIN_MILLIS * 1_000_000L;
    private boolean spun;
    private int statuses;
    private final List<Long> skipped = new ArrayList<>();
    private final List<String> deaths = new ArrayList<>();
    private final List<String> scans = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    /** A bullet equals the one its end event carries: {@code Bullet} compares by id. */
    private final Set<Bullet> fired = new HashSet<>();
    private final Set<Bullet> endEvents = new HashSet<>();
    private String target;
    private double targetBearing;
    private long targetTime;

    @Override
    public void run() {
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        while (true) {
            report();
            if (spun && skipped.isEmpty() && spinNanos < MAX_SPIN_NANOS) {
                spinNanos *= 2;
            }
            spun = false;
            clear();
            long now = getTime();
            if (now >= FIRST_SPIN && now % SPIN_EVERY == 0) {
                long until = System.nanoTime() + spinNanos;
                while (System.nanoTime() < until) {
                    // Past the turn limit: the engine skips the next turns.
                }
                spun = true;
            }
            aimAndFire();
            setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
            execute();
        }
    }

    private void aimAndFire() {
        if (target == null || getTime() - targetTime > TARGET_AGE) {
            target = null;
            return;
        }
        double turn = Utils.normalRelativeAngle(targetBearing - getGunHeadingRadians());
        setTurnGunRightRadians(turn);
        if (Math.abs(turn) < AIM_TOLERANCE && getGunHeat() == 0) {
            Bullet b = setFireBullet(3);
            if (b != null) {
                fired.add(b);
            }
        }
    }

    private void report() {
        int ended = 0;
        int endedSilently = 0;
        for (Bullet b : fired) {
            if (!b.isActive()) {
                ended++;
                endedSilently += endEvents.contains(b) ? 0 : 1;
            }
        }
        StringJoiner line = new StringJoiner("\t");
        line.add("skipprobe").add(Integer.toString(getRoundNum())).add(Long.toString(getTime()))
                .add(Integer.toString(getOthers())).add(Integer.toString(statuses)).add(join(skipped))
                .add(join(deaths)).add(join(scans)).add(join(messages))
                .add(fired.size() + "," + ended + "," + endedSilently);
        out.println(line);
    }

    private void clear() {
        statuses = 0;
        skipped.clear();
        deaths.clear();
        scans.clear();
        messages.clear();
    }

    private static String join(List<?> items) {
        StringJoiner j = new StringJoiner("|");
        items.forEach(i -> j.add(String.valueOf(i)));
        return j.toString();
    }

    @Override
    public void onStatus(StatusEvent e) {
        statuses++;
    }

    @Override
    public void onSkippedTurn(SkippedTurnEvent e) {
        skipped.add(e.getSkippedTurn());
    }

    @Override
    public void onRobotDeath(RobotDeathEvent e) {
        deaths.add(e.getName());
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        scans.add(e.getName());
        if (isTeammate(e.getName())) {
            return;
        }
        if (target == null || target.equals(e.getName())) {
            target = e.getName();
            targetBearing = getHeadingRadians() + e.getBearingRadians();
            targetTime = e.getTime();
        }
    }

    @Override
    public void onMessageReceived(MessageEvent e) {
        if (e.getMessage() instanceof long[] m && m.length == 2 && m[0] == getRoundNum()) {
            messages.add(e.getSender() + "@" + m[1]);
        }
    }

    @Override
    public void onBulletHit(BulletHitEvent e) {
        endEvents.add(e.getBullet());
    }

    @Override
    public void onBulletMissed(BulletMissedEvent e) {
        endEvents.add(e.getBullet());
    }

    @Override
    public void onBulletHitBullet(BulletHitBulletEvent e) {
        endEvents.add(e.getBullet());
    }
}
