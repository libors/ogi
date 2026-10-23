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
import java.util.Arrays;
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
     *
     * <p>A jar has two lists of its entries, and Robocode reads both: it finds robots and member jars in the stream of
     * local records ({@code JarRoot.readJarStream}), and the robot class loader reads the jar's classes through the
     * central directory (a {@code jar:} URL). A crafted jar can show each reader other entries, so the check judges
     * the stream's entries and denies every entry the two lists do not hold alike.
     */
    public static Report check(Path jar, boolean ours) throws IOException {
        byte[] bytes = Files.readAllBytes(jar);
        Lists lists = readBoth("", jar.getFileName().toString(), bytes, jar);
        Report report = check(jar.getFileName().toString(), sha256(bytes), lists.stream(), ours);
        if (lists.problems().isEmpty()) {
            return report;
        }
        List<Finding> findings = new ArrayList<>(lists.problems());
        findings.addAll(report.findings());
        return new Report(report.jar(), report.sha256(), report.robots(), report.classes(), sort(findings));
    }

    /** An archive's entries as the stream of local records shows them, and what the two readers disagree on. */
    private record Lists(Map<String, byte[]> stream, List<Finding> problems) {
    }

    /**
     * Reads an archive both ways, as the stream of its local records and through its central directory
     * ({@code file} holds its bytes), and denies every entry the two do not hold alike. {@code prefix} names the
     * archive in entry names ("" or {@code "Member.jar!/"}), {@code label} in the findings about the whole archive.
     * Nothing it reads throws: an archive it cannot read is a finding.
     */
    private static Lists readBoth(String prefix, String label, byte[] bytes, Path file) {
        Entries stream = new Entries(prefix);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && !stream.add(entry.getName(), zip)) {
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            // Robocode stops reading there too; the entries the directory lists beyond it are denied below.
            stream.problems.add(new Finding(Level.DENY, "unreadable stream", "local records: " + e.getMessage(),
                    new TreeSet<>(Set.of(label))));
        }
        Entries directory = new Entries(prefix);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    if (!directory.add(entry.getName(), in)) {
                        break;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // The robot class loader reads classes through the directory: an archive without a readable one is out.
            directory.problems.add(new Finding(Level.DENY, "unreadable directory", "central directory: "
                    + e.getMessage(), new TreeSet<>(Set.of(label))));
        }
        List<Finding> problems = new ArrayList<>(stream.problems);
        directory.problems.stream().filter(f -> !problems.contains(f)).forEach(problems::add);
        Set<String> names = new TreeSet<>(stream.entries.keySet());
        names.addAll(directory.entries.keySet());
        for (String name : names) {
            if (!Arrays.equals(stream.entries.get(name), directory.entries.get(name))) {
                problems.add(entryFinding(Level.DENY, "directory differs", prefix + name));
            }
        }
        return new Lists(stream.entries, problems);
    }

    /**
     * The entries one reader of an archive sees, in its order, with their uncompressed content, and the entries it
     * refused: a second one of a name, and one over the size limits, which ends the reading.
     */
    private static final class Entries {

        final Map<String, byte[]> entries = new LinkedHashMap<>();
        final List<Finding> problems = new ArrayList<>();
        /** How the findings name the archive's entries: "" or {@code "Member.jar!/"}. */
        private final String prefix;
        private long total;

        Entries(String prefix) {
            this.prefix = prefix;
        }

        /** Reads an entry's content; false once the size limits are exceeded. */
        boolean add(String name, InputStream in) throws IOException {
            if (entries.containsKey(name)) {
                problems.add(entryFinding(Level.DENY, "duplicate entry", prefix + name));
                return true;
            }
            byte[] bytes = in.readNBytes((int) MAX_ENTRY_BYTES + 1);
            total += bytes.length;
            if (bytes.length > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                problems.add(entryFinding(Level.DENY, "oversized entry", prefix + name));
                return false;
            }
            entries.put(name, bytes);
            return true;
        }
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
        /** The classes stored at their name's path: only these can load from the archive. */
        List<ClassFile> loadable = new ArrayList<>();
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
                    String classPath = versioned ? name.replaceFirst("^META-INF/versions/\\d+/", "") : name;
                    if (classPath.equals(cf.name + ".class")) {
                        loadable.add(cf);
                    } else {
                        // The class loader finds a class by its name's path, so this one never loads; as the jar's
                        // own it would leave every reference to the class it names unjudged.
                        add(findings, Level.DENY, "class name differs from its path", "entry " + path, path);
                    }
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
                Optional<Lists> member = readMember(path + "!/", path, bytes);
                if (member.isEmpty() || member.get().stream().isEmpty()) {
                    add(findings, Level.DENY, "unreadable member jar", "entry " + path, path);
                } else {
                    for (Finding f : member.get().problems()) {
                        f.classes().forEach(c -> add(findings, f.level(), f.reason(), f.api(), c));
                    }
                    nestedClasses += checkArchive(path + "!/", member.get().stream(), ours, depth + 1, findings,
                            robots);
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
        Map<String, ClassFile> own = new HashMap<>();
        for (ClassFile cf : loadable) {
            if (!reservedPackage(cf.name)) {
                own.put(cf.name, cf);
            }
        }
        for (ClassFile cf : classes) {
            String user = prefix + dotted(cf.name);
            if (cf.superName != null && !own.containsKey(cf.superName)) {
                judge(findings, ApiRules.type(cf.superName), "extends " + dotted(cf.superName), user);
            }
            for (String iface : cf.interfaces) {
                if (!own.containsKey(iface)) {
                    judge(findings, ApiRules.type(iface), "implements " + dotted(iface), user);
                }
            }
            for (ClassFile.MemberRef ref : cf.memberRefs) {
                if (!own.containsKey(ref.owner())) {
                    judge(findings, ApiRules.member(ref.owner(), ref.name(), ref.descriptor()),
                            dotted(ref.owner()) + "." + ref.name(), user);
                } else {
                    // javac names the receiver's static type as the owner, so a member the jar's own class only
                    // inherits is judged on the classes it is inherited from.
                    for (String type : inheritedFrom(own, ref)) {
                        judge(findings, ApiRules.member(type, ref.name(), ref.descriptor()),
                                dotted(type) + "." + ref.name(), user);
                    }
                }
            }
            for (String method : cf.nativeMethods) {
                add(findings, Level.DENY, "native code", "native method " + user + "." + method, user);
            }
            if (cf.finalizer) {
                add(findings, Level.FLAG, "threads", "finalizer " + user + ".finalize", user);
            }
        }
        return classes.size() + nestedClasses;
    }

    /**
     * The classes outside the jar that a member referenced through one of the jar's own classes can come from: the
     * first superclass and the interfaces outside the jar on the way up from that class. Empty if a class of the jar
     * on the way declares the member itself.
     */
    private static Set<String> inheritedFrom(Map<String, ClassFile> own, ClassFile.MemberRef ref) {
        Set<String> outside = new java.util.TreeSet<>();
        Set<String> seen = new HashSet<>();
        java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>(List.of(ref.owner()));
        while (!todo.isEmpty()) {
            String type = todo.poll();
            if (!seen.add(type)) {
                continue;
            }
            ClassFile cf = own.get(type);
            if (cf == null) {
                outside.add(type);
                continue;
            }
            if (cf.declared.contains(ref.name() + ref.descriptor())) {
                return Set.of();
            }
            if (cf.superName != null) {
                todo.add(cf.superName);
            }
            todo.addAll(cf.interfaces);
        }
        return outside;
    }

    /** Whether Robocode reads this entry as a member jar ({@code JarRoot.readJarStream}: by the name alone). */
    private static boolean memberJar(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jar") || lower.endsWith(".zip");
    }

    /**
     * A member jar read both ways, as the jar itself is: Robocode finds its robots in its stream of local records,
     * and the robot class loader reads its classes through its central directory (a nested {@code jar:} URL, which
     * the JDK opens from a temporary copy). Empty if no temporary copy can be written.
     */
    private static Optional<Lists> readMember(String prefix, String label, byte[] bytes) {
        Path copy = null;
        try {
            copy = Files.createTempFile("jarcheck-member", ".jar");
            Files.write(copy, bytes);
            return Optional.of(readBoth(prefix, label, bytes, copy));
        } catch (IOException e) {
            return Optional.empty();
        } finally {
            if (copy != null) {
                try {
                    Files.deleteIfExists(copy);
                } catch (IOException ignored) {
                    // A temporary file left behind costs nothing here.
                }
            }
        }
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
