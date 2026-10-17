package cz.libors.ogi.trace;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.CircularAim;
import cz.libors.ogi.gun.GuessFactorGun;
import cz.libors.ogi.gun.MeleeGun;
import cz.libors.ogi.gun.MeleeGunParameters;
import cz.libors.ogi.move.MeleeMovement;
import cz.libors.ogi.radar.TeamRadar;
import cz.libors.ogi.world.EnemyState;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paints every layer of the overlay into {@code build/overlay/overlay.png}, with the battlefield mirrored as Robocode
 * mirrors robot graphics (its text is upside down), so that the overlay can be looked at without the GUI: one of our
 * bullets with the weights of a gun that has fought a simulated mover, an enemy wave with a danger profile, and a
 * team's radar plan, and the melee movement's choice among those robots.
 */
class OverlayRenderTest {

    @Test
    void paintsEveryLayer() throws IOException {
        Field field = new Field(1000, 800);
        double ourX = 300;
        double ourY = 400;
        Trace trace = new Trace();
        trace.start(400, field.width(), field.height());

        // Our bullet: a gun that has learned a target orbiting us at 300 px, reversing every 37 turns.
        GuessFactorGun gun = new GuessFactorGun(field);
        EnemyState state = null;
        for (long t = 0; t <= 400; t++) {
            boolean forward = t % 74 < 37;
            double angle = 0.02 * (forward ? t % 37 : 37 - t % 37);
            double x = ourX + 300 * Math.cos(angle);
            double y = ourY + 300 * Math.sin(angle);
            double heading = angle + (forward ? Math.PI / 2 : -Math.PI / 2);
            gun.scanned("target", t, x, y, heading, 6);
            gun.update(t, ourX, ourY, t % 15 == 0 ? "target" : null);
            state = new EnemyState("target", x, y, heading, 6, forward ? 0.02 : -0.02, 100, t);
            double circular = CircularAim.aim(ourX, ourY, state, 14, t + 1, field);
            gun.aim("target", t, ourX, ourY, 2, true, circular);
        }
        double[][] weights = gun.planCoverage("target", 47);
        assertNotNull(weights);
        double headOn = Angles.bearing(ourX, ourY, state.x(), state.y());
        EnemyState straight = new EnemyState("target", state.x(), state.y(), state.heading(), 6, 0, 100, 400);
        ShotTrails trails = new ShotTrails();
        trails.planned(new ShotTrails.Planned(399, "target", state.x(), state.y(), headOn, Math.asin(8 / 14.0),
                weights[0], weights[1],
                new double[] {headOn, CircularAim.aim(ourX, ourY, straight, 14, 400, field),
                        CircularAim.aim(ourX, ourY, state, 14, 400, field)}));
        double fired = weights[0][30];
        trails.fired(400, ourX, ourY, 2, () -> new double[] {ourX + 14 * 12 * Math.cos(fired),
                ourY + 14 * 12 * Math.sin(fired)}, () -> new double[] {600, 420});
        trails.describe(412, trace);
        gun.describe(401, trace);
        trace.lastShot("target", "gf");
        trace.meleeHitRate(0.31);

        // An enemy wave fired at us from (750, 250): two peaks, one shadowed by our bullet.
        double reference = Angles.bearing(750, 250, ourX, ourY);
        double escape = Math.asin(8 / 14.0);
        int bins = 47;
        double[] bearings = new double[bins];
        double[] profile = new double[bins];
        boolean[] shadowed = new boolean[bins];
        for (int i = 0; i < bins; i++) {
            double gf = (i - 23) / 23.0;
            bearings[i] = reference + gf * escape;
            profile[i] = Math.exp(-Math.pow((gf - 0.4) / 0.15, 2)) + 0.5 * Math.exp(-Math.pow((gf + 0.5) / 0.1, 2));
            shadowed[i] = gf > 0.3 && gf < 0.4;
        }
        trace.add(new Trace.WaveView(750, 250, 330, true, 2.4, reference, escape, reference + 0.4 * escape,
                reference + 0.48 * escape, bearings, profile, shadowed,
                new double[] {reference, reference + 0.7 * escape, reference + 0.6 * escape}, Double.NaN, Double.NaN));
        trace.add(new Trace.WaveView(820, 650, 120, true, 0.95, Angles.bearing(820, 650, ourX, ourY),
                Math.asin(8 / 17.15), Double.NaN,
                Double.NaN, null, null, null, null, Double.NaN, Double.NaN));

        // The melee movement among the robots below, with a few recent positions of ours.
        MeleeMovement movement = new MeleeMovement(field, new Random(3));
        movement.tracing(true);
        List<MeleeMovement.Robot> robots = List.of(new MeleeMovement.Robot(600, 420, 100, true),
                new MeleeMovement.Robot(750, 250, 100, true), new MeleeMovement.Robot(820, 650, 100, true),
                new MeleeMovement.Robot(450, 720, 100, true), new MeleeMovement.Robot(150, 700, 100, false),
                new MeleeMovement.Robot(500, 120, 100, false));
        for (int t = 0; t <= 20; t += 5) {
            movement.command(395 + t, ourX - 2 * t, ourY - t, 0, 100, robots);
        }
        trace.move(movement.traced());
        assertNotNull(trace.move());

        // Our positions over the last turns, and two enemies scanned every other turn.
        PositionTrails trails2 = new PositionTrails();
        for (long t = 330; t <= 412; t++) {
            double a = (t - 330) * 0.03;
            trails2.ours(t, ourX - 2 * (412 - t) * 0.5 + 40 * Math.sin(a), ourY + 60 * Math.cos(a) - 60);
            if (t % 2 == 0) {
                trails2.enemy("enemy (1)", t, 600 + 50 * Math.cos(a), 420 + 50 * Math.sin(a));
                trails2.enemy("enemy (2)", t, 750 - (412 - t) * 0.8, 250 + (412 - t) * 0.5);
            }
        }
        trails2.describe(412, trace);
        assertNotNull(trace.positions());

        // The melee gun's aim at three enemies wandering about.
        MeleeGun meleeGun = new MeleeGun(field, new MeleeGun.Memory(), MeleeGunParameters.DEFAULT);
        Random walk = new Random(5);
        double[] ex = {600, 750, 450};
        double[] ey = {420, 250, 620};
        double[] eh = {0, 2, 4};
        for (long t = 0; t <= 400; t++) {
            for (int i = 0; i < 3; i++) {
                eh[i] += 0.15 * (walk.nextDouble() - 0.5);
                double v = 8 * walk.nextDouble();
                ex[i] = Math.max(60, Math.min(940, ex[i] + v * Math.cos(eh[i])));
                ey[i] = Math.max(60, Math.min(740, ey[i] + v * Math.sin(eh[i])));
                meleeGun.scanned("e" + i, t, ex[i], ey[i], eh[i], v, ourX, ourY, 3);
            }
        }
        meleeGun.tracing(true);
        meleeGun.aim(401, ourX, ourY, 14);
        MeleeGun.Traced mt = meleeGun.traced();
        assertNotNull(mt);
        List<String> names = new ArrayList<>(mt.enemyX().keySet());
        names.sort(null);
        int[] owner = new int[mt.enemies().length];
        for (int i = 0; i < owner.length; i++) {
            owner[i] = names.indexOf(mt.enemies()[i]);
        }
        double[] mex = new double[names.size()];
        double[] mey = new double[names.size()];
        for (int k = 0; k < names.size(); k++) {
            mex[k] = mt.enemyX().get(names.get(k))[0];
            mey[k] = mt.enemyX().get(names.get(k))[1];
        }
        trace.meleeAim(new Trace.MeleeAimView(400, ourX, ourY, mt.aim(), mt.xs(), mt.ys(), mt.density(),
                mt.weights(), owner, mex, mey, names.indexOf(mt.aimedAt())));

        // A team's radar plan: three of ours, four enemies.
        TeamRadar radar = new TeamRadar();
        radar.reported(new TeamRadar.Watcher("ours (1)", ourX, ourY, 0.3, 411));
        radar.reported(new TeamRadar.Watcher("ours (2)", 150, 700, -0.5, 411));
        radar.reported(new TeamRadar.Watcher("ours (3)", 500, 120, 1.2, 411));
        radar.scanned("enemy (1)", 600, 420, 411);
        radar.scanned("enemy (2)", 750, 250, 410);
        radar.scanned("enemy (3)", 820, 650, 411);
        radar.scanned("enemy (4)", 450, 720, 406);
        radar.watch(412, "ours (1)");
        trace.radar(radar.describe(412, "ours (1)"));
        assertNotNull(trace.radar());

        // The bullets' powers: two of our team's bullets in flight, two enemies' last shots.
        trace.powers(new Trace.PowerView(new double[][] {{300, 520, 1.9}, {640, 300, 3.0}},
                new double[][] {{600, 420, 2.4}, {820, 650, 0.9}}));
        // Beside the enemies: the turns until their guns are cool (one can fire now), our hit rate on two of them.
        trace.info(new Trace.InfoView(new double[][] {{600, 420, 7, 0.31, 0.12, 1}, {820, 650, 0, Double.NaN, 0.2, 4},
                {750, 250, -1, 0.12, Double.NaN, -1}}));
        // Our states: ganged and helped by the teammate at (300, 520).
        trace.states(new Trace.StateView(400, 400, java.util.List.of("anti-ram", "ganged"), true, 300, 520));
        assertNotNull(trace.states());

        BufferedImage image = new BufferedImage((int) field.width(), (int) field.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(30, 30, 30));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setTransform(new AffineTransform(1, 0, 0, -1, 0, field.height()));
        Overlay.showAll();
        Overlay.paint(g, trace);
        g.dispose();
        Path out = Path.of("build/overlay/overlay.png");
        Files.createDirectories(out.getParent());
        ImageIO.write(image, "png", out.toFile());
        assertTrue(Files.size(out) > 1000);
    }
}
