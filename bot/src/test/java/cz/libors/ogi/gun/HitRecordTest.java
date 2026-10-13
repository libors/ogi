package cz.libors.ogi.gun;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitRecordTest {

    @Test
    void theRateIsUndefinedBeforeTheFirstBulletEnds() {
        HitRecord record = new HitRecord();
        assertEquals(0, record.ended());
        assertTrue(Double.isNaN(record.rate()));
    }

    @Test
    void theRateIsHitsPerBulletEnded() {
        HitRecord record = new HitRecord();
        record.ended(true);
        record.ended(false);
        record.ended(false);
        record.ended(true);
        assertEquals(4, record.ended());
        assertEquals(0.5, record.rate());
    }
}
