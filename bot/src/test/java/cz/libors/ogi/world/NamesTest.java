package cz.libors.ogi.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NamesTest {

    @Test
    void theCopyNumberIsDropped() {
        assertEquals("a.Bot 3.25.0", Names.base("a.Bot 3.25.0 (1)"));
        assertEquals("a.Bot 3.25.0", Names.base("a.Bot 3.25.0 (12)"));
        assertEquals("sample.Walls", Names.base("sample.Walls"));
        assertEquals("a.Bot 1.0 ()", Names.base("a.Bot 1.0 ()"));
        assertEquals("a.Bot(2)", Names.base("a.Bot(2)"));
        assertEquals("a.Bot 1.0 (x)", Names.base("a.Bot 1.0 (x)"));
    }

    @Test
    void theCopyNumberIsReadOrZero() {
        assertEquals(3, Names.number("cz.libors.Ogi 0.1 (3)"));
        assertEquals(12, Names.number("a.Bot 3.25.0 (12)"));
        assertEquals(0, Names.number("sample.Walls"));
        assertEquals(0, Names.number("a.Bot(2)"));
        assertEquals(0, Names.number("a.Bot 1.0 ()"));
    }
}
