package cz.libors.harness;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Checks a release jar as a rumble client takes it. One jar serves the 1v1, melee and team rumbles, so it must pass
 * the client's own check for both participant lines, {@code cz.libors.Ogi <version>} and
 * {@code cz.libors.OgiTeam <version>}. A client keeps one file per line, named {@code <name>_<version>.jar}: the
 * check deploys the jar under both names and runs the skeleton battles with them.
 *
 * <p>The client's check is {@code BotsDownload.checkJarFile} of the installed {@code roborumble.jar}, called by
 * reflection. It accepts a jar whose {@code <package>/<Class>.properties} names the robot and its version, and with
 * {@code TEAMS=YES} one whose {@code <package>/<Team>.team} carries the team's version.
 *
 * <p>Usage: {@code ReleaseCheck <robocodeHome> <release jar> <version> <output.json>}
 */
public final class ReleaseCheck {

    private static final String ROBOT = "cz.libors.Ogi";
    private static final String TEAM = "cz.libors.OgiTeam";

    private ReleaseCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: ReleaseCheck <robocodeHome> <release jar> <version> <output.json>");
            System.exit(2);
        }
        Path home = Path.of(args[0]);
        Path jar = Path.of(args[1]);
        String version = args[2];
        Path output = Path.of(args[3]);

        String robot = ROBOT + " " + version;
        String team = TEAM + " " + version;
        boolean accepted = clientAccepts(jar, robot, false) && clientAccepts(jar, team, true);
        // The check must see the version, or passing it would prove nothing.
        boolean versionSeen = !clientAccepts(jar, ROBOT + " " + version + "x", false);
        System.out.printf("%-4s the rumble client's jar check: %s, %s%n", accepted && versionSeen ? "OK" : "FAIL",
                robot, team);
        if (!accepted || !versionSeen) {
            System.exit(1);
        }

        Path robots = home.resolve("robots");
        for (String name : new String[] {robot, team}) {
            Files.copy(jar, robots.resolve(name.replace(' ', '_') + ".jar"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(robots.resolve("robot.database"));
        long failed = SkeletonCheck.run(home, robot, team, output);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Whether the installed rumble client accepts the jar for a participant line's "name version". */
    private static boolean clientAccepts(Path jar, String nameAndVersion, boolean team) throws Exception {
        Class<?> download = Class.forName("net.sf.robocode.roborumble.netengine.BotsDownload");
        Properties properties = new Properties();
        properties.setProperty("TEAMS", team ? "YES" : "NOT");
        Object client = download.getConstructor(String.class, Properties.class).newInstance("release", properties);
        Method check = download.getDeclaredMethod("checkJarFile", String.class, String.class);
        check.setAccessible(true);
        return (Boolean) check.invoke(client, jar.toString(), nameAndVersion);
    }
}
