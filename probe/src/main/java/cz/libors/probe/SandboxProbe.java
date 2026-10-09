package cz.libors.probe;

import robocode.AdvancedRobot;
import robocode.RobocodeFileOutputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Tries what Robocode's sandbox must block and reports each outcome as the debug property {@code sandbox.<attempt>}:
 * {@code blocked <exception>}, or {@code allowed} with the exception that ended the attempt otherwise.
 *
 * <p>Every attempt is harmless if it succeeds: a connect to a closed port on the loopback address, a file in the
 * temporary directory that is deleted again, running {@code true}, and asking the Security Manager about exit
 * instead of exiting. It also reports how many threads it could start and the names (not the values) of the
 * environment variables it can read. Used by the harness's sandbox check only.
 */
public class SandboxProbe extends AdvancedRobot {

    private static final int MAX_THREADS = 20;

    /** The target of the reflection attempt. */
    @SuppressWarnings("unused")
    private int secret;

    private interface Attempt {
        void run() throws Exception;
    }

    @Override
    public void run() {
        // One turn first: the robot API sleeps a second in a robot's first execute(), which would delay the report.
        execute();
        Map<String, String> results = new LinkedHashMap<>();
        String tmp = System.getProperty("java.io.tmpdir");
        results.put("reflection", attempt(() -> SandboxProbe.class.getDeclaredField("secret").setAccessible(true)));
        results.put("fileRead", attempt(() -> {
            try (InputStream in = new FileInputStream(System.getProperty("java.home") + "/release")) {
                in.read();
            }
        }));
        results.put("fileWrite", attempt(() -> {
            File file = new File(tmp, "ogi-sandbox-" + System.nanoTime());
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(1);
            } finally {
                file.delete();
            }
        }));
        results.put("robocodeFileWrite", attempt(() -> {
            File file = new File(tmp, "ogi-sandbox-" + System.nanoTime());
            try (OutputStream out = new RobocodeFileOutputStream(file)) {
                out.write(1);
            } finally {
                file.delete();
            }
        }));
        results.put("socket", attempt(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", 9), 200);
            }
        }));
        results.put("exec", attempt(() -> new ProcessBuilder("true").start().waitFor()));
        results.put("exit", exit());
        results.put("threads", threads());
        results.put("env", String.join(",", new TreeSet<>(System.getenv().keySet())));
        results.put("done", "1");
        // Without a sandbox the robot is still alive here and the report arrives at once. Robocode's class loader
        // refuses engine classes on every Java version and kills the robot, so this attempt comes last.
        report(results);
        results.put("internalClass", attempt(() -> Class.forName("net.sf.robocode.security.HiddenAccess")));
        while (true) {
            report(results);
        }
    }

    /** A blocked attempt kills the robot, but the first execute() after its death still delivers the report. */
    private void report(Map<String, String> results) {
        results.forEach((key, value) -> setDebugProperty("sandbox." + key, value));
        execute();
    }

    private static String attempt(Attempt attempt) {
        try {
            attempt.run();
            return "allowed";
        } catch (SecurityException | ClassNotFoundException e) {
            // Robocode's class loader refuses engine classes with ClassNotFoundException.
            return "blocked " + e.getClass().getSimpleName();
        } catch (Exception e) {
            return "allowed, then " + e.getClass().getSimpleName();
        }
    }

    /** {@code System.exit} asks the Security Manager first; ask it directly, so that nothing exits if allowed. */
    @SuppressWarnings("removal")
    private static String exit() {
        SecurityManager manager = System.getSecurityManager();
        if (manager == null) {
            return "allowed, no security manager";
        }
        return attempt(() -> manager.checkExit(0));
    }

    private static String threads() {
        List<Thread> started = new ArrayList<>();
        String end = "no limit up to " + MAX_THREADS;
        try {
            while (started.size() < MAX_THREADS) {
                Thread thread = new Thread(SandboxProbe::idle);
                thread.start();
                started.add(thread);
            }
        } catch (SecurityException e) {
            end = "then blocked " + e.getClass().getSimpleName();
        }
        started.forEach(Thread::interrupt);
        return started.size() + " started, " + end;
    }

    private static void idle() {
        try {
            while (true) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            // stop
        }
    }
}
