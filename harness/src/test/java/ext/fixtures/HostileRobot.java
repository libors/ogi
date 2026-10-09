package ext.fixtures;

import robocode.AdvancedRobot;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.util.List;

/** References what the allowlist must deny or flag. Never run: the methods exist only to be scanned. */
public class HostileRobot extends AdvancedRobot {

    void processes() throws Exception {
        Runtime.getRuntime().exec("true");
    }

    void network() throws Exception {
        new Socket("127.0.0.1", 9).close();
    }

    void reflection() throws Exception {
        Class.forName("java.lang.Runtime").getDeclaredMethods();
    }

    void environment() {
        out.println(System.getenv("HOME"));
    }

    void exit() {
        System.exit(0);
    }

    void fileWrites() throws Exception {
        new FileOutputStream("x").close();
        new PrintStream("y").close();
    }

    void classLoading() {
        out.println(Thread.currentThread().getContextClassLoader());
    }

    void threads(List<String> names) {
        new Thread(() -> out.println("side")).start();
        names.parallelStream().forEach(out::println);
    }

    void jvmState() {
        System.getProperties().setProperty("ogi.fixture", "1");
    }

    void harmless() {
        out.println(Runtime.getRuntime().availableProcessors());
    }
}
