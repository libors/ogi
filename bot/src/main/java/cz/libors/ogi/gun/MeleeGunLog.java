package cz.libors.ogi.gun;

/**
 * The melee gun's inputs and decisions as text, so that the offline melee bench can replay a battle: the bot sets
 * them as debug properties ({@code OgiGunLog} only), a recording keeps them, and the bench feeds them to a
 * {@link MeleeGun} with other parameters.
 *
 * <ul>
 *   <li>{@value #SCAN}, for each scan the melee gun learns from: the scan's turn, the enemy's position, heading and
 *       velocity, the closest robot to it as the bot knew it, the enemies alive, and the enemy's name.</li>
 *   <li>{@value #DEATH}, when a robot died: its name.</li>
 *   <li>{@value #AIM}, each time the melee gun aims: the turn the bullet would be fired in, its origin and speed, the
 *       heading found (NaN without one) and the enemy it was aimed at (empty without one).</li>
 *   <li>{@value #FIRE}, when a bullet was fired on the melee gun's aim: the turn of that aim, the bullet's heading,
 *       power and position.</li>
 *   <li>{@value #END}, when such a bullet ended: the turn of its aim, how ({@code h} hit a robot, {@code m} left the
 *       field, {@code b} hit a bullet) and the robot it hit or whose bullet it hit (empty when it left the field).</li>
 * </ul>
 *
 * <p>Fields are comma-separated, a name comes last and may contain commas. Numbers are written as
 * {@link Double#toString} prints them, which parses back to the same value. Angles are mathematical, in radians.
 */
public final class MeleeGunLog {

    public static final String SCAN = "meleescan";
    public static final String DEATH = "meleedeath";
    public static final String AIM = "meleeaim";
    public static final String FIRE = "meleefire";
    public static final String END = "meleeend";

    /** A logged scan: {@link MeleeGun#scanned}'s arguments. */
    public record Scan(String name, long time, double x, double y, double heading, double velocity, double closestX,
                       double closestY, int enemies) {
    }

    /** A logged aim: {@link MeleeGun#aim}'s arguments and what it returned. */
    public record Aim(long fireTime, double x, double y, double speed, double heading, String aimedAt) {
    }

    /** A bullet fired on the aim of turn {@code planTime}. */
    public record Fire(long planTime, double heading, double power, double x, double y) {
    }

    /** How the bullet of the aim of turn {@code planTime} ended. */
    public record End(long planTime, char how, String robot) {
    }

    private MeleeGunLog() {
    }

    public static String scanLine(String name, long time, double x, double y, double heading, double velocity,
                                  double closestX, double closestY, int enemies) {
        return time + "," + x + "," + y + "," + heading + "," + velocity + "," + closestX + "," + closestY + ","
                + enemies + "," + name;
    }

    public static String aimLine(long fireTime, double x, double y, double speed, double heading, String aimedAt) {
        return fireTime + "," + x + "," + y + "," + speed + "," + heading + "," + (aimedAt == null ? "" : aimedAt);
    }

    public static String fireLine(long planTime, double heading, double power, double x, double y) {
        return planTime + "," + heading + "," + power + "," + x + "," + y;
    }

    public static String endLine(long planTime, char how, String robot) {
        return planTime + "," + how + "," + (robot == null ? "" : robot);
    }

    public static Scan scan(String line) {
        String[] f = split(line, 9);
        return new Scan(f[8], Long.parseLong(f[0]), d(f[1]), d(f[2]), d(f[3]), d(f[4]), d(f[5]), d(f[6]),
                Integer.parseInt(f[7]));
    }

    public static Aim aim(String line) {
        String[] f = split(line, 6);
        return new Aim(Long.parseLong(f[0]), d(f[1]), d(f[2]), d(f[3]), d(f[4]), f[5].isEmpty() ? null : f[5]);
    }

    public static Fire fire(String line) {
        String[] f = split(line, 5);
        return new Fire(Long.parseLong(f[0]), d(f[1]), d(f[2]), d(f[3]), d(f[4]));
    }

    public static End end(String line) {
        String[] f = split(line, 3);
        if (f[1].length() != 1 || "hmb".indexOf(f[1].charAt(0)) < 0) {
            throw new IllegalArgumentException("not h, m or b: " + line);
        }
        return new End(Long.parseLong(f[0]), f[1].charAt(0), f[2].isEmpty() ? null : f[2]);
    }

    private static double d(String s) {
        return Double.parseDouble(s);
    }

    private static String[] split(String line, int fields) {
        String[] f = line.split(",", fields);
        if (f.length != fields) {
            throw new IllegalArgumentException("expected " + fields + " fields: " + line);
        }
        return f;
    }
}
