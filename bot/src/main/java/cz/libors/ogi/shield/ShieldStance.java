package cz.libors.ogi.shield;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.physics.EngineRobot;
import cz.libors.ogi.physics.RobotMotion;

/**
 * Where we stand while shielding: where the round put us, the body across the line to the enemy, stepping a little
 * aside along the body only when a shield shot needs it ({@link ShieldPlanner#STEPS}) and back. Too close to the enemy
 * for our shot to meet its bullet in time, we back away first, still shielding. Why: bot.md "Bullet shield",
 * Standing and Retreat.
 */
public final class ShieldStance {

    /**
     * Closer to the enemy than this plus this many pixels per unit of the speed of its next bullet, our fastest shot,
     * fired up to two turns after the enemy's, cannot meet its bullet before it is within the planner's clearance: we
     * back away first.
     */
    static final double MIN_DISTANCE = 38;
    static final double DISTANCE_PER_SPEED = 3.4;
    /**
     * We stand where the round put us and return there after each step aside; pushed further than this (by a robot, or
     * while ramming) we stand where we are.
     */
    static final double HOME_DRIFT = 2;
    /** Nearer than this to where we stand, along the body, we do not move back: the rest is the body's turning. */
    static final double HOME_SLACK = 0.05;
    /** How far a retreat drives at most in a turn's command. */
    private static final double RETREAT_DISTANCE = 100;
    /** How far ahead a retreat must stay inside the field, and by how much. */
    static final double RETREAT_LOOKAHEAD = 60;
    static final double RETREAT_MARGIN = 30;

    /**
     * This turn's body turn (engine convention, set already) and the distances we may set: back to where we stand or
     * a step to either side of it, the preferred first; a retreat's one distance.
     */
    public record Stance(double bodyTurn, double[] moves) {
    }

    private final Field field;
    private final double coolingRate;
    /** Where we stand; NaN until the round's first shielding turn and after a retreat. */
    private double homeX = Double.NaN;
    private double homeY = Double.NaN;

    public ShieldStance(Field field, double coolingRate) {
        this.field = field;
        this.coolingRate = coolingRate;
    }

    /**
     * This turn's stance against the enemy at ({@code enemyX}, {@code enemyY}).
     *
     * @param me              our state at the end of this turn, in the engine's convention, without commands
     * @param nextBulletSpeed the speed of the enemy's next bullet as we expect it (its last shot's this round, before
     *                        that the mean over the battle)
     */
    public Stance next(EngineRobot me, double enemyX, double enemyY, double nextBulletSpeed) {
        double heading = Angles.fromRobocode(me.bodyHeading);
        if (Math.hypot(enemyX - me.x, enemyY - me.y) < MIN_DISTANCE + DISTANCE_PER_SPEED * nextBulletSpeed) {
            double away = retreatHeading(me.x, me.y, enemyX, enemyY);
            double bodyTurn = Angles.robocodeTurn(heading, away);
            double distance = RETREAT_DISTANCE;
            if (Math.abs(bodyTurn) > Math.PI / 2) {
                bodyTurn = Angles.robocodeTurn(heading, away + Math.PI);
                distance = -distance;
            }
            homeX = Double.NaN;
            return new Stance(bodyTurn, new double[] {distance});
        }
        if (Double.isNaN(homeX) || Math.hypot(me.x - homeX, me.y - homeY) > HOME_DRIFT) {
            homeX = me.x;
            homeY = me.y;
        }
        double bearing = Angles.bearing(me.x, me.y, enemyX, enemyY);
        double bodyTurn = Angles.robocodeTurn(heading, bearing + Math.PI / 2);
        if (Math.abs(bodyTurn) > Math.PI / 2) {
            bodyTurn = Angles.robocodeTurn(heading, bearing - Math.PI / 2);
        }
        return new Stance(bodyTurn, moves(me, bodyTurn));
    }

    /**
     * The distances we may set this turn with {@code bodyTurn} set: back to where we stand, or to
     * {@link ShieldPlanner#STEPS} to either side of it along the body, which this turn's turn has set across the line
     * to the enemy.
     */
    private double[] moves(EngineRobot me, double bodyTurn) {
        EngineRobot turned = me.copy();
        turned.bodyTurnRemaining = bodyTurn;
        RobotMotion.move(turned, (int) field.width(), (int) field.height(), coolingRate);
        double h = Angles.fromRobocode(turned.bodyHeading);
        double home = (homeX - turned.x) * Math.cos(h) + (homeY - turned.y) * Math.sin(h);
        if (Math.abs(home) < HOME_SLACK) {
            // Back from a step aside, or only turned: we stand here now.
            home = 0;
            homeX = me.x;
            homeY = me.y;
        }
        double[] moves = new double[ShieldPlanner.STEPS.length];
        for (int i = 0; i < moves.length; i++) {
            moves[i] = home + ShieldPlanner.STEPS[i];
        }
        return moves;
    }

    /**
     * Straight away from the enemy, or the nearest heading to it that keeps us inside the field: moving along the
     * line to the enemy changes little in the angles its gun learns.
     */
    private double retreatHeading(double x, double y, double enemyX, double enemyY) {
        double away = Angles.bearing(enemyX, enemyY, x, y);
        for (double turn = 0; turn <= Math.PI / 2; turn += 0.1) {
            for (int side = 1; side >= -1; side -= 2) {
                double h = away + side * turn;
                double ex = x + RETREAT_LOOKAHEAD * Math.cos(h);
                double ey = y + RETREAT_LOOKAHEAD * Math.sin(h);
                if (ex > RETREAT_MARGIN && ex < field.width() - RETREAT_MARGIN && ey > RETREAT_MARGIN
                        && ey < field.height() - RETREAT_MARGIN) {
                    return h;
                }
            }
        }
        return away;
    }
}
