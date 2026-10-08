package cz.libors.ogi.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnglesTest {

    private static final double EPS = 1e-12;

    @Test
    void robocodeNorthIsMathNinetyDegrees() {
        assertEquals(Math.PI / 2, Angles.fromRobocode(0), EPS);
        assertEquals(0, Angles.fromRobocode(Math.PI / 2), EPS);
        assertEquals(3 * Math.PI / 2, Angles.fromRobocode(Math.PI), EPS);
        assertEquals(Math.PI, Angles.fromRobocode(3 * Math.PI / 2), EPS);
    }

    @Test
    void conversionRoundTrips() {
        for (double a = -7; a < 7; a += 0.37) {
            assertEquals(Angles.normalizeAbsolute(a), Angles.fromRobocode(Angles.toRobocode(a)), 1e-9);
        }
    }

    @Test
    void bearingUsesMathConvention() {
        assertEquals(0, Angles.bearing(0, 0, 10, 0), EPS);
        assertEquals(Math.PI / 2, Angles.bearing(0, 0, 0, 10), EPS);
        assertEquals(Math.PI, Angles.bearing(0, 0, -10, 0), EPS);
    }

    @Test
    void normalizeRelativeIsInMinusPiToPi() {
        assertEquals(-Math.PI, Angles.normalizeRelative(3 * Math.PI), EPS);
        assertEquals(Math.PI / 2, Angles.normalizeRelative(Math.PI / 2 + 4 * Math.PI), 1e-9);
        assertEquals(-Math.PI / 2, Angles.normalizeRelative(3 * Math.PI / 2), EPS);
    }

    @Test
    void robocodeTurnIsClockwisePositive() {
        // East to north is a counter-clockwise (left) turn, i.e. negative in Robocode.
        assertEquals(-Math.PI / 2, Angles.robocodeTurn(0, Math.PI / 2), EPS);
        // North to east is a right turn.
        assertEquals(Math.PI / 2, Angles.robocodeTurn(Math.PI / 2, 0), EPS);
        // The short way across the 0/2π seam.
        assertEquals(0.2, Angles.robocodeTurn(0.1, -0.1), EPS);
    }
}
