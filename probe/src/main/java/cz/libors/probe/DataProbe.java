package cz.libors.probe;

import robocode.RobocodeFileOutputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link RandomProbe} that counts the rounds it started in a file in its data directory, as robots do that keep what
 * they learned about an opponent. Every round it reports what the file held before it added itself, in the debug
 * property {@code data}: {@code empty} in the first round of a battle that starts with empty robot data, because
 * {@code getDataFile} creates the file (engine-model G-47), then the rounds counted so far.
 */
public class DataProbe extends RandomProbe {

    private static final String FILE = "rounds.txt";

    @Override
    public void run() {
        String earlier = read();
        writeCount(earlier.chars().allMatch(Character::isDigit) ? Integer.parseInt(earlier) + 1 : 1);
        setDebugProperty("data", earlier);
        super.run();
    }

    /** The count in the file, {@code empty} for an empty file, {@code missing} if there is none. */
    private String read() {
        File file = getDataFile(FILE);
        if (!file.exists()) {
            return "missing";
        }
        try (InputStream in = new FileInputStream(file)) {
            String text = new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
            return text.isEmpty() ? "empty" : Integer.toString(Integer.parseInt(text));
        } catch (IOException | NumberFormatException e) {
            setDebugProperty("dataError", e.toString());
            return "0";
        }
    }

    private void writeCount(int count) {
        try (OutputStream out = new RobocodeFileOutputStream(getDataFile(FILE))) {
            out.write(Integer.toString(count).getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            setDebugProperty("dataError", e.toString());
        }
    }
}
