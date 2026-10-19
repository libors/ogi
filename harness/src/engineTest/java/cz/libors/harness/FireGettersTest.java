package cz.libors.harness;

import cz.libors.harness.recording.Recording.BulletTick;
import cz.libors.harness.recording.Recording.DebugEntry;
import cz.libors.harness.recording.Recording.RobotTick;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine-model G-49: after {@code setFireBullet} or {@code setFire} in a turn, {@code getGunHeat()} and {@code
 * getEnergy()} already include the shot (the robot's proxy adds its heat and takes its power), though the engine fires
 * it only when the turn's commands load; a second shot in the turn is therefore refused, and at the next turn's status
 * both getters are the engine's again. Checked on every shot the probes tried in the golden recordings ({@code
 * fireGetters}, {@code fireAgain}).
 */
class FireGettersTest {

    /** A probe's {@code fireGetters} entry: one shot it tried in a turn. */
    private record Shot(ProbeTrace trace, DebugEntry entry, double requested, double heatBefore, double energyBefore,
                        double heatAfter, double energyAfter, boolean fired, String call) {

        static Shot parse(ProbeTrace trace, DebugEntry d) {
            String[] f = d.value().split(",");
            return new Shot(trace, d, Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]),
                    Double.parseDouble(f[4]), Double.parseDouble(f[5]), Boolean.parseBoolean(f[6]), f[7]);
        }

        /** The power the proxy fires: clamped to [0.1, 3] and to the energy (engine-model G-06). */
        double power() {
            return Math.min(energyBefore, Math.min(Math.max(requested, 0.1), 3.0));
        }

        String where() {
            return trace.name + " round " + entry.round() + " turn " + entry.turn() + " robot " + entry.robot() + ": "
                    + entry.value();
        }
    }

    /**
     * Whether a probe's entry is of the turn it was recorded in: once one side is left the robots stop acting, and
     * their last debug properties stay in the snapshots.
     */
    private static boolean fresh(DebugEntry d, String key) {
        return d.key().equals(key) && Long.parseLong(d.value().substring(0, d.value().indexOf(','))) == d.turn();
    }

    @Test
    void theGettersIncludeTheShotSetInTheTurn() throws IOException {
        Map<String, int[]> counts = new TreeMap<>();
        for (ProbeTrace trace : ProbeTrace.loadAll()) {
            for (DebugEntry d : trace.recording.debug()) {
                if (!fresh(d, "fireGetters")) {
                    continue;
                }
                Shot s = Shot.parse(trace, d);
                assertEquals(s.heatBefore() == 0 && s.energyBefore() != 0, s.fired(),
                        "accepted with a cool gun: " + s.where());
                if (s.fired()) {
                    assertEquals(s.heatBefore() + (1 + s.power() / 5), s.heatAfter(), 0, "heat: " + s.where());
                    assertEquals(s.energyBefore() - s.power(), s.energyAfter(), 0, "energy: " + s.where());
                } else {
                    assertEquals(s.heatBefore(), s.heatAfter(), 0, "heat: " + s.where());
                    assertEquals(s.energyBefore(), s.energyAfter(), 0, "energy: " + s.where());
                }
                counts.computeIfAbsent(s.call(), k -> new int[2])[s.fired() ? 0 : 1]++;
            }
        }
        StringBuilder summary = new StringBuilder();
        counts.forEach((call, n) -> summary.append(String.format(" %s %d accepted, %d refused;", call, n[0], n[1])));
        System.out.println("Fire getters:" + summary);
        for (String call : new String[] {"setFire", "setFireBullet"}) {
            int[] n = counts.getOrDefault(call, new int[2]);
            assertTrue(n[0] > 100 && n[1] > 100, "too few shots by " + call + ": " + summary);
        }
    }

    /**
     * The proxy's view of the shot holds for its turn only: whether it accepted a shot must match the engine's
     * bullet in the next turn, also for {@code setFire}, which reports the shot only through the getters.
     */
    @Test
    void anAcceptedShotIsTheEnginesBulletInTheNextTurn() throws IOException {
        int checked = 0;
        for (ProbeTrace trace : ProbeTrace.loadAll()) {
            Set<Long> spawns = new HashSet<>();
            for (TreeMap<Integer, BulletTick> track : trace.bulletTracks().values()) {
                BulletTick first = track.firstEntry().getValue();
                spawns.add(ProbeTrace.key(first.round(), first.turn(), first.owner()));
            }
            for (DebugEntry d : trace.recording.debug()) {
                if (!fresh(d, "fireGetters") || trace.tick(d.round(), d.turn() + 1, d.robot()) == null) {
                    continue;
                }
                Shot s = Shot.parse(trace, d);
                assertEquals(s.fired(), spawns.contains(ProbeTrace.key(d.round(), d.turn() + 1, d.robot())),
                        "bullet in the next turn: " + s.where());
                checked++;
            }
        }
        assertTrue(checked > 400, "too few shots: " + checked);
    }

    /**
     * At the next turn's status the getters are the engine's values again: the gun heat and energy before the call
     * equal the robot's record of that turn, also right after an accepted shot, whose heat and power the proxy no
     * longer adds.
     */
    @Test
    void theGettersAreTheEnginesAgainInTheNextTurn() throws IOException {
        int checked = 0;
        int afterAShot = 0;
        for (ProbeTrace trace : ProbeTrace.loadAll()) {
            Set<Long> accepted = new HashSet<>();
            for (DebugEntry d : trace.recording.debug()) {
                if (fresh(d, "fireGetters") && Shot.parse(trace, d).fired()) {
                    accepted.add(ProbeTrace.key(d.round(), d.turn(), d.robot()));
                }
            }
            for (DebugEntry d : trace.recording.debug()) {
                if (!fresh(d, "fireGetters")) {
                    continue;
                }
                Shot s = Shot.parse(trace, d);
                RobotTick status = trace.tick(d.round(), d.turn(), d.robot());
                assertNotNull(status, s.where());
                assertEquals(status.gunHeat(), s.heatBefore(), 0, "heat at the turn's status: " + s.where());
                assertEquals(status.energy(), s.energyBefore(), 0, "energy at the turn's status: " + s.where());
                checked++;
                afterAShot += accepted.contains(ProbeTrace.key(d.round(), d.turn() - 1, d.robot())) ? 1 : 0;
            }
        }
        System.out.printf("Fire getters at the turn's status: %d shots, %d of them the turn after a shot%n", checked,
                afterAShot);
        assertTrue(afterAShot > 50, "too few shots right after a shot: " + afterAShot);
    }

    /** A second {@code setFireBullet} in the turn of an accepted shot is refused and changes no getter. */
    @Test
    void aSecondShotInTheTurnIsRefused() throws IOException {
        int checked = 0;
        for (ProbeTrace trace : ProbeTrace.loadAll()) {
            Map<Long, Shot> firsts = new HashMap<>();
            for (DebugEntry d : trace.recording.debug()) {
                if (fresh(d, "fireGetters")) {
                    firsts.put(ProbeTrace.key(d.round(), d.turn(), d.robot()), Shot.parse(trace, d));
                }
            }
            for (DebugEntry d : trace.recording.debug()) {
                if (!fresh(d, "fireAgain")) {
                    continue;
                }
                String[] f = d.value().split(",");
                Shot first = firsts.get(ProbeTrace.key(d.round(), d.turn(), d.robot()));
                String where = trace.name + " round " + d.round() + " turn " + d.turn() + ": " + d.value();
                assertTrue(first != null && first.fired(), "a second shot without an accepted first: " + where);
                assertFalse(Boolean.parseBoolean(f[2]), "second shot accepted: " + where);
                assertEquals(first.heatAfter(), Double.parseDouble(f[3]), 0, "heat: " + where);
                assertEquals(first.energyAfter(), Double.parseDouble(f[4]), 0, "energy: " + where);
                checked++;
            }
        }
        System.out.printf("Second shots in a turn: %d, all refused%n", checked);
        assertTrue(checked > 100, "too few second shots: " + checked);
    }
}
