package cz.libors.ogi.trace;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Paints a {@link Trace} with Robocode's robot graphics, the one place that turns the trace into pixels.
 *
 * <p>Seven layers, each switched by a key while the battle view has the focus (Robocode hands key presses to every
 * robot that overrides {@code onKeyPressed}), with a legend in the top left corner; which are shown at first depends
 * on the kind of battle ({@link #battle}):
 * <ul>
 *   <li><b>E, enemy waves:</b> grey arcs around where the shooter stood, over the maximum escape angle either side
 *       of the line from there towards us at the shot (a head-on aim), which ends on the wave with the bullet's power
 *       beyond it. The waves that reach us first have their arc coloured by the danger model's profile, blue
 *       unlikely to red likely, green where our bullets shadow them, and marks where head-on (a cross in a circle),
 *       linear (two lines) and circular targeting (a circle) aimed; a thin yellow arc just outside where the wave
 *       crosses us.</li>
 *   <li><b>G, our waves:</b> dark red arcs for our bullets, small dots of the gun's weight just behind the front, the
 *       bullet itself yellow with its power beside it; the guns' records at the bottom left, one a line, the gun of
 *       our last bullet in yellow.</li>
 *   <li><b>M, movement</b> (melee and team): every candidate destination of the minimum-risk movement as a dark area
 *       coloured by its risk, blue safest to red, ringed where it was also scored against the waves; the chosen one
 *       ringed in dark orange and joined to us by a dashed line.</li>
 *   <li><b>P (or H), our positions:</b> where we were in each of the last turns, violet dots fading with age.</li>
 *   <li><b>O, opponents' positions:</b> the enemies' scanned positions, turquoise, fading likewise.</li>
 *   <li><b>A, melee aim:</b> the melee gun's last aim: the predicted positions of every enemy where the bullet
 *       arrives, coloured by the weight on their bearing, a line from each enemy to the middle of its positions, the
 *       aim itself.</li>
 *   <li><b>R, team radar:</b> each of our robots in a colour of its own, with the arc its radar sweeps over the enemies
 *       the team's plan gives it, a line to each of them and a short one where its radar points; each enemy ringed in
 *       its watcher's colour, with the turns since the team's last scan of it.</li>
 * </ul>
 *
 * <p>Robocode hands robots a graphics mirrored to battlefield coordinates (y up), so points in the mathematical
 * convention are drawn as they are. Arcs are drawn as polylines from such points, never with {@code Arc2D}, whose
 * angles would turn the other way in the mirrored graphics.
 */
public final class Overlay {

    private static final Color ENEMY_WAVE = new Color(120, 120, 120);
    private static final Color OUR_WAVE = new Color(170, 35, 35);
    /** The line from a wave's origin to its front: dark, so that it does not cover the rest. */
    private static final Color ENEMY_LINE = new Color(42, 42, 42);
    private static final Color OUR_LINE = new Color(55, 14, 14);
    private static final Color HIT_RANGE = new Color(255, 230, 80, 230);
    private static final Color AIM_MARK = new Color(200, 200, 200);
    /** Our bullet on its wave, the colour {@code Ogi} gives its bullets in the battle view. */
    private static final Color BULLET = new Color(255, 230, 40);
    private static final Color ENEMY_POWER = new Color(190, 190, 190);
    private static final Color OUR_POWER = new Color(235, 90, 90);
    private static final Color SHADOW = new Color(50, 255, 50);
    private static final Color UNLIKELY = new Color(40, 90, 255);
    private static final Color LIKELY = new Color(255, 40, 40);
    private static final Color TEXT = new Color(230, 230, 230);
    private static final Color GUN_TEXT = new Color(170, 170, 170);
    private static final Color LAYER_ON = new Color(90, 220, 90);
    private static final Color LAYER_OFF = new Color(200, 70, 70);
    private static final Color UNWATCHED = new Color(150, 150, 150);
    private static final Color STALE = new Color(255, 80, 80);
    private static final Color WAVED = new Color(110, 110, 110);
    /** The movement's candidates: dark areas, unlike the bright dots of the waves. */
    private static final Color SAFE_AREA = new Color(14, 22, 72);
    private static final Color RISKY_AREA = new Color(76, 16, 16);
    private static final double AREA_RADIUS = 8;
    private static final Color DESTINATION = new Color(200, 100, 20);
    private static final BasicStroke DASHED = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            10f, new float[] {6f, 5f}, 0f);
    private static final Color OUR_TRAIL = new Color(170, 90, 255);
    private static final Color PREDICTED = new Color(220, 220, 220, 150);
    private static final Color MELEE_AIM_RAY = new Color(255, 200, 80, 200);
    private static final Color OPPONENT_TRAIL = new Color(64, 224, 208);
    /** A trail's newest dot has this opacity, its oldest none. */
    private static final int TRAIL_ALPHA = 220;
    private static final Color PULL = new Color(200, 120, 255);
    /** Our robots' colours in the radar layer, by their place in the plan. */
    private static final Color[] WATCHERS = {new Color(0, 200, 255), new Color(255, 90, 200), new Color(150, 255, 80),
            new Color(255, 170, 40), new Color(170, 120, 255)};
    private static final int CIRCLE_SEGMENTS = 72;
    private static final int ARC_SEGMENTS = 12;
    private static final int WAVE_SEGMENTS = 32;
    /** Our gun's dots lie this far behind the wave's front. */
    private static final double DOTS_BEHIND = 4;
    /** The headings that hit us are drawn this far outside the wave's front, off its colours. */
    private static final double HIT_RANGE_OUT = 4;
    /** A scan this many turns old or older is marked stale. */
    private static final long STALE_AGE = 3;

    /** The layers: their keys, names and whether each is shown (statics last for the battle). */
    private static final char[] KEYS = {'e', 'g', 'm', 'r', 'p', 'o', 'a'};
    private static final String[] NAMES = {"enemy waves", "our waves", "movement", "team radar", "our positions",
            "opponents' positions", "melee aim"};
    private static final boolean[] SHOWN = new boolean[KEYS.length];
    private static final int ENEMY_WAVES = 0;
    private static final int OUR_WAVES = 1;
    private static final int MOVE = 2;
    private static final int RADAR = 3;
    private static final int POSITIONS = 4;
    private static final int OPPONENTS = 5;
    private static final int MELEE_AIM = 6;
    /** H (history) switches our positions too, as P does. */
    private static final char POSITIONS_ALIAS = 'h';
    /** The kinds of battle, for the layers shown at its start ({@link #battle}). */
    public static final int DUEL = 0;
    public static final int MELEE = 1;
    public static final int TEAM = 2;
    /** Whether the layers of this battle's kind were set: at the battle's first paint. */
    private static boolean kindSet;

    private Overlay() {
    }

    /**
     * The layers shown when a battle's painting starts: in a duel both kinds of waves; in a melee the movement, and in a
     * team battle the radar, where the waves of all robots would cover each other. Keys change them afterwards.
     */
    public static void battle(int kind) {
        if (kindSet) {
            return;
        }
        kindSet = true;
        SHOWN[ENEMY_WAVES] = kind == DUEL;
        SHOWN[OUR_WAVES] = kind == DUEL;
        SHOWN[MOVE] = kind == MELEE;
        SHOWN[RADAR] = kind == TEAM;
        SHOWN[POSITIONS] = true;
        SHOWN[OPPONENTS] = false;
        SHOWN[MELEE_AIM] = kind == MELEE;
    }

    /** Shows every layer, for a test that paints them all. */
    static void showAll() {
        kindSet = true;
        Arrays.fill(SHOWN, true);
    }

    /** Switches the layer of this key, if it has one. */
    public static void key(char c) {
        char k = Character.toLowerCase(c) == POSITIONS_ALIAS ? KEYS[POSITIONS] : Character.toLowerCase(c);
        for (int i = 0; i < KEYS.length; i++) {
            if (k == KEYS[i]) {
                SHOWN[i] = !SHOWN[i];
            }
        }
    }

    public static void paint(Graphics2D g, Trace trace) {
        Trace.PositionView positions = trace.positions();
        if (positions != null && SHOWN[OPPONENTS]) {
            paintTrail(g, positions.enemies(), OPPONENT_TRAIL);
        }
        if (positions != null && SHOWN[POSITIONS]) {
            paintTrail(g, positions.ours(), OUR_TRAIL);
        }
        if (SHOWN[RADAR] && trace.radar() != null) {
            paintRadar(g, trace.radar());
        }
        if (SHOWN[MOVE] && trace.move() != null) {
            paintMove(g, trace.move());
        }
        if (SHOWN[MELEE_AIM] && trace.meleeAim() != null) {
            paintMeleeAim(g, trace.meleeAim());
        }
        for (Trace.WaveView w : trace.waves()) {
            if (SHOWN[w.enemy() ? ENEMY_WAVES : OUR_WAVES]) {
                paintWave(g, w);
            }
        }
        if (SHOWN[OUR_WAVES]) {
            paintGuns(g, trace);
        }
        paintLegend(g, trace);
    }

    private static void paintWave(Graphics2D g, Trace.WaveView w) {
        double r = w.radius();
        if (r <= 0) {
            return;
        }
        g.setStroke(new BasicStroke(1f));
        g.setColor(w.enemy() ? ENEMY_LINE : OUR_LINE);
        dot(g, w.x(), w.y(), 2.5);
        double ex = w.x() + r * Math.cos(w.reference());
        double ey = w.y() + r * Math.sin(w.reference());
        g.draw(new Line2D.Double(w.x(), w.y(), ex, ey));
        if (w.aims() == null) {
            // With the simple guns' marks the head-on mark ends the line.
            g.draw(new Ellipse2D.Double(ex - 4, ey - 4, 8, 8));
        }
        if (w.enemy() && w.bearings() != null) {
            paintDangerArc(g, w, r);
        } else {
            g.setColor(w.enemy() ? ENEMY_WAVE : OUR_WAVE);
            // Only where the target can be: the maximum escape angle either side of the head-on aim.
            g.draw(Double.isNaN(w.escape()) ? arc(w.x(), w.y(), r, 0, 2 * Math.PI, CIRCLE_SEGMENTS)
                    : arc(w.x(), w.y(), r, w.reference() - w.escape(), w.reference() + w.escape(), WAVE_SEGMENTS));
        }
        if (!w.enemy() && w.bearings() != null && r > DOTS_BEHIND) {
            paintProfile(g, w, r - DOTS_BEHIND);
        }
        if (!Double.isNaN(w.hitLow())) {
            g.setColor(HIT_RANGE);
            g.setStroke(new BasicStroke(2f));
            g.draw(arc(w.x(), w.y(), r + HIT_RANGE_OUT, w.hitLow(), w.hitHigh(), ARC_SEGMENTS));
            g.setStroke(new BasicStroke(1f));
        }
        if (w.aims() != null) {
            g.setColor(AIM_MARK);
            for (int i = 0; i < w.aims().length; i++) {
                if (!Double.isNaN(w.aims()[i])) {
                    aimMark(g, i, w.x(), w.y(), r, w.aims()[i]);
                }
            }
        }
        String power = String.format(Locale.ROOT, "%.1f", w.power());
        if (!w.enemy()) {
            // Our bullet itself, with its power beside it, while it flies.
            if (!Double.isNaN(w.bulletX())) {
                g.setColor(BULLET);
                dot(g, w.bulletX(), w.bulletY(), 3);
                g.setColor(OUR_POWER);
                g.drawString(power, (float) (w.bulletX() + 6), (float) (w.bulletY() + 4));
            }
        } else {
            // Where an enemy's bullet flies we do not know: its power stands where the head-on aim meets the wave.
            g.setColor(ENEMY_POWER);
            g.drawString(power, (float) (w.x() + (r + 14) * Math.cos(w.reference()) - 8),
                    (float) (w.y() + (r + 14) * Math.sin(w.reference()) - 4));
        }
    }

    /**
     * The wave we surf drawn in the danger model's colours: each piece of the arc between two guess factors coloured
     * by the profile there, blue unlikely to red likely, green where our bullets shadow it.
     */
    private static void paintDangerArc(Graphics2D g, Trace.WaveView w, double radius) {
        double[] shares = shares(w.values());
        double[] b = w.bearings();
        g.setStroke(new BasicStroke(2.5f));
        for (int i = 0; i + 1 < b.length; i++) {
            boolean shadowed = w.shadowed() != null && (w.shadowed()[i] || w.shadowed()[i + 1]);
            g.setColor(shadowed ? SHADOW : blend(UNLIKELY, LIKELY, (shares[i] + shares[i + 1]) / 2));
            g.draw(arc(w.x(), w.y(), radius, b[i], b[i + 1], 2));
        }
        g.setStroke(new BasicStroke(1f));
    }

    /** The gun's weights as small dots just behind our wave's front, coloured as the danger arc. */
    private static void paintProfile(Graphics2D g, Trace.WaveView w, double radius) {
        double[] shares = shares(w.values());
        for (int i = 0; i < shares.length; i++) {
            g.setColor(blend(UNLIKELY, LIKELY, shares[i]));
            double a = w.bearings()[i];
            dot(g, w.x() + radius * Math.cos(a), w.y() + radius * Math.sin(a), 1.5);
        }
    }

    /** Each value against the others, 0 to 1: within a standard deviation of the mean, clamped beyond. */
    private static double[] shares(double[] values) {
        double mean = 0;
        for (double v : values) {
            mean += v;
        }
        mean /= Math.max(1, values.length);
        double variance = 0;
        for (double v : values) {
            variance += (v - mean) * (v - mean);
        }
        double sd = Math.sqrt(variance / Math.max(1, values.length));
        double[] shares = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            shares[i] = sd > 0 ? Math.max(0, Math.min(1, (values[i] - mean + sd) / (2 * sd))) : 0.5;
        }
        return shares;
    }

    /**
     * Where a simple gun aimed, on the wave's front: head-on a cross in a circle, linear two lines along the bullet's
     * way, circular a circle.
     */
    private static void aimMark(Graphics2D g, int gun, double cx, double cy, double r, double bearing) {
        double c = Math.cos(bearing);
        double s = Math.sin(bearing);
        double px = cx + r * c;
        double py = cy + r * s;
        switch (gun) {
            case Trace.HEAD_ON -> {
                g.draw(new Ellipse2D.Double(px - 4, py - 4, 8, 8));
                g.draw(new Line2D.Double(px - 4 * c, py - 4 * s, px + 4 * c, py + 4 * s));
                g.draw(new Line2D.Double(px + 4 * s, py - 4 * c, px - 4 * s, py + 4 * c));
            }
            case Trace.LINEAR -> {
                for (int side = -1; side <= 1; side += 2) {
                    double ox = -2.5 * side * s;
                    double oy = 2.5 * side * c;
                    g.draw(new Line2D.Double(px + ox - 5 * c, py + oy - 5 * s, px + ox + 5 * c, py + oy + 5 * s));
                }
            }
            case Trace.CIRCULAR -> g.draw(new Ellipse2D.Double(px - 4, py - 4, 8, 8));
            default -> {
            }
        }
    }

    /**
     * The melee movement: our recent positions (grey), every candidate destination coloured by its risk on a log
     * scale (blue safest to red), a ring round those also scored against the waves, the chosen one ringed and joined
     * to us, and a line to the robot the gang defence draws us to.
     */
    private static void paintMove(Graphics2D g, Trace.MoveView m) {
        double[][] c = m.candidates();
        double[] logs = new double[c.length];
        for (int i = 0; i < c.length; i++) {
            logs[i] = Math.log(Math.max(c[i][2], 1e-300));
        }
        double[] shares = shares(logs);
        for (int i = 0; i < c.length; i++) {
            g.setColor(blend(SAFE_AREA, RISKY_AREA, shares[i]));
            dot(g, c[i][0], c[i][1], AREA_RADIUS);
        }
        g.setColor(WAVED);
        g.setStroke(new BasicStroke(1f));
        for (double[] p : m.waved()) {
            g.draw(new Ellipse2D.Double(p[0] - AREA_RADIUS - 2, p[1] - AREA_RADIUS - 2, 2 * AREA_RADIUS + 4,
                    2 * AREA_RADIUS + 4));
        }
        if (!Double.isNaN(m.pullX())) {
            g.setColor(PULL);
            g.draw(new Line2D.Double(m.x(), m.y(), m.pullX(), m.pullY()));
        }
        if (!Double.isNaN(m.destinationX())) {
            g.setColor(DESTINATION);
            g.setStroke(DASHED);
            g.draw(new Line2D.Double(m.x(), m.y(), m.destinationX(), m.destinationY()));
            g.setStroke(new BasicStroke(2f));
            g.draw(new Ellipse2D.Double(m.destinationX() - AREA_RADIUS - 3, m.destinationY() - AREA_RADIUS - 3,
                    2 * AREA_RADIUS + 6, 2 * AREA_RADIUS + 6));
            g.setStroke(new BasicStroke(1f));
        }
    }

    /**
     * The melee gun's last aim: every predicted position of every enemy where the bullet arrives, coloured by the weight
     * covering its bearing (blue little to red most: there the gun aims), a line from each enemy to the weighted middle
     * of its positions (where the gun expects it to go), and the aim itself.
     */
    private static void paintMeleeAim(Graphics2D g, Trace.MeleeAimView v) {
        double[] shares = shares(v.density());
        int enemies = v.enemyX().length;
        double[] sx = new double[enemies];
        double[] sy = new double[enemies];
        double[] sw = new double[enemies];
        for (int i = 0; i < v.xs().length; i++) {
            g.setColor(withAlpha(blend(UNLIKELY, LIKELY, shares[i]), 210));
            dot(g, v.xs()[i], v.ys()[i], v.enemy()[i] == v.aimedAt() ? 2.5 : 1.8);
            int k = v.enemy()[i];
            sx[k] += v.weights()[i] * v.xs()[i];
            sy[k] += v.weights()[i] * v.ys()[i];
            sw[k] += v.weights()[i];
        }
        g.setStroke(new BasicStroke(1f));
        double reach = 200;
        for (int k = 0; k < enemies; k++) {
            if (sw[k] <= 0) {
                continue;
            }
            double mx = sx[k] / sw[k];
            double my = sy[k] / sw[k];
            g.setColor(PREDICTED);
            g.draw(new Line2D.Double(v.enemyX()[k], v.enemyY()[k], mx, my));
            g.draw(new Ellipse2D.Double(mx - 4, my - 4, 8, 8));
            if (k == v.aimedAt()) {
                reach = Math.hypot(mx - v.originX(), my - v.originY()) + 20;
            }
        }
        if (!Double.isNaN(v.aim())) {
            g.setColor(MELEE_AIM_RAY);
            g.draw(new Line2D.Double(v.originX(), v.originY(), v.originX() + reach * Math.cos(v.aim()),
                    v.originY() + reach * Math.sin(v.aim())));
        }
    }

    /** A trail of positions {x, y, age}, each dot fainter the older it is. */
    private static void paintTrail(Graphics2D g, double[][] points, Color color) {
        for (double[] p : points) {
            int alpha = (int) Math.round(TRAIL_ALPHA * (1 - p[2] / PositionTrails.TURNS));
            if (alpha > 0) {
                g.setColor(withAlpha(color, alpha));
                dot(g, p[0], p[1], 1.5);
            }
        }
    }

    private static void paintRadar(Graphics2D g, Trace.RadarView radar) {
        List<Trace.RadarWatcher> watchers = radar.watchers();
        for (int i = 0; i < watchers.size(); i++) {
            Trace.RadarWatcher w = watchers.get(i);
            Color color = WATCHERS[i % WATCHERS.length];
            if (!Double.isNaN(w.arcCentre())) {
                double radius = w.reach() + 30;
                double from = w.arcCentre() - w.arcHalfWidth();
                double to = w.arcCentre() + w.arcHalfWidth();
                Path2D wedge = arc(w.x(), w.y(), radius, from, to, ARC_SEGMENTS);
                wedge.lineTo(w.x(), w.y());
                wedge.closePath();
                g.setColor(withAlpha(color, 18));
                g.fill(wedge);
            }
            g.setColor(color);
            g.setStroke(new BasicStroke(w.me() ? 3f : 1.5f));
            g.draw(new Ellipse2D.Double(w.x() - 24, w.y() - 24, 48, 48));
            g.setStroke(new BasicStroke(1f));
            g.draw(new Line2D.Double(w.x(), w.y(), w.x() + 60 * Math.cos(w.radarHeading()),
                    w.y() + 60 * Math.sin(w.radarHeading())));
        }
        for (Trace.RadarEnemy e : radar.enemies()) {
            g.setColor(e.watcher() < 0 ? UNWATCHED : WATCHERS[e.watcher() % WATCHERS.length]);
            if (e.watcher() >= 0) {
                Trace.RadarWatcher w = watchers.get(e.watcher());
                g.setColor(withAlpha(WATCHERS[e.watcher() % WATCHERS.length], 150));
                g.setStroke(new BasicStroke(1f));
                g.draw(new Line2D.Double(w.x(), w.y(), e.x(), e.y()));
                g.setColor(WATCHERS[e.watcher() % WATCHERS.length]);
            }
            g.setStroke(new BasicStroke(2f));
            g.draw(new Ellipse2D.Double(e.x() - 22, e.y() - 22, 44, 44));
            g.setColor(e.age() >= STALE_AGE ? STALE : TEXT);
            g.drawString(Long.toString(e.age()), (float) (e.x() + 24), (float) (e.y() + 16));
        }
    }

    /**
     * The guns at the bottom left, one a line under each target with a plan, then the melee gun: each with its
     * virtual hit rate (the melee gun its bullets' hit rate), the one that aimed our last bullet in our bullets'
     * colour.
     */
    private static void paintGuns(Graphics2D g, Trace trace) {
        List<String> lines = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        for (Trace.GunView gun : trace.guns()) {
            lines.add(gun.target() + "  data " + gun.data());
            colors.add(TEXT);
            boolean lastTarget = gun.target().equals(trace.lastShotTarget());
            for (int i = 0; i < gun.gunNames().length; i++) {
                lines.add("  " + gun.gunNames()[i] + "  " + percent(gun.hitRates()[i]));
                colors.add(lastTarget && gun.gunNames()[i].equals(trace.lastShotGun()) ? BULLET : GUN_TEXT);
            }
        }
        if (!Double.isNaN(trace.meleeHitRate())) {
            lines.add("melee  " + percent(trace.meleeHitRate()));
            colors.add("melee".equals(trace.lastShotGun()) ? BULLET : GUN_TEXT);
        }
        for (int i = 0; i < lines.size(); i++) {
            g.setColor(colors.get(i));
            g.drawString(lines.get(i), 10, (float) (12 + 13 * (lines.size() - 1 - i)));
        }
    }

    private static String percent(double rate) {
        return Double.isNaN(rate) ? "-" : String.format(Locale.ROOT, "%.1f %%", 100 * rate);
    }

    /** The layers' keys in the top left corner, green when shown. */
    private static void paintLegend(Graphics2D g, Trace trace) {
        for (int i = 0; i < KEYS.length; i++) {
            g.setColor(SHOWN[i] ? LAYER_ON : LAYER_OFF);
            g.drawString(Character.toUpperCase(KEYS[i]) + ": " + NAMES[i], 10,
                    (float) (trace.fieldHeight() - 20 - 14 * i));
        }
    }

    private static void dot(Graphics2D g, double x, double y, double r) {
        g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
    }

    private static Color blend(Color a, Color b, double share) {
        return new Color((int) Math.round(a.getRed() + share * (b.getRed() - a.getRed())),
                (int) Math.round(a.getGreen() + share * (b.getGreen() - a.getGreen())),
                (int) Math.round(a.getBlue() + share * (b.getBlue() - a.getBlue())));
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
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
