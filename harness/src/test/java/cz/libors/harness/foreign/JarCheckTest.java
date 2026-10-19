package cz.libors.harness.foreign;

import cz.libors.harness.foreign.ApiRules.Level;
import cz.libors.harness.foreign.JarCheck.Finding;
import cz.libors.harness.foreign.JarCheck.Report;
import cz.libors.harness.foreign.JarCheck.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarCheckTest {

    private static final String FIXTURES = "ext/fixtures/";

    @Test
    void ordinaryRobotCodeIsClean() throws IOException {
        Map<String, byte[]> jar = classes("CleanRobot", "CleanRobot$Sighting");
        jar.put(FIXTURES + "CleanRobot.properties",
                "robot.classname=ext.fixtures.CleanRobot\n".getBytes(StandardCharsets.UTF_8));
        Report report = JarCheck.check("clean.jar", "-", jar, false);
        assertEquals(List.of(), report.findings());
        assertEquals(Verdict.CLEAN, report.verdict());
        assertEquals(List.of("ext.fixtures.CleanRobot"), report.robots());
        assertEquals(2, report.classes());
    }

    @Test
    void dangerousApisAreDeniedAndThreadsFlagged() throws IOException {
        Report report = JarCheck.check("hostile.jar", "-", classes("HostileRobot"), false);
        assertEquals(Verdict.DENIED, report.verdict());
        Map<String, Finding> byApi = byApi(report);
        assertFinding(byApi, "java.lang.Runtime.exec", Level.DENY, "processes");
        assertFinding(byApi, "java.net.Socket.<init>", Level.DENY, "network");
        assertFinding(byApi, "java.lang.Class.forName", Level.DENY, "reflection");
        assertFinding(byApi, "java.lang.Class.getDeclaredMethods", Level.DENY, "reflection");
        assertFinding(byApi, "java.lang.System.getenv", Level.DENY, "environment");
        assertFinding(byApi, "java.lang.System.exit", Level.DENY, "exit");
        assertFinding(byApi, "java.io.FileOutputStream.<init>", Level.DENY, "file writes");
        assertFinding(byApi, "java.io.PrintStream.<init>", Level.DENY, "file writes");
        assertFinding(byApi, "java.lang.Thread.getContextClassLoader", Level.DENY, "class loading");
        assertFinding(byApi, "java.lang.System.getProperties", Level.DENY, "JVM-wide state");
        assertFinding(byApi, "java.lang.Thread.<init>", Level.FLAG, "threads");
        assertFinding(byApi, "java.lang.Thread.start", Level.FLAG, "threads");
        assertFinding(byApi, "java.util.List.parallelStream", Level.FLAG, "threads");
        assertFalse(byApi.containsKey("java.lang.Runtime.getRuntime"));
        assertFalse(byApi.containsKey("java.lang.Runtime.availableProcessors"));
        assertEquals(List.of("ext.fixtures.HostileRobot"),
                List.copyOf(byApi.get("java.lang.System.exit").classes()));
    }

    @Test
    void threadsAndDataFilesAreOnlyFlagged() throws IOException {
        Report report = JarCheck.check("threaded.jar", "-", classes("ThreadedRobot"), false);
        assertEquals(Verdict.FLAGGED, report.verdict());
        Map<String, Finding> byApi = byApi(report);
        assertFinding(byApi, "java.lang.Thread.<init>", Level.FLAG, "threads");
        assertFinding(byApi, "java.io.File.exists", Level.FLAG, "file access");
        assertFinding(byApi, "java.util.zip.ZipFile.<init>", Level.FLAG, "file access");
    }

    @Test
    void everyClassFileIsJudgedAndDuplicatesAreDenied() throws IOException {
        Map<String, byte[]> jar = classes("HostileRobot");
        jar.put("copy/HostileRobot.class", classBytes("HostileRobot"));
        Report report = JarCheck.check("twice.jar", "-", jar, false);
        Map<String, Finding> byApi = byApi(report);
        assertFinding(byApi, "class ext.fixtures.HostileRobot", Level.DENY, "duplicate class");
        assertFinding(byApi, "java.lang.Runtime.exec", Level.DENY, "processes");
    }

    @Test
    void foreignCodeMustNotUseTrustedOrReservedPackages() throws IOException {
        // The harness trusts robots by package, and Robocode loads java.*, javax.*, kotlin.* and robocode* classes
        // from the JDK and the engine, never from a robot's jar.
        Map<String, byte[]> jar = new LinkedHashMap<>();
        jar.put("cz/libors/harness/foreign/JarCheckTest.class", ownBytes());
        jar.put("sampleteam/SomeTeam.team", "team.members=ext.fixtures.CleanRobot\n".getBytes(StandardCharsets.UTF_8));
        Map<String, Finding> foreign = byApi(JarCheck.check("trusted.jar", "-", jar, false));
        assertFinding(foreign, "entry cz/libors/harness/foreign/JarCheckTest.class", Level.DENY, "trusted package");
        assertFinding(foreign, "entry sampleteam/SomeTeam.team", Level.DENY, "trusted package");
        assertFalse(byApi(JarCheck.check("ours.jar", "-", jar, true)).values().stream()
                .anyMatch(f -> f.reason().equals("trusted package")));
        assertTrue(JarCheck.reservedPackage("java/lang/Runtime"));
        assertTrue(JarCheck.reservedPackage("javax/swing/JFrame"));
        assertTrue(JarCheck.reservedPackage("kotlin/Unit"));
        assertTrue(JarCheck.reservedPackage("robocode/Robot"));
        assertTrue(JarCheck.reservedPackage("robocodex/Thing"));
        assertFalse(JarCheck.reservedPackage("kc/mega/BeepBoop"));
        assertFalse(JarCheck.reservedPackage("jk/math/FastTrig"));
    }

    @Test
    void extendingAClassLoaderIsDenied() throws IOException {
        Map<String, Finding> byApi = byApi(JarCheck.check("loader.jar", "-", classes("Loader"), false));
        assertFinding(byApi, "extends java.lang.ClassLoader", Level.DENY, "class loading");
    }

    @Test
    void nativeMethodsAreDenied() throws IOException {
        Map<String, Finding> byApi = byApi(JarCheck.check("native.jar", "-", classes("NativeRobot"), false));
        assertFinding(byApi, "native method ext.fixtures.NativeRobot.boom", Level.DENY,
                "native code");
    }

    @Test
    void codeHiddenInOtherEntriesIsDenied() throws IOException {
        Map<String, byte[]> jar = classes("CleanRobot", "CleanRobot$Sighting");
        jar.put("data/stats.bin", classBytes("HostileRobot"));
        jar.put("lib/extra.jar", new byte[] {'P', 'K', 3, 4, 0, 0});
        jar.put("data/stats.dat", zip(classes("CleanRobot")));
        jar.put("META-INF/services/java.lang.Runnable", "x.Y\n".getBytes(StandardCharsets.UTF_8));
        Map<String, Finding> byApi = byApi(JarCheck.check("hidden.jar", "-", jar, false));
        assertFinding(byApi, "entry data/stats.bin", Level.DENY, "hidden class file");
        assertFinding(byApi, "entry lib/extra.jar", Level.DENY, "unreadable member jar");
        assertFinding(byApi, "entry data/stats.dat", Level.DENY, "nested archive");
        assertFinding(byApi, "entry META-INF/services/java.lang.Runnable", Level.FLAG, "service registration");
    }

    @Test
    void aTeamJarsMemberJarsAreCheckedLikeJarsOfTheirOwn() throws IOException {
        Map<String, byte[]> member = classes("CleanRobot", "CleanRobot$Sighting");
        member.put(FIXTURES + "CleanRobot.properties",
                "robot.classname=ext.fixtures.CleanRobot\n".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> team = new LinkedHashMap<>();
        team.put("ext/fixtures/CleanTeam.team", "team.members=ext.fixtures.CleanRobot\n"
                .getBytes(StandardCharsets.UTF_8));
        team.put("CleanRobot.jar", zip(member));
        Report clean = JarCheck.check("team.jar", "-", team, false);
        assertEquals(List.of(), clean.findings());
        assertEquals(List.of("ext.fixtures.CleanTeam (team)", "CleanRobot.jar!/ext.fixtures.CleanRobot"),
                clean.robots());
        assertEquals(2, clean.classes());

        Map<String, byte[]> hostile = classes("HostileRobot");
        hostile.put("deeper.jar", zip(classes("CleanRobot")));
        team.put("HostileRobot.zip", zip(hostile));
        Map<String, Finding> byApi = byApi(JarCheck.check("team.jar", "-", team, false));
        assertFinding(byApi, "java.lang.System.exit", Level.DENY, "exit");
        assertEquals(List.of("HostileRobot.zip!/ext.fixtures.HostileRobot"),
                List.copyOf(byApi.get("java.lang.System.exit").classes()));
        assertFinding(byApi, "entry HostileRobot.zip!/deeper.jar", Level.DENY, "nested archive");
    }

    @Test
    void readsAJarFile(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("clean.jar");
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(file)) {
            for (Map.Entry<String, byte[]> e : classes("CleanRobot", "CleanRobot$Sighting").entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        Report report = JarCheck.check(jar);
        assertEquals(Verdict.CLEAN, report.verdict());
        assertEquals("clean.jar", report.jar());
        assertEquals(JarCheck.sha256(Files.readAllBytes(jar)), report.sha256());
    }

    @Test
    void aMemberJarTheCentralDirectoryLeavesOutIsStillJudged(@TempDir Path dir) throws IOException {
        // Robocode finds robots by reading the jar as a stream of local records (JarRoot.readJarStream); a jar
        // reader such as ZipFile reads the central directory, which a crafted jar can keep from naming an entry.
        Map<String, byte[]> member = classes("HostileRobot");
        member.put(FIXTURES + "HostileRobot.properties",
                "robot.classname=ext.fixtures.HostileRobot\n".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> entries = classes("CleanRobot", "CleanRobot$Sighting");
        entries.put(FIXTURES + "CleanRobot.properties",
                "robot.classname=ext.fixtures.CleanRobot\n".getBytes(StandardCharsets.UTF_8));
        entries.put("Member.jar", zip(member));
        RawZip jar = new RawZip();
        Map<String, Integer> offsets = new LinkedHashMap<>();
        entries.forEach((name, bytes) -> offsets.put(name, jar.local(name, bytes)));
        entries.forEach((name, bytes) -> {
            if (!name.equals("Member.jar")) {
                jar.central(name, bytes, offsets.get(name));
            }
        });
        Path file = dir.resolve("crafted.jar");
        Files.write(file, jar.bytes());

        Report report = JarCheck.check(file);
        assertEquals(Verdict.DENIED, report.verdict());
        Map<String, Finding> byApi = byApi(report);
        assertFinding(byApi, "entry Member.jar", Level.DENY, "directory differs");
        assertFinding(byApi, "java.lang.System.exit", Level.DENY, "exit");
        assertTrue(report.robots().contains("Member.jar!/ext.fixtures.HostileRobot"), report.robots().toString());
    }

    @Test
    void aClassOnlyTheCentralDirectoryListsIsDenied(@TempDir Path dir) throws IOException {
        // The other way round: the class loader reads the jar's classes through the central directory, which can
        // name an entry hidden inside another entry's content, where the stream never sees a record.
        byte[] clean = classBytes("CleanRobot");
        byte[] hostile = classBytes("HostileRobot");
        RawZip inner = new RawZip();
        inner.local(FIXTURES + "HostileRobot.class", hostile);
        byte[] padding = "data".getBytes(StandardCharsets.UTF_8);
        byte[] carrier = new byte[padding.length + inner.bytes().length];
        System.arraycopy(padding, 0, carrier, 0, padding.length);
        System.arraycopy(inner.bytes(), 0, carrier, padding.length, inner.bytes().length);
        RawZip jar = new RawZip();
        int cleanAt = jar.local(FIXTURES + "CleanRobot.class", clean);
        int carrierAt = jar.local("data/stats.dat", carrier);
        jar.central(FIXTURES + "CleanRobot.class", clean, cleanAt);
        jar.central("data/stats.dat", carrier, carrierAt);
        int hiddenAt = carrierAt + RawZip.LOCAL_HEADER + "data/stats.dat".length() + padding.length;
        jar.central(FIXTURES + "HostileRobot.class", hostile, hiddenAt);
        Path file = dir.resolve("crafted.jar");
        Files.write(file, jar.bytes());

        Report report = JarCheck.check(file);
        assertEquals(Verdict.DENIED, report.verdict());
        assertFinding(byApi(report), "entry " + FIXTURES + "HostileRobot.class", Level.DENY, "directory differs");
    }

    @Test
    void rulesResolveNestedClassesArraysAndPackages() {
        assertEquals(Level.ALLOW, ApiRules.type("[[I").level());
        assertEquals(Level.ALLOW, ApiRules.type("[Ljava/lang/String;").level());
        assertEquals(Level.ALLOW, ApiRules.type("java/util/Map$Entry").level());
        assertEquals(Level.ALLOW, ApiRules.type("java/lang/Character$UnicodeBlock").level());
        assertEquals(Level.ALLOW, ApiRules.type("java/lang/IllegalStateException").level());
        assertEquals(Level.ALLOW, ApiRules.type("java/util/concurrent/atomic/AtomicLong").level());
        assertEquals(Level.FLAG, ApiRules.type("java/util/concurrent/Executors").level());
        assertEquals(Level.DENY, ApiRules.type("java/lang/reflect/InvocationTargetException").level());
        assertEquals(Level.DENY, ApiRules.type("java/lang/invoke/MethodHandles$Lookup").level());
        assertEquals(Level.DENY, ApiRules.type("robocode/control/RobocodeEngine").level());
        assertEquals("not on the allowlist", ApiRules.type("kotlin/Unit").reason());
        assertEquals(Level.ALLOW, ApiRules.member("java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/CallSite;").level());
        assertEquals(Level.DENY, ApiRules.member("java/lang/invoke/MethodHandles$Lookup", "findVirtual",
                "()V").level());
        assertEquals(Level.ALLOW, ApiRules.member("java/io/PrintStream", "<init>", "(Ljava/io/OutputStream;)V")
                .level());
        assertEquals(Level.ALLOW, ApiRules.member("[D", "clone", "()Ljava/lang/Object;").level());
    }

    private static void assertFinding(Map<String, Finding> byApi, String api, Level level, String reason) {
        Finding f = byApi.get(api);
        assertNotNull(f, api + " missing from " + byApi.keySet());
        assertEquals(level, f.level(), api);
        assertEquals(reason, f.reason(), api);
        assertTrue(!f.classes().isEmpty(), api);
    }

    private static Map<String, Finding> byApi(Report report) {
        Map<String, Finding> map = new TreeMap<>();
        report.findings().forEach(f -> map.put(f.api(), f));
        return map;
    }

    /** The fixture classes as jar entries, in order. */
    private static Map<String, byte[]> classes(String... names) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String name : names) {
            entries.put(FIXTURES + name + ".class", classBytes(name));
        }
        return entries;
    }

    /** The entries as the bytes of a zip archive. */
    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /**
     * A zip archive written by hand, because {@code ZipOutputStream} writes only archives whose central directory
     * names every local record: stored entries' local records in the order added, then the central directory
     * records added, which may leave entries out or point into another entry's content.
     */
    private static final class RawZip {

        static final int LOCAL_HEADER = 30;

        private final ByteArrayOutputStream local = new ByteArrayOutputStream();
        private final ByteArrayOutputStream central = new ByteArrayOutputStream();
        private int count;

        /** Appends a local record and returns its offset. */
        int local(String name, byte[] content) {
            int offset = local.size();
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            int32(local, 0x04034b50);
            int16(local, 20);
            int16(local, 0);
            int16(local, 0);
            int16(local, 0);
            int16(local, 0x21);
            int32(local, crc(content));
            int32(local, content.length);
            int32(local, content.length);
            int16(local, nameBytes.length);
            int16(local, 0);
            local.writeBytes(nameBytes);
            local.writeBytes(content);
            return offset;
        }

        /** Appends a central directory record that points at the local record at {@code offset}. */
        void central(String name, byte[] content, int offset) {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            int32(central, 0x02014b50);
            int16(central, 20);
            int16(central, 20);
            int16(central, 0);
            int16(central, 0);
            int16(central, 0);
            int16(central, 0x21);
            int32(central, crc(content));
            int32(central, content.length);
            int32(central, content.length);
            int16(central, nameBytes.length);
            int16(central, 0);
            int16(central, 0);
            int16(central, 0);
            int16(central, 0);
            int32(central, 0);
            int32(central, offset);
            central.writeBytes(nameBytes);
            count++;
        }

        /** The local records, the central directory and its end record; just the local records if none was added. */
        byte[] bytes() {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            all.writeBytes(local.toByteArray());
            if (count > 0) {
                all.writeBytes(central.toByteArray());
                int32(all, 0x06054b50);
                int16(all, 0);
                int16(all, 0);
                int16(all, count);
                int16(all, count);
                int32(all, central.size());
                int32(all, local.size());
                int16(all, 0);
            }
            return all.toByteArray();
        }

        private static long crc(byte[] content) {
            CRC32 crc = new CRC32();
            crc.update(content);
            return crc.getValue();
        }

        private static void int16(ByteArrayOutputStream out, int value) {
            out.write(value & 0xff);
            out.write((value >> 8) & 0xff);
        }

        private static void int32(ByteArrayOutputStream out, long value) {
            int16(out, (int) (value & 0xffff));
            int16(out, (int) ((value >> 16) & 0xffff));
        }
    }

    private static byte[] ownBytes() throws IOException {
        try (InputStream in = JarCheckTest.class.getResourceAsStream("JarCheckTest.class")) {
            assertNotNull(in);
            return in.readAllBytes();
        }
    }

    private static byte[] classBytes(String name) throws IOException {
        try (InputStream in = JarCheckTest.class.getResourceAsStream("/" + FIXTURES + name + ".class")) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }
}
