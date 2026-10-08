package cz.libors.ogi.trace;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.Locale;

/**
 * Paints a {@link Trace} with Robocode's robot graphics, the one place that turns the trace into pixels.
 *
 * <p>Robocode hands robots a graphics mirrored to battlefield coordinates (y up), so points in the mathematical
 * convention are drawn as they are. Arcs are drawn as polylines from such points, never with {@code Arc2D}, whose
 * angles would turn the other way in the mirrored graphics.
 */
public final class Overlay {

    private static final Color VIRTUAL_WAVE = new Color(160, 160, 160, 40);
    private static final Color REAL_WAVE = new Color(255, 170, 60, 200);
    private static final Color ENEMY_WAVE = new Color(255, 80, 80, 220);
    private static final Color ENEMY_HIT_RANGE = new Color(255, 230, 80, 230);
    private static final Color HIT_RANGE = new Color(90, 220, 90, 220);
    private static final Color REFERENCE = new Color(200, 200, 200, 120);
    private static final Color ESCAPE = new Color(120, 120, 120, 120);
    private static final Color[] GUN_COLORS = {new Color(90, 220, 90), new Color(80, 170, 255)};
    private static final Color TEXT = new Color(230, 230, 230);
    private static final int CIRCLE_SEGMENTS = 72;
    private static final int ARC_SEGMENTS = 12;

    private Overlay() {
    }

    public static void paint(Graphics2D g, Trace trace) {
        for (Trace.WaveView w : trace.waves()) {
            paintWave(g, w);
        }
        int line = 0;
        for (Trace.GunView gun : trace.guns()) {
            paintGun(g, gun);
            paintGunText(g, trace, gun, line++);
        }
    }

    private static void paintWave(Graphics2D g, Trace.WaveView w) {
        if (w.radius() <= 0) {
            return;
        }
        g.setStroke(new BasicStroke(w.real() ? 1.5f : 1f));
        g.setColor(w.enemy() ? ENEMY_WAVE : w.real() ? REAL_WAVE : VIRTUAL_WAVE);
        g.draw(arc(w.x(), w.y(), w.radius(), 0, 2 * Math.PI, CIRCLE_SEGMENTS));
        if (!Double.isNaN(w.minOffset())) {
            g.setStroke(new BasicStroke(w.real() ? 4f : 2f));
            g.setColor(w.enemy() ? ENEMY_HIT_RANGE : HIT_RANGE);
            g.draw(arc(w.x(), w.y(), w.radius(), w.reference() + w.minOffset(), w.reference() + w.maxOffset(),
                    ARC_SEGMENTS));
        }
    }

    private static void paintGun(Graphics2D g, Trace.GunView gun) {
        double length = Math.hypot(gun.targetX() - gun.originX(), gun.targetY() - gun.originY()) + 60;
        g.setStroke(new BasicStroke(1f));
        g.setColor(ESCAPE);
        ray(g, gun.originX(), gun.originY(), gun.reference() + gun.escape(), length);
        ray(g, gun.originX(), gun.originY(), gun.reference() - gun.escape(), length);
        g.setColor(REFERENCE);
        ray(g, gun.originX(), gun.originY(), gun.reference(), length);
        for (int i = 0; i < gun.aims().length; i++) {
            if (Double.isNaN(gun.aims()[i])) {
                continue;
            }
            g.setStroke(new BasicStroke(i == gun.chosen() ? 2.5f : 1f));
            g.setColor(GUN_COLORS[i % GUN_COLORS.length]);
            ray(g, gun.originX(), gun.originY(), gun.aims()[i], length);
        }
    }

    private static void paintGunText(Graphics2D g, Trace trace, Trace.GunView gun, int line) {
        StringBuilder sb = new StringBuilder(gun.target()).append(": ");
        for (int i = 0; i < gun.gunNames().length; i++) {
            sb.append(i == gun.chosen() ? "[" : "").append(gun.gunNames()[i])
                    .append(String.format(Locale.ROOT, " %.1f%%", 100 * gun.hitRates()[i]))
                    .append(i == gun.chosen() ? "]" : "").append("  ");
        }
        sb.append("data ").append(gun.data());
        g.setColor(TEXT);
        g.drawString(sb.toString(), 10, (float) (trace.fieldHeight() - 15 - 14 * line));
    }

    private static void ray(Graphics2D g, double x, double y, double angle, double length) {
        g.draw(new Line2D.Double(x, y, x + length * Math.cos(angle), y + length * Math.sin(angle)));
    }

    /** A polyline along the circle from angle {@code from} to {@code to}, counter-clockwise. */
    static Path2D arc(double cx, double cy, double r, double from, double to, int segments) {
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i <= segments; i++) {
            double a = from + (to - from) * i / segments;
            double px = cx + r * Math.cos(a);
            double py = cy + r * Math.sin(a);
            if (i == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        return path;
    }
}
