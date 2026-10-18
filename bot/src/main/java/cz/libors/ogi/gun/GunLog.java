package cz.libors.ogi.gun;

/**
 * The gun's inputs and decisions as text, so that the offline gun bench can replay a battle: the bot sets them as
 * debug properties, a recording keeps them, and {@link GunModel} replays them.
 *
 * <ul>
 *   <li>{@value #SHOT}, in each turn the gun plans a shot: the plan's turn, whether the guess-factor gun searched (1
 *       or 0), the direction, the reference, the escape angle, the circular aim, the {@value TargetingFeatures#COUNT}
 *       features, the guess factor found, the chosen gun, the random gun's range (lowest and highest guess factor)
 *       and draw, the mirror gun's aim, whether one enemy lived (1 or 0), and the target. Lines logged before the
 *       random gun (2026-10-12) lack the range and the draw, and lines logged before the mirror gun (2026-10-14) its
 *       aim; they are read with NaN there. Lines logged before 2026-10-18 lack the one-enemy flag and are read as one
 *       enemy's, which leaves a replay of them as it was.</li>
 *   <li>{@value #PASS}, once for each wave that passed its target in this turn, before the turn's plan: the plan's
 *       turn, whether a bullet was fired with it (1 or 0), the wave's reference and escape angle, whether any heading
 *       would have hit (1 or 0), the lowest and the highest hitting offset from the reference, and the target.</li>
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

    private static final int SHOT_FIELDS = 14 + TargetingFeatures.COUNT;
    /** A shot line logged before the one-enemy flag. */
    private static final int NO_ALONE_SHOT_FIELDS = 13 + TargetingFeatures.COUNT;
    /** A shot line logged before the mirror gun. */
    private static final int NO_MIRROR_SHOT_FIELDS = 12 + TargetingFeatures.COUNT;
    /** A shot line logged before the random gun. */
    private static final int OLD_SHOT_FIELDS = 9 + TargetingFeatures.COUNT;
    private static final int PASS_FIELDS = 8;

    /**
     * A logged plan: the shot's inputs and what the bot decided; {@code aloneLogged} whether the line carried the
     * one-enemy flag (lines from before 2026-10-18 did not, and read as one enemy's).
     */
    public record LoggedShot(GunModel.Shot shot, double guessFactor, int gun, boolean aloneLogged) {
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
        return b.append(',').append(a.guessFactor()).append(',').append(a.gun()).append(',').append(s.randomLow())
                .append(',').append(s.randomHigh()).append(',').append(s.randomDraw()).append(',').append(s.mirrorAim())
                .append(',').append(s.alone() ? 1 : 0).append(',').append(s.target()).toString();
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
        int k = 6 + TargetingFeatures.COUNT;
        String[] f = line.split(",", SHOT_FIELDS);
        // A robot's name starts with its package, never with a number: an old line has the name where the range is,
        // a line from before the mirror gun where its aim is, one from before the one-enemy flag where the flag is.
        boolean old = f.length < NO_MIRROR_SHOT_FIELDS || !isNumber(f[k + 2]);
        boolean noMirror = !old && (f.length < NO_ALONE_SHOT_FIELDS || !isNumber(f[k + 5]));
        boolean noAlone = !old && !noMirror && (f.length < SHOT_FIELDS || !isNumber(f[k + 6]));
        if (old) {
            f = split(line, OLD_SHOT_FIELDS);
        } else if (noMirror) {
            f = split(line, NO_MIRROR_SHOT_FIELDS);
        } else if (noAlone) {
            f = split(line, NO_ALONE_SHOT_FIELDS);
        }
        double[] features = new double[TargetingFeatures.COUNT];
        for (int i = 0; i < features.length; i++) {
            features[i] = Double.parseDouble(f[6 + i]);
        }
        double low = old ? Double.NaN : Double.parseDouble(f[k + 2]);
        double high = old ? Double.NaN : Double.parseDouble(f[k + 3]);
        double draw = old ? Double.NaN : Double.parseDouble(f[k + 4]);
        double mirror = old || noMirror ? Double.NaN : Double.parseDouble(f[k + 5]);
        boolean alone = old || noMirror || noAlone || flag(f[k + 6]);
        GunModel.Shot shot = new GunModel.Shot(f[old ? k + 2 : noMirror ? k + 5 : noAlone ? k + 6 : k + 7],
                Long.parseLong(f[0]), features, Integer.parseInt(f[2]), Double.parseDouble(f[3]),
                Double.parseDouble(f[4]), flag(f[1]), Double.parseDouble(f[5]), low, high, draw, mirror, alone);
        return new LoggedShot(shot, Double.parseDouble(f[k]), Integer.parseInt(f[k + 1]), !(old || noMirror || noAlone));
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
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
