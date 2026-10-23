package cz.libors.harness.queue;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutorTest {

    private static final String APPROVED = "# approved\na.Bot_1.0.jar " + "ab".repeat(32) + " notes: Müller\n";

    @Test
    void approvedListsReadInEveryEncodingAWindowsEditorWrites() {
        List<String> expected = List.of("# approved", "a.Bot_1.0.jar " + "ab".repeat(32) + " notes: Müller");
        assertEquals(expected, Executor.approvedLines(APPROVED.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, Executor.approvedLines(withBom(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                APPROVED.getBytes(StandardCharsets.UTF_8))));
        assertEquals(expected, Executor.approvedLines(withBom(new byte[] {(byte) 0xFF, (byte) 0xFE},
                APPROVED.getBytes(StandardCharsets.UTF_16LE))));
        // The ANSI code page: only the note's character does not decode, the jar's line still parses.
        List<String> ansi = Executor.approvedLines(APPROVED.getBytes(Charset.forName("windows-1252")));
        assertEquals("a.Bot_1.0.jar " + "ab".repeat(32), ansi.get(1).substring(0, 14 + 64));
    }

    private static byte[] withBom(byte[] bom, byte[] text) {
        byte[] all = new byte[bom.length + text.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(text, 0, all, bom.length, text.length);
        return all;
    }
}
