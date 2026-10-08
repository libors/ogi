package cz.libors.ogi.trace;

import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.CircularAim;
import cz.libors.ogi.gun.GuessFactorGun;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paints the trace of a gun that has fought a simulated mover into {@code build/overlay/gun.png}, with the battlefield
 * mirrored as Robocode mirrors robot graphics, so that the overlay can be looked at without the GUI.
 */
class OverlayRenderTest {

    @Test
    void paintsWavesAndThePlan() throws IOException {
        Field field = new Field(800, 600);
        GuessFactorGun gun = new GuessFactorGun(field);
        double ourX = 200;
        double ourY = 300;
        Trace trace = new Trace();
        for (long t = 0; t <= 400; t++) {
            // A target orbiting us at 300 px that reverses every 37 turns.
            boolean forward = t % 74 < 37;
            double angle = 0.02 * (forward ? t % 37 : 37 - t % 37);
            double x = ourX + 300 * Math.cos(angle);
            double y = ourY + 300 * Math.sin(angle);
            double heading = angle + (forward ? Math.PI / 2 : -Math.PI / 2);
            gun.scanned("target", t, x, y, heading, 6);
            gun.update(t, ourX, ourY, t % 15 == 0 ? "target" : null);
            EnemyState state = new EnemyState("target", x, y, heading, 6, 0, 100, t);
            double circular = CircularAim.aim(ourX, ourY, state, 14, t + 1, field);
            gun.aim("target", t, ourX, ourY, 2, true, circular);
            if (t == 400) {
                trace.start(t, field.height());
                gun.describe(t, trace);
            }
        }
        assertFalse(trace.waves().isEmpty());
        assertFalse(trace.guns().isEmpty());

        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(30, 30, 30));
        g.fillRect(0, 0, 800, 600);
        g.setTransform(new AffineTransform(1, 0, 0, -1, 0, 600));
        Overlay.paint(g, trace);
        g.dispose();
        Path out = Path.of("build/overlay/gun.png");
        Files.createDirectories(out.getParent());
        ImageIO.write(image, "png", out.toFile());
        assertTrue(Files.size(out) > 1000);
    }
}
