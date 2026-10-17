package cz.libors.harness;

import cz.libors.harness.recording.Recording.DebugEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine-model G-49: after {@code setFireBullet} in a turn, {@code getGunHeat()} and {@code getEnergy()} already
 * include the shot (the robot's proxy adds its heat and takes its power), though the engine fires it only when the
 * turn's commands load. Checked on every shot the probes tried in the golden recordings ({@code fireGetters}).
 */
class FireGettersTest {

    @Test
    void theGettersIncludeTheShotSetInTheTurn() throws IOException {
        int accepted = 0;
        int refused = 0;
        for (ProbeTrace trace : ProbeTrace.loadAll()) {
            for (DebugEntry d : trace.recording.debug()) {
                if (!d.key().equals("fireGetters")) {
                    continue;
                }
                String[] f = d.value().split(",");
                double requested = Double.parseDouble(f[1]);
                double heatBefore = Double.parseDouble(f[2]);
                double energyBefore = Double.parseDouble(f[3]);
                double heatAfter = Double.parseDouble(f[4]);
                double energyAfter = Double.parseDouble(f[5]);
                boolean fired = Boolean.parseBoolean(f[6]);
                String where = trace.name + " round " + d.round() + " turn " + d.turn() + ": " + d.value();
                assertEquals(heatBefore == 0 && energyBefore != 0, fired, "accepted with a cool gun: " + where);
                if (fired) {
                    // The power the proxy fires: clamped to [0.1, 3] and to the energy (engine-model G-06).
                    double p = Math.min(energyBefore, Math.min(Math.max(requested, 0.1), 3.0));
                    assertEquals(heatBefore + (1 + p / 5), heatAfter, 0, "heat: " + where);
                    assertEquals(energyBefore - p, energyAfter, 0, "energy: " + where);
                    accepted++;
                } else {
                    assertEquals(heatBefore, heatAfter, 0, "heat: " + where);
                    assertEquals(energyBefore, energyAfter, 0, "energy: " + where);
                    refused++;
                }
            }
        }
        System.out.printf("Fire getters: %d shots accepted, %d refused%n", accepted, refused);
        assertTrue(accepted > 100 && refused > 100, "too few shots in the recordings: " + accepted + ", " + refused);
    }
}
