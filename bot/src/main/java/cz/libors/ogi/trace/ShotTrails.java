package cz.libors.ogi.trace;

import cz.libors.ogi.physics.BulletMotion;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/**
 * The waves of our bullets for the overlay: each bullet with what the gun expected when it aimed it. The gun aims a
 * shot in the turn before it fires ({@link #planned}); the bullet joins in the turn it fires ({@link #fired}) and its
 * wave stays until it has passed its target. Only fed while the robot paints, so it costs nothing in a rumble.
 */
public final class ShotTrails {

    /** A wave is dropped this far beyond its target's distance: once it has passed the target's body. */
    private static final double PAST_TARGET = 20;

    /**
     * The aim planned in turn {@code time} for the shot of the next turn.
     *
     * @param target    the enemy aimed at, at its latest scan ({@code targetX}, {@code targetY})
     * @param reference the bearing from the planned origin to the target's latest scan: a head-on aim
     * @param escape    the maximum escape angle at the planned bullet's speed
     * @param bearings  bearings the gun weighed, with their weights ({@code values}); null if it weighed none
     * @param aims      head-on, linear and circular aims ({@link Trace#HEAD_ON} …)
     */
    public record Planned(long time, String target, double targetX, double targetY, double reference, double escape,
                          double[] bearings, double[] values, double[] aims) {
    }

    private record Trail(double x, double y, long fireTime, double power, Planned plan, Supplier<double[]> bullet,
                         Supplier<double[]> target) {

        double radius(long now) {
            return (now - fireTime) * BulletMotion.speed(power);
        }
    }

    private Planned planned;
    private final List<Trail> trails = new ArrayList<>();

    public void planned(Planned plan) {
        planned = plan;
    }

    /**
     * A bullet of {@code power} fired in turn {@code now} from ({@code x}, {@code y}) on last turn's plan, if it was
     * traced.
     *
     * @param bullet our bullet's position while it flies, null once it ended
     * @param target the target's latest known position, null once it is gone (the wave then goes as far as the
     *               target was when the gun aimed)
     */
    public void fired(long now, double x, double y, double power, Supplier<double[]> bullet,
                      Supplier<double[]> target) {
        if (planned != null && planned.time() == now - 1) {
            trails.add(new Trail(x, y, now, power, planned, bullet, target));
        }
        planned = null;
    }

    /** Adds the waves in flight to the trace and drops those that have passed their target. */
    public void describe(long now, Trace trace) {
        for (Iterator<Trail> it = trails.iterator(); it.hasNext(); ) {
            Trail t = it.next();
            double radius = t.radius(now);
            double[] target = t.target().get();
            Planned p = t.plan();
            double reach = Math.hypot(p.targetX() - t.x(), p.targetY() - t.y());
            if (target != null) {
                reach = Math.max(reach, Math.hypot(target[0] - t.x(), target[1] - t.y()));
            }
            if (radius > reach + PAST_TARGET || radius > Math.hypot(trace.fieldWidth(), trace.fieldHeight())) {
                it.remove();
                continue;
            }
            double[] bullet = t.bullet().get();
            trace.add(new Trace.WaveView(t.x(), t.y(), radius, false, t.power(), p.reference(), p.escape(),
                    Double.NaN, Double.NaN, p.bearings(), p.values(), null, p.aims(),
                    bullet == null ? Double.NaN : bullet[0], bullet == null ? Double.NaN : bullet[1]));
        }
    }
}
