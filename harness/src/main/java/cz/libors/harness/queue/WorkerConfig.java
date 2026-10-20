package cz.libors.harness.queue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A machine that runs battles for the queue, and where things are on it.
 *
 * <ul>
 *   <li>{@code local}: this machine, as a local process; trusted robots only (statistics against foreign robots
 *       run on the Windows worker).</li>
 *   <li>any other name: an ssh host with the native Windows layout (build-and-run.md): Java 17 in
 *       {@code C:\ogi\jdk}, everything else under {@code C:\ogi}, the approved foreign jars in
 *       {@code C:\ogi\rumble}.</li>
 * </ul>
 *
 * @param jvm the slots' JVM options, the Security Manager's included where the JVM needs it
 */
record WorkerConfig(String name, boolean local, int slots, boolean foreign, String java, String root, String bundle,
                    String foreignDir, List<String> jvm, double cpuFactor) {

    /** The options of the load study (build-and-run.md): cheap compilation, no concurrent collector. */
    static final List<String> DEFAULT_JVM = List.of("-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1");
    /** A rumble client's options (Robocode's {@code roborumble.sh}): the JVM's own collector and compilers. */
    static final List<String> RUMBLE_JVM = List.of("-Xmx512M");

    /** The slots' base options by profile: {@code queue} (the default) or {@code rumble}. */
    static List<String> baseJvm(String profile) {
        return switch (profile) {
            case "queue" -> DEFAULT_JVM;
            case "rumble" -> RUMBLE_JVM;
            default -> throw new IllegalArgumentException("slot JVM profile: queue or rumble, not " + profile);
        };
    }

    /**
     * {@code <name>:<slots>}, e.g. {@code ogi-win:12} or {@code local:4}.
     *
     * @param localJava              the Java 17 the local worker runs (Gradle's toolchain)
     * @param localSecurityManager   whether that JVM needs {@code -Djava.security.manager=allow} (Java 12–23)
     * @param baseJvm                the slots' options before {@code extraJvm} ({@link #baseJvm(String)})
     */
    static WorkerConfig parse(String entry, Path stateDir, Path localBundle, String localJava,
                              boolean localSecurityManager, List<String> baseJvm, List<String> extraJvm,
                              double cpuFactor) {
        String[] parts = entry.trim().split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("worker: <name>:<slots>, not " + entry);
        }
        String name = parts[0];
        int slots = Integer.parseInt(parts[1]);
        List<String> jvm = new ArrayList<>(baseJvm);
        jvm.addAll(extraJvm);
        if (name.equals("local")) {
            if (localSecurityManager) {
                jvm.add("-Djava.security.manager=allow");
            }
            return new WorkerConfig(name, true, slots, false, localJava,
                    stateDir.resolve("workers").resolve("local").toAbsolutePath().toString(),
                    localBundle.toAbsolutePath().toString(), null, List.copyOf(jvm), cpuFactor);
        }
        // Java 17 on the Windows workers (C:\ogi\jdk): the Security Manager needs no flag, and the flag is harmless.
        jvm.add("-Djava.security.manager=allow");
        return new WorkerConfig(name, false, slots, true, "C:/ogi/jdk/bin/java.exe", "C:/ogi", "C:/ogi/bundle",
                "C:/ogi/rumble", List.copyOf(jvm), cpuFactor);
    }

    /** Where the server puts a job's jars on the worker, for the executor's {@code deploy}. */
    String jarsDir() {
        return root + "/jars";
    }

    /** The executor's arguments after its main class. */
    List<String> executorArguments(String mode) {
        List<String> args = new ArrayList<>(List.of(mode, "bundle=" + bundle, "root=" + root, "java=" + java,
                "cpuFactor=" + cpuFactor));
        if (mode.equals("serve")) {
            args.add("slots=" + slots);
        }
        if (foreignDir != null) {
            args.add("foreign=" + foreignDir);
        }
        jvm.forEach(o -> args.add("jvm=" + o));
        return args;
    }
}
