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
