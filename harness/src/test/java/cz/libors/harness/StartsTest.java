package cz.libors.harness;

import cz.libors.harness.BattleSetup.Start;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartsTest {

    @Test
    void startsAreInsideTheFieldApartAndRepeatable() {
        List<Start> starts = Starts.random(5, 10, 400, 400);
        assertEquals(starts, Starts.random(5, 10, 400, 400));
        for (int i = 0; i < starts.size(); i++) {
            Start s = starts.get(i);
            assertTrue(s.x() >= 36 && s.x() <= 364 && s.y() >= 36 && s.y() <= 364, s.toString());
            assertTrue(s.headingDegrees() >= 0 && s.headingDegrees() < 360, s.toString());
            for (int j = 0; j < i; j++) {
                assertFalse(Starts.overlaps(s.x(), s.y(), starts.get(j).x(), starts.get(j).y()));
            }
        }
    }

    @Test
    void formatPrintsPlainDecimals() {
        // The engine strips everything but digits and dots, so a sign or an exponent would change the value.
        for (Start s : Starts.random(9, 50, 5000, 5000)) {
            String text = Starts.format(List.of(s));
            assertTrue(text.matches("\\([0-9.]+,[0-9.]+,[0-9.]+\\),"), text);
        }
        assertEquals("(36.5,100.0,0.001),", Starts.format(List.of(new Start(36.5, 100, 0.001))));
    }
}
