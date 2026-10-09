package cz.libors.ogi.gun;

/**
 * The gun's inputs and decisions as text, so that the offline gun bench can replay a battle: the bot sets them as
 * debug properties, a recording keeps them, and {@link GunModel} replays them.
 *
 * <ul>
 *   <li>{@value #SHOT}, in each turn the gun plans a shot: the plan's turn, whether the guess-factor gun searched (1
 *       or 0), the direction, the reference, the escape angle, the circular aim, the {@value TargetingFeatures#COUNT}
 *       features, the guess factor found, the chosen gun and the target.</li>
 *   <li>{@value #PASS}, once for each wave that passed its target in this turn, before the turn's plan: the plan's
 *       turn, whether a bullet was fired with it (1 or 0), the wave's reference and escape angle, whether any heading
 *       would have hit (1 or 0), the lowest and the highest hitting heading, and the target.</li>
 *   <li>{@value #SHIELD}, when an enemy bullet destroyed one of ours ({@code OgiGunLog} only): the turn of the
 *       bullet's plan and the name of the robot whose bullet it was. That bullet's wave still passes, but the bullet
 *       never got there.</li>
 * </ul>
 *
 * <p>Fields are comma-separated, the target's name comes last and may contain commas. Numbers are written as
 * {@link Double#toString} prints them, which parses back to the same value. Angles are mathematical, in radians.
 */
public final class GunLog {

    public static final String SHOT = "gunshot";
    public static final String PASS = "gunpass";
    public static final String SHIELD = "gunshield";

    private static final int SHOT_FIELDS = 9 + TargetingFeatures.COUNT;
    private static final int PASS_FIELDS = 8;

    /** A logged plan: the shot's inputs and what the bot decided. */
    public record LoggedShot(GunModel.Shot shot, double guessFactor, int gun) {
    }

    private GunLog() {
    }

    public static String line(GunModel.Shot s, GunModel.Aim a) {
        StringBuilder b = new StringBuilder(400);
        b.append(s.time()).append(',').append(s.search() ? 1 : 0).append(',').append(s.direction())
                .append(',').append(s.reference()).append(',').append(s.escape()).append(',').append(s.circularAim());
        for (double f : s.features()) {
            b.append(',').append(f);
        }
        return b.append(',').append(a.guessFactor()).append(',').append(a.gun()).append(',').append(s.target())
                .toString();
    }

    public static String line(GunModel.Pass p) {
        return p.time() + "," + (p.real() ? 1 : 0) + "," + p.reference() + "," + p.escape() + "," + (p.hits() ? 1 : 0)
                + "," + p.minOffset() + "," + p.maxOffset() + "," + p.target();
    }

    public static String shieldLine(long planTime, String shooter) {
        return planTime + "," + shooter;
    }

    /** The plan's turn of a bullet that an enemy bullet destroyed. */
    public static long shieldTime(String line) {
        return Long.parseLong(line.substring(0, line.indexOf(',')));
    }

    public static LoggedShot shot(String line) {
        String[] f = split(line, SHOT_FIELDS);
        double[] features = new double[TargetingFeatures.COUNT];
        for (int i = 0; i < features.length; i++) {
            features[i] = Double.parseDouble(f[6 + i]);
        }
        int k = 6 + features.length;
        GunModel.Shot shot = new GunModel.Shot(f[k + 2], Long.parseLong(f[0]), features, Integer.parseInt(f[2]),
                Double.parseDouble(f[3]), Double.parseDouble(f[4]), flag(f[1]), Double.parseDouble(f[5]));
        return new LoggedShot(shot, Double.parseDouble(f[k]), Integer.parseInt(f[k + 1]));
    }

    public static GunModel.Pass pass(String line) {
        String[] f = split(line, PASS_FIELDS);
        return new GunModel.Pass(f[7], Long.parseLong(f[0]), flag(f[1]), Double.parseDouble(f[2]),
                Double.parseDouble(f[3]), flag(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]));
    }

    private static String[] split(String line, int fields) {
        String[] f = line.split(",", fields);
        if (f.length != fields) {
            throw new IllegalArgumentException("expected " + fields + " fields: " + line);
        }
        return f;
    }

    private static boolean flag(String s) {
        return switch (s) {
            case "1" -> true;
            case "0" -> false;
            default -> throw new IllegalArgumentException("not 1 or 0: " + s);
        };
    }
}
