package cz.libors.harness.compare;

import cz.libors.harness.compare.OpponentSet.Format;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordBattlesTest {

    private static final OpponentSet DUEL = OpponentSet.parse("duel", List.of("format 1v1", "sample.Walls"));
    private static final OpponentSet MELEE = OpponentSet.parse("melee", List.of("format melee", "weight [field] 1",
            "[field]", "s.A 1", "s.B 1", "s.C 1", "s.D 1", "s.E 1", "s.F 1", "s.G 1", "s.H 1", "s.I 1", "s.J 1"));
    private static final OpponentSet TEAM = OpponentSet.parse("team", List.of("format team",
            "sampleteam.MyFirstTeam"));

    @Test
    void anOptionTheSetsFormatDoesNotUseIsRefused() {
        RecordBattles.check(Map.of("pairs", "3"), DUEL);
        RecordBattles.check(Map.of("draws", "3"), MELEE);
        assertThrows(IllegalArgumentException.class, () -> RecordBattles.check(Map.of("pairs", "3"), MELEE));
        assertThrows(IllegalArgumentException.class, () -> RecordBattles.check(Map.of("only", "s.A 1"), MELEE));
        assertThrows(IllegalArgumentException.class, () -> RecordBattles.check(Map.of("starts", "fixed"), MELEE));
        assertThrows(IllegalArgumentException.class, () -> RecordBattles.check(Map.of("draws", "3"), DUEL));
        assertThrows(IllegalArgumentException.class, () -> RecordBattles.check(Map.of("draws", "3"),
                TEAM));
    }

    @Test
    void aTeamSetRecordsTheTeamAsCompareDoes() {
        Map<String, String> options = Map.of("robot", "cz.libors.Ogi", "team", "cz.libors.OgiTeam");
        assertThrows(IllegalArgumentException.class, () -> RecordBattles.check(Map.of("robot", "cz.libors.Ogi"),
                TEAM));
        RecordBattles.check(options, TEAM);
        assertEquals("cz.libors.OgiTeam", RecordBattles.subjectClass(Format.TEAM, options));
        assertEquals("cz.libors.Ogi", RecordBattles.subjectClass(Format.DUEL, options));
        assertEquals("cz.libors.Ogi", RecordBattles.subjectClass(Format.MELEE, options));
    }
}
