package cz.libors.ogi.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayTeamTest {

    @Test
    void digitsSwitchOneMemberAndZeroSwitchesAllAlike() {
        Overlay.team(5);
        allOn();
        Overlay.key('3');
        assertFalse(Overlay.own(3));
        assertTrue(Overlay.own(1) && Overlay.own(2) && Overlay.own(4) && Overlay.own(5));
        // Not all on: 0 switches every member on, then off.
        Overlay.key('0');
        for (int n = 1; n <= 5; n++) {
            assertTrue(Overlay.own(n), "member " + n);
        }
        Overlay.key('0');
        for (int n = 1; n <= 5; n++) {
            assertFalse(Overlay.own(n), "member " + n);
        }
        Overlay.key('2');
        assertTrue(Overlay.own(2));
        // A member beyond the team does nothing.
        Overlay.key('7');
        assertTrue(Overlay.own(2));
        assertFalse(Overlay.own(1) || Overlay.own(3) || Overlay.own(4) || Overlay.own(5));
        allOn();
    }

    @Test
    void theLowestPaintingMemberWithItsLayersOnDrawsWhatTheTeamShares() {
        Overlay.team(5);
        allOn();
        Overlay.key('1');
        int[] painted = {1, 2, 4};
        assertEquals(new Overlay.Roles(false, false, true), Overlay.Roles.of(new Trace.PaintersView(1, 5, painted)));
        assertEquals(new Overlay.Roles(true, true, false), Overlay.Roles.of(new Trace.PaintersView(2, 5, painted)));
        assertEquals(new Overlay.Roles(true, false, false), Overlay.Roles.of(new Trace.PaintersView(4, 5, painted)));
        assertEquals(new Overlay.Roles(true, true, true), Overlay.Roles.of(null));
        allOn();
    }

    /** The members' switches are statics: every test starts and leaves them all on. */
    private static void allOn() {
        for (int n = 1; n <= 5; n++) {
            if (!Overlay.own(n)) {
                Overlay.key('0');
                break;
            }
        }
    }
}
