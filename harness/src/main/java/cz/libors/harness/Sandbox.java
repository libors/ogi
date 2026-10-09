package cz.libors.harness;

import java.util.List;
import java.util.Optional;

/**
 * Foreign robots run only where Robocode's sandbox is active.
 *
 * <p>Robocode installs its Security Manager when the engine starts, and only a JVM that still allows one gets it:
 * Java 17, or 18–23 with {@code -Djava.security.manager=allow}. On Java 24+ Robocode 1.11.1 enforces nothing
 * (engine-model G-43).
 */
public final class Sandbox {

    static final String ROBOCODE_MANAGER = "net.sf.robocode.host.security.RobocodeSecurityManager";

    /** Our robots, built here, and Robocode's sample robots, which come with the installation. */
    private static final List<String> TRUSTED = List.of(
            "cz.libors.", "sample.", "sampleex.", "samplesentry.", "sampleteam.");

    private Sandbox() {
    }

    /**
     * The robots and teams not built here and not shipped with Robocode. Trust goes by name, so a foreign jar must
     * not use these packages; the jar check denies it, and deployBots runs the jar check.
     */
    public static List<String> foreign(List<String> robots) {
        return robots.stream().filter(r -> !trusted(r)).toList();
    }

    /** Whether a robot, team or class name lies in our packages or Robocode's sample packages. */
    public static boolean trusted(String name) {
        return TRUSTED.stream().anyMatch(name::startsWith);
    }

    /** The class of the JVM's Security Manager, if one is installed. */
    @SuppressWarnings("removal")
    public static Optional<String> manager() {
        SecurityManager manager = System.getSecurityManager();
        return manager == null ? Optional.empty() : Optional.of(manager.getClass().getName());
    }

    /** Whether Robocode's Security Manager is installed, which happens when an engine is created. */
    public static boolean active() {
        return manager().map(ROBOCODE_MANAGER::equals).orElse(false);
    }

    /** Refuses a battle with foreign robots unless the sandbox is active. */
    static void requireFor(List<String> robots, boolean active) {
        List<String> foreign = foreign(robots);
        if (!foreign.isEmpty() && !active) {
            throw new IllegalStateException("Foreign robots " + foreign + " need Robocode's sandbox, which is not "
                    + "active on Java " + Runtime.version().feature() + "; run them on Java 17 (engine-model G-43)");
        }
    }
}
