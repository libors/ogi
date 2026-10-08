package cz.libors.ogi.enemy;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Readable examples of the detector's rules. Its quality is measured against real battles by
 * {@code FireDetectionTest} in the harness.
 */
class FireDetectorTest {

    private final FireDetector detector = new FireDetector("enemy", 0.1, 800, 600);

    @Test
    void consecutiveScansGiveTheExactFireTurnAndOrigin() {
        detector.scanned(40, 100, 200, 8, 100);
        DetectedShot shot = detector.scanned(41, 108, 200, 8, 98).orElseThrow();
        assertEquals(40, shot.fireTime());
        assertTrue(shot.exactTime());
        assertEquals(2, shot.power(), 1e-12);
        assertEquals(100, shot.x());
    }

    @Test
    void noShotWhileTheGunIsHotFromTheRoundStart() {
        // All guns start at heat 3 and cool by 0.1 per turn.
        detector.scanned(20, 100, 200, 0, 100);
        assertTrue(detector.scanned(21, 100, 200, 0, 99).isEmpty());
    }

    @Test
    void observedHitsAreNotShots() {
        detector.scanned(40, 100, 200, 0, 100);
        detector.ourBulletHit(1);
        assertTrue(detector.scanned(41, 100, 200, 0, 96).isEmpty());
        // Its bullet hit us: it regained 3p, which hides part of its next shot's cost.
        detector.hitUs(1);
        DetectedShot shot = detector.scanned(42, 100, 200, 0, 97).orElseThrow();
        assertEquals(2, shot.power(), 1e-12);
    }

    @Test
    void wallDamageIsNotAShot() {
        detector.scanned(40, 700, 300, 8, 100);
        // Stopped at the right wall from full speed: 3 damage, no shot.
        assertTrue(detector.scanned(41, 782, 300, 0, 97).isEmpty());
    }

    @Test
    void theNextShotWaitsForTheGunToCool() {
        detector.scanned(40, 100, 200, 0, 100);
        detector.scanned(41, 100, 200, 0, 97).orElseThrow();
        // A 3.0 shot heats the gun to 1.6: the next is possible 16 turns later.
        assertTrue(detector.scanned(42, 100, 200, 0, 96).isEmpty());
        detector.scanned(55, 100, 200, 0, 96);
        Optional<DetectedShot> next = detector.scanned(57, 100, 200, 0, 95);
        assertEquals(56, next.orElseThrow().fireTime());
    }

    @Test
    void aShotThatSpendsTheLastEnergy() {
        detector.scanned(40, 100, 200, 0, 0.05);
        DetectedShot shot = detector.scanned(41, 100, 200, 0, 0).orElseThrow();
        assertEquals(0.05, shot.power(), 1e-12);
    }
}
