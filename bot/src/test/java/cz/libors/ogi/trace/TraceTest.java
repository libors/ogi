package cz.libors.ogi.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TraceTest {

    @Test
    void gunLineKeepsTheFieldOrderTheHarnessReads() {
        Trace.GunView view = new Trace.GunView("sample.SpinBot", 41, 100.5, 200.25, 300, 400, 0.75, 0.6, 1.9, -1,
                new double[] {Double.NaN, 0.5}, 1, new String[] {"gf", "circular"}, new double[] {0.25, Double.NaN},
                12, new double[] {0.2, Double.NaN}, new int[] {10, 0}, false);

        assertEquals("41,1,1,NaN,0.5,100.5,200.25,0.75,-1,1.9,12,0.25,NaN,sample.SpinBot",
                Trace.gunLine(view, true));
    }
}
