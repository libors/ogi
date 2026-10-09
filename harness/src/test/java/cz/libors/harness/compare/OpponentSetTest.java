package cz.libors.harness.compare;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpponentSetTest {

    @Test
    void readsTheTitleTheFormatAndTheGroups() {
        OpponentSet set = OpponentSet.parse("tiers", List.of(
                "# a comment",
                "title Three tiers of bots",
                "format 1v1",
                "sample.Fire",
                "[strong]",
                "sample.RamFire   # a comment after a name",
                "[ranks 31-300]",
                "sample.Walls",
                "[other]",
                "sample.SpinBot"));
        assertEquals("Three tiers of bots", set.title());
        assertEquals(OpponentSet.Format.DUEL, set.format());
        assertEquals(List.of(
                new OpponentSet.Opponent("sample.Fire", false, ""),
                new OpponentSet.Opponent("sample.RamFire", true, "strong"),
                new OpponentSet.Opponent("sample.Walls", false, "ranks 31-300"),
                new OpponentSet.Opponent("sample.SpinBot", false, "other")), set.opponents());
    }

    @Test
    void theTitleIsOptional() {
        OpponentSet set = OpponentSet.parse("plain", List.of("format team", "sampleteam.MyFirstTeam"));
        assertTrue(set.title().isEmpty());
        assertFalse(set.opponents().get(0).strong());
    }

    @Test
    void anOpponentListedTwiceIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> OpponentSet.parse("twice", List.of(
                "format 1v1", "[strong]", "sample.Fire", "[other]", "sample.Fire")));
    }
}
