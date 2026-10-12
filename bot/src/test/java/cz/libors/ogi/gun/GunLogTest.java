package cz.libors.ogi.gun;

import cz.libors.ogi.geom.Field;
import cz.libors.ogi.world.EnemyState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GunLogTest {

    private static final Field FIELD = new Field(800, 600);

    @Test
    void linesParseBackToTheSameValues() {
        double[] features = {0, -0.0, 1e-300, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 1};
        GunModel.Shot shot = new GunModel.Shot("a.B 1.0, beta", 17, features, -1, -3.0000000000000004, 0.6,
                true, Double.NaN);
        GunModel.Aim aim = new GunModel.Aim(Double.NaN, new double[] {Double.NaN, 1.25}, GunModel.CIRCULAR);
        GunLog.LoggedShot logged = GunLog.shot(GunLog.line(shot, aim));
        GunModel.Shot back = logged.shot();
        assertEquals(shot.target(), back.target());
        assertEquals(shot.time(), back.time());
        assertArrayEquals(features, back.features());
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(back.features()[1]));
        assertEquals(shot.direction(), back.direction());
        assertEquals(shot.reference(), back.reference());
        assertEquals(shot.escape(), back.escape());
        assertTrue(back.search());
        assertTrue(Double.isNaN(back.circularAim()));
        assertTrue(Double.isNaN(logged.guessFactor()));
        assertEquals(GunModel.CIRCULAR, logged.gun());

        GunModel.Pass miss = new GunModel.Pass("x", 3, false, 0.5, 0.7, false, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY);
        assertEquals(miss, GunLog.pass(GunLog.line(miss)));
        GunModel.Pass hit = new GunModel.Pass("x, y", 4, true, -1.5, 0.7, true, -0.01, 0.02);
        assertEquals(hit, GunLog.pass(GunLog.line(hit)));

        assertThrows(IllegalArgumentException.class, () -> GunLog.pass("1,2,3"));
        assertThrows(IllegalArgumentException.class, () -> GunLog.pass("4,2,-1.5,0.7,1,-0.01,0.02,x"));
    }

    @Test
    void theRandomGunsRangeAndDrawParseBack() {
        double[] features = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1};
        GunModel.Shot shot = new GunModel.Shot("a.B 1.0, beta", 17, features, 1, 0.5, 0.6, true, 0.25,
                -0.7300000000000001, 0.9, 0.123456789);
        GunModel.Aim aim = new GunModel.Aim(0.3, new double[] {0.1, 0.25, 0.2, 0.4}, GunModel.RANDOM);
        GunLog.LoggedShot logged = GunLog.shot(GunLog.line(shot, aim));
        assertEquals(shot.target(), logged.shot().target());
        assertEquals(shot.randomLow(), logged.shot().randomLow());
        assertEquals(shot.randomHigh(), logged.shot().randomHigh());
        assertEquals(shot.randomDraw(), logged.shot().randomDraw());
        assertEquals(GunModel.RANDOM, logged.gun());
    }

    @Test
    void aLineFromBeforeTheRandomGunIsReadWithoutItsRange() {
        String old = "17,1,-1,0.5,0.6,NaN,0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,1.0,0.3,2,a.B 1.0, beta";
        GunLog.LoggedShot logged = GunLog.shot(old);
        assertEquals("a.B 1.0, beta", logged.shot().target());
        assertEquals(0.3, logged.guessFactor());
        assertEquals(GunModel.ANTI_SURFER, logged.gun());
        assertTrue(Double.isNaN(logged.shot().randomLow()));
        assertTrue(Double.isNaN(logged.shot().randomDraw()));
        assertEquals(1.0, logged.shot().features()[9]);
    }

    /**
     * Replays the log of a simulated fight with a fresh model, as the gun bench does: every decision comes out the
     * same, bit for bit.
     */
    @Test
    void aReplayOfTheLogDecidesAsTheGunDid() {
        String name = "replay-target";
        GuessFactorGun gun = new GuessFactorGun(FIELD);
        double ourX = 200;
        double ourY = 300;
        Random random = new Random(7);
        List<List<String>> passLines = new ArrayList<>();
        List<String> shotLines = new ArrayList<>();
        double angle = 0;
        int direction = 1;
        for (long t = 0; t <= 1500; t++) {
            // A target orbiting us at 300 px that reverses at random.
            if (random.nextDouble() < 0.04) {
                direction = -direction;
            }
            angle += direction * 0.02;
            double x = ourX + 300 * Math.cos(angle);
            double y = ourY + 300 * Math.sin(angle);
            double heading = angle + direction * Math.PI / 2;
            gun.scanned(name, t, x, y, heading, 6);
            gun.update(t, ourX, ourY, t % 15 == 0 ? name : null);
            passLines.add(gun.passed().stream().map(GunLog::line).toList());
            EnemyState state = new EnemyState(name, x, y, heading, 6, 0, 100, t);
            double circular = CircularAim.aim(ourX, ourY, state, 14, t + 1, FIELD);
            gun.aim(name, t, ourX, ourY, 2, t % 15 > 10, circular);
            GuessFactorGun.Plan plan = gun.plan(name, t).orElseThrow();
            shotLines.add(GunLog.line(plan.shot(), plan.decision()));
        }

        GunModel model = new GunModel(GunParameters.DEFAULT);
        Map<Long, GunModel.Shot> shots = new HashMap<>();
        Map<Long, GunModel.Aim> aims = new HashMap<>();
        int passes = 0;
        int searches = 0;
        int guessFactorChosen = 0;
        for (int i = 0; i < shotLines.size(); i++) {
            for (String line : passLines.get(i)) {
                GunModel.Pass pass = GunLog.pass(line);
                model.learn(shots.get(pass.time()), aims.get(pass.time()), pass);
                passes++;
            }
            GunLog.LoggedShot logged = GunLog.shot(shotLines.get(i));
            GunModel.Aim aim = model.aim(logged.shot());
            assertEquals(Double.doubleToLongBits(logged.guessFactor()), Double.doubleToLongBits(aim.guessFactor()),
                    "turn " + i);
            assertEquals(logged.gun(), aim.gun(), "turn " + i);
            shots.put(logged.shot().time(), logged.shot());
            aims.put(logged.shot().time(), aim);
            searches += Double.isNaN(aim.guessFactor()) ? 0 : 1;
            guessFactorChosen += aim.gun() == GunModel.GUESS_FACTOR ? 1 : 0;
        }
        assertTrue(passes > 1000, "passes: " + passes);
        assertTrue(searches > 300, "searches: " + searches);
        assertTrue(guessFactorChosen > 0, "the guess-factor gun was never chosen");
        assertEquals(gun.dataSize(name), model.dataSize());
        for (int g = 0; g < GunModel.GUNS; g++) {
            assertEquals(Double.doubleToLongBits(gun.hitRate(name, g)), Double.doubleToLongBits(model.hitRate(g)));
        }
        assertFalse(Double.isNaN(model.hitRate(GunModel.GUESS_FACTOR)));
    }
}
