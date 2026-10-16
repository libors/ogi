package cz.libors.harness.foreign;

import cz.libors.harness.Sandbox;
import cz.libors.harness.foreign.ApiRules.Level;
import cz.libors.harness.foreign.ApiRules.Rule;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Checks a foreign robot or team jar against {@link ApiRules} before it is run: every member its classes reference,
 * the classes they extend or implement, native methods, and jar entries that could hide code. A team jar's member
 * jars are checked like jars of their own.
 *
 * <p>Robot code can reach other classes only through the references in its constant pool, so without reflection or
 * class loading (both denied) the references are all it can call. What the check cannot see is where a robot reads
 * and writes files at run time; that stays with Robocode's sandbox.
 *
 * <p>Usage: {@code JarCheck <jar or directory>...}. Exits with 1 if any jar is denied.
 */
public final class JarCheck {

    /** Larger entries are refused rather than read. */
    private static final long MAX_ENTRY_BYTES = 16L << 20;
    private static final long MAX_TOTAL_BYTES = 64L << 20;

    public enum Verdict { CLEAN, FLAGGED, DENIED }

    /** One API (or jar entry) with a rule other than allow, and the classes that use it. */
    public record Finding(Level level, String reason, String api, Set<String> classes) {
    }

    public record Report(String jar, String sha256, List<String> robots, int classes, List<Finding> findings) {

        public Verdict verdict() {
            if (findings.stream().anyMatch(f -> f.level() == Level.DENY)) {
                return Verdict.DENIED;
            }
            return findings.isEmpty() ? Verdict.CLEAN : Verdict.FLAGGED;
        }
    }

    private JarCheck() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: JarCheck <jar or directory>...");
            System.exit(2);
        }
        List<Path> jars = new ArrayList<>();
        for (String arg : args) {
            Path path = Path.of(arg);
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.list(path)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().forEach(jars::add);
                }
            } else {
                jars.add(path);
            }
        }
        int denied = 0;
        for (Path jar : jars) {
            Report report = check(jar);
            System.out.println(format(report));
            if (report.verdict() == Verdict.DENIED) {
                denied++;
            }
        }
        System.out.printf("%n%d jar(s) checked, %d denied. Read flagged and denied code before running it.%n",
                jars.size(), denied);
        System.exit(denied == 0 ? 0 : 1);
    }

    /** Checks a foreign jar. */
    public static Report check(Path jar) throws IOException {
        return check(jar, false);
    }

    /**
     * Checks a jar; {@code ours} skips the rule against code in the trusted packages, for checking our own jars and
     * the sample robots against false alarms.
     */
    public static Report check(Path jar, boolean ours) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        List<Finding> problems = new ArrayList<>();
        long total = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (entries.containsKey(entry.getName())) {
                    problems.add(entryFinding(Level.DENY, "duplicate entry", entry.getName()));
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readNBytes((int) MAX_ENTRY_BYTES + 1);
                }
                total += bytes.length;
                if (bytes.length > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                    problems.add(entryFinding(Level.DENY, "oversized entry", entry.getName()));
                    break;
                }
                entries.put(entry.getName(), bytes);
            }
        }
        Report report = check(jar.getFileName().toString(), sha256(Files.readAllBytes(jar)), entries, ours);
        if (problems.isEmpty()) {
            return report;
        }
        List<Finding> findings = new ArrayList<>(problems);
        findings.addAll(report.findings());
        return new Report(report.jar(), report.sha256(), report.robots(), report.classes(), sort(findings));
    }

    /**
     * Checks the entries of a jar, in jar order; the bytes are each entry's uncompressed content.
     *
     * <p>Every class file is judged, also when two of them name the same class. A class in a package that Robocode's
     * robot class loader takes from the JDK or the engine instead ({@link #reservedPackage}) is denied and does not
     * count as the jar's own, so it cannot hide references to the real class.
     */
    static Report check(String jarName, String sha256, Map<String, byte[]> entries, boolean ours) {
        Map<List<String>, Finding> findings = new LinkedHashMap<>();
        List<String> robots = new ArrayList<>();
        int classes = checkArchive("", entries, ours, 0, findings, robots);
        return new Report(jarName, sha256, List.copyOf(robots), classes, sort(new ArrayList<>(findings.values())));
    }

    /**
     * Checks one archive's entries and returns its number of class files. {@code prefix} names the archive inside
     * the jar ("" for the jar itself, {@code "inner.jar!/"} for a member jar); every finding and robot it adds
     * carries it.
     *
     * <p>A team jar may carry its members' jars: Robocode reads every entry whose name ends with {@code .jar} or
     * {@code .zip} as a jar of its own and loads robots from it ({@code JarRoot.readJarStream}). Such a member jar is
     * checked like a jar of its own, its classes judged against its own classes only, because a robot's class loader
     * reaches only the archive it was found in. Archives inside member jars, and archives under other names, are
     * denied.
     */
    private static int checkArchive(String prefix, Map<String, byte[]> entries, boolean ours, int depth,
                                    Map<List<String>, Finding> findings, List<String> robots) {
        List<ClassFile> classes = new ArrayList<>();
        Map<String, String> firstPath = new HashMap<>();
        int nestedClasses = 0;
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            String path = prefix + name;
            byte[] bytes = e.getValue();
            if (!ours && Sandbox.trusted(name.replace('/', '.'))) {
                add(findings, Level.DENY, "trusted package", "entry " + path, path);
            }
            if (name.endsWith(".class")) {
                boolean versioned = name.startsWith("META-INF/versions/");
                if (versioned) {
                    add(findings, Level.FLAG, "multi-release classes", "entry " + path, path);
                }
                try {
                    ClassFile cf = ClassFile.parse(bytes);
                    classes.add(cf);
                    String earlier = firstPath.putIfAbsent(cf.name, name);
                    if (earlier != null && !versioned && !earlier.startsWith("META-INF/versions/")) {
                        add(findings, Level.DENY, "duplicate class", "class " + prefix + dotted(cf.name), path);
                    }
                    if (reservedPackage(cf.name)) {
                        add(findings, Level.DENY, "JDK or engine package", "class " + prefix + dotted(cf.name),
                                path);
                    }
                    if (!ours && Sandbox.trusted(dotted(cf.name))) {
                        add(findings, Level.DENY, "trusted package", "class " + prefix + dotted(cf.name), path);
                    }
                } catch (IOException | RuntimeException ex) {
                    add(findings, Level.DENY, "unreadable class file", "entry " + path, path);
                }
            } else if (ClassFile.hasMagic(bytes)) {
                add(findings, Level.DENY, "hidden class file", "entry " + path, path);
            } else if (depth == 0 && memberJar(name)) {
                Optional<Map<String, byte[]>> member = readMember(bytes);
                if (member.isEmpty()) {
                    add(findings, Level.DENY, "unreadable member jar", "entry " + path, path);
                } else {
                    nestedClasses += checkArchive(path + "!/", member.get(), ours, depth + 1, findings, robots);
                }
            } else if (isArchive(name, bytes)) {
                add(findings, Level.DENY, "nested archive", "entry " + path, path);
            } else if (name.startsWith("META-INF/services/")) {
                add(findings, Level.FLAG, "service registration", "entry " + path, path);
            } else if (name.endsWith(".properties")) {
                robotClass(bytes).ifPresent(robot -> robots.add(prefix + robot));
            } else if (name.endsWith(".team")) {
                robots.add(prefix + dotted(name.substring(0, name.length() - ".team".length())) + " (team)");
            }
        }
        Set<String> own = new HashSet<>();
        for (ClassFile cf : classes) {
            if (!reservedPackage(cf.name)) {
                own.add(cf.name);
            }
        }
        for (ClassFile cf : classes) {
            String user = prefix + dotted(cf.name);
            if (cf.superName != null && !own.contains(cf.superName)) {
                judge(findings, ApiRules.type(cf.superName), "extends " + dotted(cf.superName), user);
            }
            for (String iface : cf.interfaces) {
                if (!own.contains(iface)) {
                    judge(findings, ApiRules.type(iface), "implements " + dotted(iface), user);
                }
            }
            for (ClassFile.MemberRef ref : cf.memberRefs) {
                if (!own.contains(ref.owner())) {
                    judge(findings, ApiRules.member(ref.owner(), ref.name(), ref.descriptor()),
                            dotted(ref.owner()) + "." + ref.name(), user);
                }
            }
            for (String method : cf.nativeMethods) {
                add(findings, Level.DENY, "native code", "native method " + user + "." + method, user);
            }
        }
        return classes.size() + nestedClasses;
    }

    /** Whether Robocode reads this entry as a member jar ({@code JarRoot.readJarStream}: by the name alone). */
    private static boolean memberJar(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jar") || lower.endsWith(".zip");
    }

    /**
     * A member jar's entries as Robocode's stream reader sees them (local headers, in order), or empty if it is not
     * a readable archive, holds a duplicate entry or exceeds the size limits.
     */
    private static Optional<Map<String, byte[]>> readMember(byte[] bytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] content = zip.readNBytes((int) MAX_ENTRY_BYTES + 1);
                total += content.length;
                if (content.length > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES
                        || entries.put(entry.getName(), content) != null) {
                    return Optional.empty();
                }
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries);
    }

    /**
     * Whether Robocode's robot class loader takes a class of this name from the JDK or the engine, never from the
     * robot's jar ({@code RobotClassLoader.loadClass}: java., javax., kotlin. and every name starting with robocode).
     */
    static boolean reservedPackage(String internalName) {
        return internalName.startsWith("java/") || internalName.startsWith("javax/")
                || internalName.startsWith("kotlin/") || internalName.startsWith("robocode");
    }

    public static String format(Report report) {
        StringBuilder out = new StringBuilder();
        out.append(String.format("%-8s %s  (%d classes, robots: %s, sha256 %s)", report.verdict(), report.jar(),
                report.classes(), report.robots().isEmpty() ? "none" : String.join(", ", report.robots()),
                report.sha256()));
        for (Finding f : report.findings()) {
            out.append(String.format("%n  %-4s %-22s %s  <- %s", f.level(), f.reason(), f.api(),
                    String.join(", ", f.classes())));
        }
        return out.toString();
    }

    private static void judge(Map<List<String>, Finding> findings, Rule rule, String api, String user) {
        if (rule.level() != Level.ALLOW) {
            add(findings, rule.level(), rule.reason(), api, user);
        }
    }

    private static void add(Map<List<String>, Finding> findings, Level level, String reason, String api,
                            String user) {
        findings.computeIfAbsent(List.of(level.name(), reason, api),
                k -> new Finding(level, reason, api, new TreeSet<>())).classes().add(user);
    }

    private static Finding entryFinding(Level level, String reason, String entry) {
        return new Finding(level, reason, "entry " + entry, new TreeSet<>(Set.of(entry)));
    }

    private static List<Finding> sort(List<Finding> findings) {
        findings.sort(Comparator.comparing((Finding f) -> f.level() == Level.DENY ? 0 : 1)
                .thenComparing(Finding::reason).thenComparing(Finding::api));
        return List.copyOf(findings);
    }

    private static boolean isArchive(String name, byte[] bytes) {
        String lower = name.toLowerCase();
        boolean zipMagic = bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
        return zipMagic || lower.endsWith(".jar") || lower.endsWith(".zip");
    }

    private static Optional<String> robotClass(byte[] bytes) {
        Properties properties = new Properties();
        try {
            properties.load(new ByteArrayInputStream(bytes));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(properties.getProperty("robot.classname"));
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
