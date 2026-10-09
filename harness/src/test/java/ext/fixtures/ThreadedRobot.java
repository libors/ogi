package ext.fixtures;

import robocode.AdvancedRobot;

import java.io.IOException;
import java.util.zip.ZipFile;

/** Uses threads and reads files: allowed by Robocode's sandbox, but flagged for review. */
public class ThreadedRobot extends AdvancedRobot {

    @Override
    public void run() {
        new Thread(() -> out.println(getDataFile("stats").exists())).start();
    }

    void readArchive() throws IOException {
        new ZipFile("stats.zip").close();
    }
}
