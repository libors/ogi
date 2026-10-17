package cz.libors.harness.compare;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    void aMeleeSetNeedsWeights() {
        OpponentSet set = OpponentSet.parse("melee", List.of("format melee", "weight [a] 415", "[a]", "a.A 1"));
        assertEquals(OpponentSet.Format.MELEE, set.format());
        assertEquals(1000, set.format().width);
        assertEquals(35, set.format().rounds);
        assertThrows(IllegalArgumentException.class,
                () -> OpponentSet.parse("melee", List.of("format melee", "[a]", "a.A 1")));
    }

    @Test
    void theTitleIsOptional() {
        OpponentSet set = OpponentSet.parse("plain", List.of("format team", "sampleteam.MyFirstTeam"));
        assertTrue(set.title().isEmpty());
        assertFalse(set.opponents().get(0).strong());
    }

    @Test
    void readsHowManyRumbleRobotsEachGroupStandsFor() {
        OpponentSet set = OpponentSet.parse("tiers", List.of(
                "format 1v1", "weight [strong] 30", "weight [ranks 31-300] 270", "[strong]", "sample.RamFire",
                "[ranks 31-300]", "sample.Walls"));
        assertEquals(Map.of("strong", 30.0, "ranks 31-300", 270.0), set.weights());
        assertEquals(300, set.standsFor());
        OpponentSet plain = OpponentSet.parse("plain", List.of("format 1v1", "sample.Fire", "sample.Walls"));
        assertTrue(plain.weights().isEmpty());
        assertEquals(2, plain.standsFor());
    }

    @Test
    void withWeightsEveryGroupNeedsOne() {
        assertThrows(IllegalArgumentException.class, () -> OpponentSet.parse("partial", List.of(
                "format 1v1", "weight [strong] 30", "[strong]", "sample.RamFire", "[weak]", "sample.Walls")));
        assertThrows(IllegalArgumentException.class, () -> OpponentSet.parse("malformed", List.of(
                "format 1v1", "weight strong 30", "[strong]", "sample.RamFire")));
        assertThrows(IllegalArgumentException.class, () -> OpponentSet.parse("zero", List.of(
                "format 1v1", "weight [strong] 0", "[strong]", "sample.RamFire")));
    }

    @Test
    void anOpponentListedTwiceIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> OpponentSet.parse("twice", List.of(
                "format 1v1", "[strong]", "sample.Fire", "[other]", "sample.Fire")));
    }
}
