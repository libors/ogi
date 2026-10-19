package cz.libors.ogi.trace;

import cz.libors.ogi.world.BattleKind;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Paints a {@link Trace} with Robocode's robot graphics, the one place that turns the trace into pixels.
 *
 * <p>Ten layers, each switched by a key while the battle view has the focus (Robocode hands key presses to every
 * robot that overrides {@code onKeyPressed}), with a legend in the top left corner; which are shown at first depends
 * on the kind of battle ({@link #battle}):
 * <ul>
 *   <li><b>E, enemy waves:</b> grey arcs around where the shooter stood, over the maximum escape angle either side
 *       of the line from there towards us at the shot (a head-on aim), which ends on the wave with the bullet's power
 *       beyond it. The waves that reach us first have their arc coloured by the danger model's profile, blue
 *       unlikely to red likely, green where our bullets shadow them, and marks where head-on (a cross in a circle),
 *       linear (two lines) and circular targeting (a circle) aimed; a thin yellow arc just outside where the wave
 *       crosses us.</li>
 *   <li><b>G, gun waves:</b> dark red arcs for our bullets, ticks of the gun's chance to hit just behind the front,
 *       the bullet itself yellow with its power beside it; the guns' records at the bottom left, the gun of our last
 *       bullet in yellow.</li>
 *   <li><b>M, movement</b> (melee and team): the candidate destinations of the minimum-risk movement also scored
 *       against the waves, as dark areas coloured by their risk, blue safest to red; the chosen one ringed in dark
 *       orange and joined to us by a dashed line; a line from us to each enemy as wide as its share of the chosen
 *       destination's risk, and below the destination the teammates' share and what the crowding and the pull
 *       multiply it by.</li>
 *   <li><b>H, position history:</b> where we were in each of the last turns, violet dots fading with age, and the
 *       enemies' scanned positions, turquoise, fading likewise.</li>
 *   <li><b>A, melee aim:</b> the melee gun's last aim: the predicted positions of every enemy where the bullet
 *       arrives as squares half a robot wide, coloured by the weight on their bearing, a line from each enemy to the
 *       middle of its positions, the aim itself.</li>
 *   <li><b>B, bullet powers:</b> the power beside each of our and our teammates' bullets in flight, and each enemy's
 *       last shot's power to its right.</li>
 *   <li><b>I, info:</b> right of each enemy, under its last shot's power, the turns until its gun is cool, its
 *       hit rate on us and ours on it (not in a team, where both would be the drawing robot's alone) and, with two
 *       enemies or more, the age of our side's last scan of it; below us our special states in dark grey, a warning
 *       diamond round us while we are ganged and a line to the ganged teammate we help.</li>
 *   <li><b>R, team radar:</b> each of our robots in a colour of its own, with the arc its radar sweeps over the enemies
 *       the team's plan gives it, a line to each of them and a short one where its radar points. No rings: they hid
 *       the robots.</li>
 *   <li><b>C, closest robots</b> (melee and team): a line from each enemy to the robot closest to it, red to ours,
 *       faint to another enemy, and left of each of our robots how many enemies it is the closest robot of.</li>
 *   <li><b>D, hits on us:</b> where each bullet hit us in the last turns, with a line back to where it was fired
 *       from, coloured by how many robots its shooter had closer than us; at the top right the round's damage taken by
 *       the same classes, dealt, and our energy lead when the round came down to a duel.</li>
 * </ul>
 *
 * <p>In a team battle every robot whose painting is enabled paints, so what is the same on every robot (the team
 * radar, the bullet powers, the column beside each enemy, the enemies' position history, the closest robots) is drawn
 * by one of them only: the painting robot with the lowest member number whose own layers are on, which also draws its
 * gun panel (headed by its number). Keys 1 to 9 switch a member's own layers (its waves, movement, melee aim, trail,
 * states and the hits on it, whose damage it writes in a row of its own), 0 switches all of them on, or off when all
 * were on.
 * Below the legend the painting robots' numbers, green while their own layers are on and red while off; the legend is
 * drawn by the painting robot with the lowest number.
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
    /** A gun's hit rate that does not decide its choice. */
    private static final Color GUN_TEXT_DIM = new Color(105, 105, 105);
    private static final Color LAYER_ON = new Color(90, 220, 90);
    private static final Color LAYER_OFF = new Color(200, 70, 70);
    private static final Color STALE = new Color(255, 80, 80);
    private static final Color WAVED = new Color(110, 110, 110);
    /** The movement's candidates: dark areas, unlike the bright dots of the waves. */
    private static final Color SAFE_AREA = new Color(14, 22, 72);
    private static final Color RISKY_AREA = new Color(76, 16, 16);
    private static final double AREA_RADIUS = 8;
    private static final Color DESTINATION = new Color(200, 100, 20);
    /**
     * Whom the movement fears: a line to each enemy, 1 px plus this many for a share of 1 of the chosen destination's
     * risk, drawn from this share on and labelled from the next.
     */
    private static final Color FEAR = new Color(255, 190, 90, 80);
    private static final Color FEAR_TEXT = new Color(230, 175, 90);
    private static final double FEAR_WIDTH = 8;
    private static final double MIN_FEAR = 0.02;
    private static final double FEAR_LABEL = 0.1;
    /** A line from an enemy to the robot closest to it (layer C): red to ours, faint to another enemy. */
    private static final Color CLOSEST_OURS = new Color(255, 70, 70, 190);
    private static final Color CLOSEST_OTHER = new Color(150, 150, 150, 60);
    /** The enemies whose closest robot ours is stand this far left of its centre (layer C). */
    private static final double CLOSEST_LABEL = 30;
    /**
     * The hits on us (layer D) by their shooter's view when it aimed ({@link Trace#hitClass}): we were its closest
     * robot (red, as layer C's lines to us), 1 or 2 robots were closer, 3 or more, no wave matched (dashed).
     */
    private static final Color[] HIT_CLASSES = {new Color(255, 60, 60), new Color(255, 165, 40),
            new Color(230, 80, 255), new Color(200, 200, 200)};
    private static final String[] HIT_CLASS_NAMES = {"closest", "1-2 closer", "3+ closer", "unseen"};
    /** A hit stays on the overlay this many turns, fading. */
    public static final int HIT_TURNS = 100;
    private static final double HIT_CROSS = 5;
    private static final BasicStroke DASHED = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            10f, new float[] {6f, 5f}, 0f);
    private static final Color OUR_TRAIL = new Color(170, 90, 255);
    private static final Color PREDICTED = new Color(220, 220, 220, 150);
    private static final Color MELEE_AIM_RAY = new Color(185, 115, 30, 220);
    /** An enemy's last shot's power beside it (layer B). */
    private static final Color ENEMY_SHOT = new Color(175, 25, 25);
    /** An enemy's turns until its gun is cool (layer I), and when it is. */
    private static final Color GUN_HOT = new Color(205, 150, 150);
    private static final Color GUN_COOL = new Color(255, 70, 70);
    /** The enemy's hit rate on us (layer I) and ours on it. */
    private static final Color THEIR_RATE = new Color(255, 230, 40);
    private static final Color OUR_RATE = new Color(110, 230, 110);
    /**
     * The numbers beside an enemy stand in one column this far right of its centre, clear of its gun, one row each
     * from the top (baselines, y up): its last shot's power (B), the turns until its gun is cool, its hit rate on us,
     * ours on it and the age of our side's last scan of it (I). A layer turned off leaves its rows empty, so the rest
     * stay put.
     */
    private static final double LABEL_X = 32;
    private static final double ROW_POWER = 18;
    private static final double ROW_COOL = 6;
    private static final double ROW_THEIRS = -6;
    private static final double ROW_OURS = -18;
    private static final double ROW_SCAN_AGE = -30;
    /** The arrows before the hit rates (layer I): this long, the number this far after them. */
    private static final double ARROW_LENGTH = 7;
    private static final double ARROW_GAP = 3;
    /** The gun panel's columns: the gun, its hit rate over all waves, over the waves with a bullet. */
    private static final float[] GUN_COLUMNS = {10, 88, 150};
    /** Our wave's ticks: this many px for a chance to hit of 1, at least the minimum; red from this chance on. */
    private static final double TICK_PER_CHANCE = 60;
    private static final double MIN_TICK = 1.5;
    private static final double RED_CHANCE = 0.4;
    /** The melee gun's predicted positions are squares of this side: half a robot. */
    private static final double PREDICTED_SIDE = 18;
    private static final Color OPPONENT_TRAIL = new Color(64, 224, 208);
    /** A trail's newest dot has this opacity, its oldest none. */
    private static final int TRAIL_ALPHA = 220;
    private static final Color PULL = new Color(200, 120, 255);
    /** Our special states below us (layer I): dark grey, quieter than the enemies' numbers. */
    private static final Color STATE_TEXT = new Color(125, 125, 125);
    /** The gang defence's warning: a diamond round a ganged robot, a line from its helper to it. */
    private static final Color GANG = new Color(0xff, 0x4f, 0x30);
    /**
     * The states' first baseline this far below our centre, just under the body (Robocode draws the energy above a
     * robot, and the names below it are off), the next ones this far apart; the diamond's half-diagonal.
     */
    private static final double STATE_ROW = 30;
    private static final double STATE_ROW_GAP = 12;
    private static final double GANG_DIAMOND = 38;
    /** The legend's row of painting robots: member n stands this many pixels right of member n − 1. */
    private static final float MEMBER_SPACING = 12;
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

    /**
     * The layers: their keys, names, whether each is offered in this battle's kind and whether it is shown (statics
     * last for the battle).
     */
    private static final char[] KEYS = {'e', 'g', 'm', 'r', 'h', 'a', 'b', 'i', 'c', 'd'};
    private static final String[] NAMES = {"enemy waves", "gun waves", "movement", "team radar", "position history",
            "melee aim", "bullet powers", "info", "closest robots", "hits on us"};
    private static final boolean[] SHOWN = new boolean[KEYS.length];
    private static final boolean[] OFFERED = new boolean[KEYS.length];
    private static final int ENEMY_WAVES = 0;
    private static final int GUN_WAVES = 1;
    private static final int MOVE = 2;
    private static final int RADAR = 3;
    private static final int HISTORY = 4;
    private static final int MELEE_AIM = 5;
    private static final int POWERS = 6;
    private static final int INFO = 7;
    private static final int CLOSEST = 8;
    private static final int HITS = 9;
    /** The kind of this battle, set at its first round's start ({@link #battle}); null before. */
    private static BattleKind kind;
    /** Our team's size, 0 outside a team; set every round, painting or not. */
    private static int members;
    /** By member number, whether that robot of our team draws its own layers (keys 1 to 9 and 0). */
    private static final boolean[] OWN = {true, true, true, true, true, true, true, true, true, true};
    /**
     * The layers shown before a melee's or a team battle's round came down to one enemy ({@link #duel}), null while
     * it has not.
     */
    private static boolean[] beforeDuel;

    private Overlay() {
    }

    /**
     * The layers offered and shown when a battle starts, at its first round's start, painting or not: a key pressed
     * before this robot's first paint then switches its layers as it does on the teammates that paint already. A duel
     * offers neither the movement, the melee aim, the closest robots nor the team radar, a melee not the team radar.
     * Shown: in a duel both kinds of waves; in a melee the movement and the melee aim, and in a team battle the radar,
     * where the waves of all robots would cover each other; in both the closest robots and the hits on us; the position
     * history, the bullet powers and the enemy info in every kind. Keys change them afterwards.
     */
    public static void battle(BattleKind battleKind) {
        if (kind != null) {
            return;
        }
        kind = battleKind;
        Arrays.fill(OFFERED, true);
        OFFERED[MOVE] = kind != BattleKind.DUEL;
        OFFERED[MELEE_AIM] = kind != BattleKind.DUEL;
        OFFERED[RADAR] = kind == BattleKind.TEAM;
        OFFERED[CLOSEST] = kind != BattleKind.DUEL;
        SHOWN[ENEMY_WAVES] = kind == BattleKind.DUEL;
        SHOWN[GUN_WAVES] = kind == BattleKind.DUEL;
        SHOWN[MOVE] = kind == BattleKind.MELEE;
        SHOWN[RADAR] = kind == BattleKind.TEAM;
        SHOWN[HISTORY] = true;
        SHOWN[MELEE_AIM] = kind == BattleKind.MELEE;
        SHOWN[POWERS] = true;
        SHOWN[INFO] = true;
        SHOWN[CLOSEST] = kind != BattleKind.DUEL;
        SHOWN[HITS] = kind != BattleKind.DUEL;
    }

    /**
     * Whether a melee's or a team battle's round is down to one enemy: then it is a duel, and the duel's layers are
     * shown (both kinds of waves with the gun panel, no melee aim; in a melee no movement either, which surfs now) until
     * the next round, when the layers shown before come back. Keys change them meanwhile.
     */
    public static void duel(boolean duel) {
        if (kind == null || kind == BattleKind.DUEL) {
            return;
        }
        if (duel && beforeDuel == null) {
            beforeDuel = SHOWN.clone();
            SHOWN[ENEMY_WAVES] = true;
            SHOWN[GUN_WAVES] = true;
            SHOWN[MELEE_AIM] = false;
            SHOWN[MOVE] &= kind == BattleKind.TEAM;
        } else if (!duel && beforeDuel != null) {
            System.arraycopy(beforeDuel, 0, SHOWN, 0, SHOWN.length);
            beforeDuel = null;
        }
    }

    /** Offers and shows every layer, every member's own layers too, for a test that paints them all. */
    static void showAll() {
        kind = BattleKind.TEAM;
        Arrays.fill(OFFERED, true);
        Arrays.fill(SHOWN, true);
        Arrays.fill(OWN, true);
    }

    /** Back to the state before a battle's first round, for tests: the statics outlive a test class. */
    static void reset() {
        kind = null;
        beforeDuel = null;
        members = 0;
        Arrays.fill(OFFERED, false);
        Arrays.fill(SHOWN, false);
        Arrays.fill(OWN, true);
    }

    /** Whether the layer of this key is shown, for tests; false for a key of no layer. */
    static boolean shown(char key) {
        int i = new String(KEYS).indexOf(key);
        return i >= 0 && SHOWN[i];
    }

    /** Whether this battle offers the layer of this key, for tests. */
    static boolean offered(char key) {
        int i = new String(KEYS).indexOf(key);
        return i >= 0 && OFFERED[i];
    }

    /** We play in a team of {@code size} robots (0: not in a team). */
    public static void team(int size) {
        members = Math.min(size, OWN.length - 1);
    }

    /**
     * Switches the layer of this key, if this battle offers it; in a team a digit 1 to 9 switches that member's own
     * layers, and 0 switches every member's on, or off if all were on.
     */
    public static void key(char c) {
        char k = Character.toLowerCase(c);
        for (int i = 0; i < KEYS.length; i++) {
            if (k == KEYS[i] && OFFERED[i]) {
                SHOWN[i] = !SHOWN[i];
            }
        }
        if (members == 0 || k < '0' || k > '9') {
            return;
        }
        int n = k - '0';
        if (n == 0) {
            boolean allOn = true;
            for (int m = 1; m <= members; m++) {
                allOn &= OWN[m];
            }
            Arrays.fill(OWN, 1, members + 1, !allOn);
        } else if (n <= members) {
            OWN[n] = !OWN[n];
        }
    }

    /** Whether member {@code n} of our team draws its own layers, for tests. */
    static boolean own(int n) {
        return OWN[n];
    }

    /**
     * What this robot draws: its own layers, the layers that are the same on every robot of the team, and the legend.
     * Outside a team all three.
     */
    record Roles(boolean own, boolean shared, boolean legend) {

        static Roles of(Trace.PaintersView p) {
            if (p == null) {
                return new Roles(true, true, true);
            }
            int firstOwn = 0;
            for (int n : p.painted()) {
                if (n < OWN.length && OWN[n]) {
                    firstOwn = n;
                    break;
                }
            }
            boolean own = p.me() >= OWN.length || OWN[p.me()];
            return new Roles(own, own && firstOwn == p.me(), p.painted().length == 0 || p.painted()[0] == p.me());
        }
    }

    public static void paint(Graphics2D g, Trace trace) {
        Roles roles = Roles.of(trace.painters());
        Trace.PositionView positions = trace.positions();
        if (positions != null && SHOWN[HISTORY]) {
            if (roles.shared()) {
                paintTrail(g, positions.enemies(), OPPONENT_TRAIL);
            }
            if (roles.own()) {
                paintTrail(g, positions.ours(), OUR_TRAIL);
            }
        }
        if (roles.shared() && SHOWN[CLOSEST] && trace.closest() != null) {
            paintClosest(g, trace.closest());
        }
        if (roles.shared() && SHOWN[RADAR] && trace.radar() != null) {
            paintRadar(g, trace.radar());
        }
        if (roles.own()) {
            if (SHOWN[MOVE] && trace.move() != null) {
                paintMove(g, trace.move());
            }
            if (SHOWN[HITS] && trace.hits() != null) {
                paintHits(g, trace);
            }
            if (SHOWN[MELEE_AIM] && trace.meleeAim() != null) {
                paintMeleeAim(g, trace.meleeAim());
            }
            for (Trace.WaveView w : trace.waves()) {
                if (SHOWN[w.enemy() ? ENEMY_WAVES : GUN_WAVES]) {
                    paintWave(g, w);
                }
            }
        }
        // The gun panel stands in one place: one robot's, the one that draws what the team shares.
        if (roles.shared() && SHOWN[GUN_WAVES]) {
            paintGuns(g, trace);
        }
        if (roles.shared() && SHOWN[POWERS] && trace.powers() != null) {
            paintPowers(g, trace.powers());
        }
        if (roles.shared() && SHOWN[INFO] && trace.info() != null) {
            paintInfo(g, trace.info());
        }
        if (roles.own() && SHOWN[INFO] && trace.states() != null) {
            paintStates(g, trace.states());
        }
        if (roles.legend()) {
            paintLegend(g, trace);
        }
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

    /**
     * The gun's chance to hit at each bearing it weighed, as a tick inward from just behind our wave's front: as long
     * as the chance ({@link #TICK_PER_CHANCE}) and coloured by it on a fixed scale, blue none to red
     * {@link #RED_CHANCE} and more, so that a flat profile stays short and blue.
     */
    private static void paintProfile(Graphics2D g, Trace.WaveView w, double radius) {
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i < w.values().length; i++) {
            double chance = w.values()[i];
            g.setColor(blend(UNLIKELY, LIKELY, Math.min(1, chance / RED_CHANCE)));
            double a = w.bearings()[i];
            double c = Math.cos(a);
            double s = Math.sin(a);
            double inner = Math.max(0, radius - Math.max(MIN_TICK, TICK_PER_CHANCE * chance));
            g.draw(new Line2D.Double(w.x() + radius * c, w.y() + radius * s, w.x() + inner * c, w.y() + inner * s));
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
     * The melee movement: a line from us to each enemy as wide as its share of the chosen destination's risk (whom we
     * fear there, labelled from {@link #FEAR_LABEL}), the candidates also scored against the waves coloured by their
     * risk on a log scale (blue safest to red), the chosen one ringed and joined to us, below it the teammates' share
     * of its risk and what the crowding and the pull multiply it by, and a line to the robot the pull draws us to.
     */
    private static void paintMove(Graphics2D g, Trace.MoveView m) {
        for (double[] f : m.fears()) {
            if (f[2] < MIN_FEAR) {
                continue;
            }
            g.setColor(FEAR);
            g.setStroke(new BasicStroke((float) (1 + FEAR_WIDTH * f[2]), BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER));
            g.draw(new Line2D.Double(m.x(), m.y(), f[0], f[1]));
            if (f[2] >= FEAR_LABEL) {
                g.setColor(FEAR_TEXT);
                g.drawString(Math.round(100 * f[2]) + "%", (float) (m.x() + 0.6 * (f[0] - m.x()) + 6),
                        (float) (m.y() + 0.6 * (f[1] - m.y()) + 4));
            }
        }
        g.setStroke(new BasicStroke(1f));
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
            StringBuilder text = new StringBuilder();
            if (!Double.isNaN(m.mates())) {
                text.append("mates ").append(Math.round(100 * m.mates())).append("%  ");
            }
            text.append(String.format(Locale.ROOT, "crowd \u00d7%.2f", m.crowding()));
            if (!Double.isNaN(m.pull())) {
                text.append(String.format(Locale.ROOT, "  pull \u00d7%.2f", m.pull()));
            }
            String line = text.toString();
            g.setColor(GUN_TEXT);
            g.drawString(line, (float) (m.destinationX() - g.getFontMetrics().stringWidth(line) / 2.0),
                    (float) (m.destinationY() - AREA_RADIUS - 18));
        }
    }

    /**
     * From each enemy a line to the robot closest to it, red to ours and faint to another enemy, and left of each of
     * our robots how many enemies it is the closest robot of ("k2", red from 1).
     */
    private static void paintClosest(Graphics2D g, Trace.ClosestView v) {
        g.setStroke(new BasicStroke(1.2f));
        for (double[] e : v.enemies()) {
            g.setColor(e[4] > 0 ? CLOSEST_OURS : CLOSEST_OTHER);
            g.draw(new Line2D.Double(e[0], e[1], e[2], e[3]));
        }
        g.setStroke(new BasicStroke(1f));
        for (double[] o : v.ours()) {
            String k = "k" + (int) o[2];
            g.setColor(o[2] > 0 ? HIT_CLASSES[0] : STATE_TEXT);
            g.drawString(k, (float) (o[0] - CLOSEST_LABEL - g.getFontMetrics().stringWidth(k)), (float) (o[1] - 4));
        }
    }

    /**
     * The bullets that hit us in the last {@link #HIT_TURNS} turns, fading: a cross where each hit, a line back to a
     * ring where it was fired from, in the colour of its shooter's view ({@link #HIT_CLASSES}); and in the top right
     * corner, one row per robot of our team, the round's damage taken by the same classes, dealt, and our energy lead
     * when the round came down to a duel.
     */
    private static void paintHits(Graphics2D g, Trace trace) {
        Trace.HitsView v = trace.hits();
        for (Trace.Hit h : v.hits()) {
            int alpha = (int) Math.round(230 * (1 - (double) h.age() / HIT_TURNS));
            if (alpha <= 0) {
                continue;
            }
            g.setColor(withAlpha(HIT_CLASSES[Trace.hitClass(h.closer())], alpha));
            g.setStroke(h.closer() < 0 ? DASHED : new BasicStroke(1.5f));
            g.draw(new Line2D.Double(h.x(), h.y(), h.fromX(), h.fromY()));
            g.setStroke(new BasicStroke(1f));
            g.draw(new Ellipse2D.Double(h.fromX() - 4, h.fromY() - 4, 8, 8));
            g.setStroke(new BasicStroke(2f));
            g.draw(new Line2D.Double(h.x() - HIT_CROSS, h.y() - HIT_CROSS, h.x() + HIT_CROSS, h.y() + HIT_CROSS));
            g.draw(new Line2D.Double(h.x() - HIT_CROSS, h.y() + HIT_CROSS, h.x() + HIT_CROSS, h.y() - HIT_CROSS));
        }
        g.setStroke(new BasicStroke(1f));
        List<String> parts = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        int member = trace.painters() == null ? 1 : trace.painters().me();
        if (trace.painters() != null) {
            parts.add("robot " + member + "  ");
            colors.add(TEXT);
        }
        parts.add("taken");
        colors.add(GUN_TEXT);
        for (int i = 0; i < HIT_CLASS_NAMES.length; i++) {
            parts.add(String.format(Locale.ROOT, "  %s %.0f", HIT_CLASS_NAMES[i], v.taken()[i]));
            colors.add(HIT_CLASSES[i]);
        }
        parts.add(String.format(Locale.ROOT, "   dealt %.0f", v.dealt()));
        colors.add(OUR_RATE);
        if (!Double.isNaN(v.duel())) {
            parts.add(String.format(Locale.ROOT, "   duel %+.0f", v.duel()));
            colors.add(TEXT);
        }
        float width = 0;
        for (String p : parts) {
            width += g.getFontMetrics().stringWidth(p);
        }
        float x = (float) (trace.fieldWidth() - 10 - width);
        float y = (float) (trace.fieldHeight() - 20 - 14 * (member - 1));
        for (int i = 0; i < parts.size(); i++) {
            g.setColor(colors.get(i));
            g.drawString(parts.get(i), x, y);
            x += g.getFontMetrics().stringWidth(parts.get(i));
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
        double half = PREDICTED_SIDE / 2;
        for (int i = 0; i < v.xs().length; i++) {
            g.setColor(withAlpha(blend(UNLIKELY, LIKELY, shares[i]), 210));
            g.setStroke(new BasicStroke(v.enemy()[i] == v.aimedAt() ? 1.5f : 1f));
            g.draw(new Rectangle2D.Double(v.xs()[i] - half, v.ys()[i] - half, PREDICTED_SIDE, PREDICTED_SIDE));
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

    /**
     * The bullets' powers: beside each of our and our teammates' bullets in flight in our bullets' colour (ours that
     * the guns aimed not while our waves' layer labels them already), and to the right of each enemy its last shot's
     * power in dark red (where its bullets fly we do not know).
     */
    private static void paintPowers(Graphics2D g, Trace.PowerView v) {
        g.setColor(BULLET);
        for (Trace.PowerMark b : SHOWN[GUN_WAVES] ? List.<Trace.PowerMark>of() : v.ours()) {
            g.drawString(String.format(Locale.ROOT, "%.1f", b.power()), (float) (b.x() + 6), (float) (b.y() + 4));
        }
        for (Trace.PowerMark b : v.others()) {
            g.drawString(String.format(Locale.ROOT, "%.1f", b.power()), (float) (b.x() + 6), (float) (b.y() + 4));
        }
        g.setColor(ENEMY_SHOT);
        for (Trace.PowerMark e : v.enemies()) {
            g.drawString(String.format(Locale.ROOT, "%.1f", e.power()), (float) (e.x() + LABEL_X),
                    (float) (e.y() + ROW_POWER));
        }
    }

    /**
     * In the column right of each enemy: the turns until its gun is cool ("7t", bright red when it is), behind an arrow
     * to the left its hit rate on us, behind an arrow to the right ours on it, and with two enemies or more the turns
     * since our side last scanned it ("r1", red from 3).
     */
    private static void paintInfo(Graphics2D g, Trace.InfoView v) {
        g.setStroke(new BasicStroke(1.2f));
        for (Trace.EnemyInfo e : v.enemies()) {
            float x = (float) (e.x() + LABEL_X);
            float rate = (float) (x + ARROW_LENGTH + ARROW_GAP);
            if (e.coolIn() >= 0) {
                g.setColor(e.coolIn() == 0 ? GUN_COOL : GUN_HOT);
                g.drawString(e.coolIn() + "t", x, (float) (e.y() + ROW_COOL));
            }
            if (!Double.isNaN(e.theirRate())) {
                g.setColor(THEIR_RATE);
                arrow(g, x, e.y() + ROW_THEIRS + 4, false);
                g.drawString(Math.round(100 * e.theirRate()) + "%", rate, (float) (e.y() + ROW_THEIRS));
            }
            if (!Double.isNaN(e.ourRate())) {
                g.setColor(OUR_RATE);
                arrow(g, x, e.y() + ROW_OURS + 4, true);
                g.drawString(Math.round(100 * e.ourRate()) + "%", rate, (float) (e.y() + ROW_OURS));
            }
            if (e.scanAge() >= 0) {
                g.setColor(e.scanAge() >= STALE_AGE ? STALE : TEXT);
                g.drawString("r" + e.scanAge(), x, (float) (e.y() + ROW_SCAN_AGE));
            }
        }
        g.setStroke(new BasicStroke(1f));
    }

    /**
     * Our special states below us, one a row downwards, centred, in dark grey; while we are ganged a warning diamond
     * round us, and while we help a ganged teammate a line to it, both in the warning's colour.
     */
    private static void paintStates(Graphics2D g, Trace.StateView v) {
        g.setColor(STATE_TEXT);
        for (int i = 0; i < v.states().size(); i++) {
            String s = v.states().get(i).label();
            g.drawString(s, (float) (v.x() - g.getFontMetrics().stringWidth(s) / 2.0),
                    (float) (v.y() - STATE_ROW - STATE_ROW_GAP * i));
        }
        g.setColor(GANG);
        g.setStroke(new BasicStroke(2f));
        if (v.ganged()) {
            Path2D.Double diamond = new Path2D.Double();
            diamond.moveTo(v.x(), v.y() + GANG_DIAMOND);
            diamond.lineTo(v.x() + GANG_DIAMOND, v.y());
            diamond.lineTo(v.x(), v.y() - GANG_DIAMOND);
            diamond.lineTo(v.x() - GANG_DIAMOND, v.y());
            diamond.closePath();
            g.draw(diamond);
        }
        if (!Double.isNaN(v.helpX())) {
            g.setStroke(new BasicStroke(1.5f));
            g.draw(new Line2D.Double(v.x(), v.y(), v.helpX(), v.helpY()));
        }
        g.setStroke(new BasicStroke(1f));
    }

    /** An arrow from {@code x} to {@code x} + {@link #ARROW_LENGTH} at height {@code y}, pointing right or left. */
    private static void arrow(Graphics2D g, double x, double y, boolean right) {
        double tip = right ? x + ARROW_LENGTH : x;
        double back = right ? -3 : 3;
        g.draw(new Line2D.Double(x, y, x + ARROW_LENGTH, y));
        g.draw(new Line2D.Double(tip, y, tip + back, y + 2.5));
        g.draw(new Line2D.Double(tip, y, tip + back, y - 2.5));
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
            g.setStroke(new BasicStroke(w.me() ? 2.5f : 1f));
            g.draw(new Line2D.Double(w.x(), w.y(), w.x() + 60 * Math.cos(w.radarHeading()),
                    w.y() + 60 * Math.sin(w.radarHeading())));
        }
        g.setStroke(new BasicStroke(1f));
        for (Trace.RadarEnemy e : radar.enemies()) {
            if (e.watcher() >= 0) {
                Trace.RadarWatcher w = watchers.get(e.watcher());
                g.setColor(withAlpha(WATCHERS[e.watcher() % WATCHERS.length], 150));
                g.draw(new Line2D.Double(w.x(), w.y(), e.x(), e.y()));
            }
        }
    }

    /**
     * The guns at the bottom left, one a line under each target with a plan, then the melee gun: each with its
     * virtual hit rate (the melee gun its bullets' hit rate), the one that aimed our last bullet in our bullets'
     * colour.
     */
    private static void paintGuns(Graphics2D g, Trace trace) {
        List<String[]> rows = new ArrayList<>();
        List<Color[]> colors = new ArrayList<>();
        if (trace.painters() != null) {
            rows.add(new String[] {"robot " + trace.painters().me()});
            colors.add(new Color[] {TEXT});
        }
        for (Trace.GunView gun : trace.guns()) {
            rows.add(new String[] {gun.target() + "  data " + gun.data() + (gun.hardToHit() ? "  hard to hit" : "")});
            colors.add(new Color[] {TEXT});
            int bullets = gun.bulletTries()[0];
            rows.add(new String[] {"", "waves", "bullets " + bullets});
            colors.add(new Color[] {GUN_TEXT, GUN_TEXT, GUN_TEXT});
            boolean lastTarget = gun.target().equals(trace.lastShotTarget());
            for (int i = 0; i < gun.gunNames().length; i++) {
                int tries = gun.bulletTries()[i];
                rows.add(new String[] {gun.gunNames()[i], percent(gun.hitRates()[i]),
                        percent(gun.bulletRates()[i]) + (tries != bullets && tries > 0 ? " of " + tries : "")});
                // The anti-surfer and random guns replace the chosen one by the bullets, the others by the waves.
                boolean byBullets = i == 2 || i == 3;
                colors.add(lastTarget && gun.gunNames()[i].equals(trace.lastShotGun())
                        ? new Color[] {BULLET, BULLET, BULLET}
                        : new Color[] {GUN_TEXT, byBullets ? GUN_TEXT_DIM : TEXT, byBullets ? TEXT : GUN_TEXT_DIM});
            }
        }
        if (!Double.isNaN(trace.meleeHitRate())) {
            rows.add(new String[] {"melee", percent(trace.meleeHitRate())});
            Color c = "melee".equals(trace.lastShotGun()) ? BULLET : GUN_TEXT;
            colors.add(new Color[] {c, c});
        }
        for (int i = 0; i < rows.size(); i++) {
            float y = (float) (12 + 13 * (rows.size() - 1 - i));
            for (int k = 0; k < rows.get(i).length; k++) {
                g.setColor(colors.get(i)[k]);
                g.drawString(rows.get(i)[k], GUN_COLUMNS[k], y);
            }
        }
    }

    private static String percent(double rate) {
        return Double.isNaN(rate) ? "-" : String.format(Locale.ROOT, "%.1f %%", 100 * rate);
    }

    /** The keys of the layers this battle offers in the top left corner, green when shown. */
    private static void paintLegend(Graphics2D g, Trace trace) {
        int row = 0;
        for (int i = 0; i < KEYS.length; i++) {
            if (OFFERED[i]) {
                g.setColor(SHOWN[i] ? LAYER_ON : LAYER_OFF);
                g.drawString(Character.toUpperCase(KEYS[i]) + ": " + NAMES[i], 10,
                        (float) (trace.fieldHeight() - 20 - 14 * row++));
            }
        }
        Trace.PaintersView p = trace.painters();
        if (p != null) {
            // Each member keeps its place, so that a robot not painting leaves a gap: 1 2 3   5.
            float y = (float) (trace.fieldHeight() - 20 - 14 * row);
            for (int n : p.painted()) {
                g.setColor(n >= OWN.length || OWN[n] ? LAYER_ON : LAYER_OFF);
                g.drawString(String.valueOf(n), 10 + MEMBER_SPACING * (n - 1), y);
            }
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
