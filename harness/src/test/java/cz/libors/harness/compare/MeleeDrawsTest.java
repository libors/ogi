package cz.libors.harness.compare;

import cz.libors.harness.compare.MeleeDraws.Draw;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeleeDrawsTest {

    /** Three groups standing for 30, 70 and 315 participants; the set lacks some of the last two. */
    private static OpponentSet set() {
        List<String> lines = new ArrayList<>(List.of("format melee", "weight [top] 30", "weight [mid] 70",
                "weight [low] 315", "[top]"));
        for (int i = 0; i < 10; i++) {
            lines.add("top.Bot" + i + " 1.0");
        }
        lines.add("[mid]");
        for (int i = 0; i < 20; i++) {
            lines.add("mid.Bot" + i + " 1.0");
        }
        lines.add("[low]");
        for (int i = 0; i < 40; i++) {
            lines.add("low.Bot" + i + " 1.0");
        }
        return OpponentSet.parse("test", lines);
    }

    @Test
    void aDrawHasNineDifferentOpponentsWithinTheQuotasAndASubjectPlace() {
        MeleeDraws draws = new MeleeDraws(set());
        for (int d = 0; d < 2000; d++) {
            Draw draw = draws.draw(7, d);
            assertEquals(9, draw.opponents().size());
            assertEquals(9, new HashSet<>(draw.opponents()).size(), "no opponent twice");
            assertTrue(draw.subjectIndex() >= 0 && draw.subjectIndex() <= 9);
            List<String> robots = draw.robots("cz.libors.Ogi 1");
            assertEquals(10, robots.size());
            assertEquals("cz.libors.Ogi 1", robots.get(draw.subjectIndex()));
            for (int g = 0; g < draws.groups().size(); g++) {
                String prefix = draws.groups().get(g).name() + ".";
                long n = draw.opponents().stream().filter(o -> o.startsWith(prefix)).count();
                double quota = draws.quota(g);
                assertTrue(n == Math.floor(quota) || n == Math.floor(quota) + 1,
                        "group " + prefix + " got " + n + " places for a quota of " + quota);
            }
        }
    }

    @Test
    void aDrawDependsOnlyOnTheRunSeedAndItsNumber() {
        MeleeDraws draws = new MeleeDraws(set());
        assertEquals(draws.draw(3, 11), new MeleeDraws(set()).draw(3, 11));
        assertNotEquals(draws.draw(3, 11), draws.draw(3, 12));
        assertNotEquals(draws.draw(3, 11), draws.draw(4, 11));
    }

    @Test
    void everyOpponentAppearsAsOftenAsItsGroupsShareOfTheRumbleSays() {
        MeleeDraws draws = new MeleeDraws(set());
        int n = 40_000;
        Map<String, Integer> appearances = new HashMap<>();
        int[] places = new int[10];
        for (int d = 0; d < n; d++) {
            Draw draw = draws.draw(1, d);
            draw.opponents().forEach(o -> appearances.merge(o, 1, Integer::sum));
            places[draw.subjectIndex()]++;
        }
        for (MeleeDraws.Group g : draws.groups()) {
            // 9·w_g/(W·n_g) per draw: the rumble's 9/415 for a group the set holds whole.
            double p = 9 * g.weight() / 415 / g.opponents().size();
            double sd = Math.sqrt(n * p * (1 - p));
            for (String o : g.opponents()) {
                int a = appearances.getOrDefault(o, 0);
                assertTrue(Math.abs(a - n * p) < 5 * sd, o + " appeared " + a + " times, expected " + n * p);
            }
        }
        for (int count : places) {
            assertTrue(Math.abs(count - n / 10.0) < 5 * Math.sqrt(n * 0.09), "the subject's place: " + count);
        }
    }

    @Test
    void aGroupNeedsEnoughOpponentsAndAWeightNeedsAGroup() {
        assertThrows(IllegalArgumentException.class, () -> new MeleeDraws(OpponentSet.parse("small", List.of(
                "format melee", "weight [a] 90", "weight [b] 10", "[a]", "a.A 1", "a.B 1", "[b]", "b.A 1"))));
        assertThrows(IllegalArgumentException.class, () -> new MeleeDraws(OpponentSet.parse("empty", List.of(
                "format melee", "weight [a] 1", "weight [b] 1", "[a]", "a.A 1", "a.B 1", "a.C 1", "a.D 1",
                "a.E 1", "a.F 1", "a.G 1", "a.H 1", "a.I 1", "a.J 1"))));
    }
}
