package cz.libors.probe;

import robocode.DeathEvent;
import robocode.util.Utils;

/** {@link RandomProbe} drawing from {@link Utils#getRandom()}, to test whether an engine seed controls it. */
public class UtilsRandomProbe extends RandomProbe {

    private boolean dead;

    @Override
    protected double nextRandom() {
        // A robot's last turn after its death runs while the engine draws from the same generator (engine-model
        // G-34), so drawing then would make the rest of the battle irreproducible.
        return dead ? 0.5 : Utils.getRandom().nextDouble();
    }

    @Override
    public void onDeath(DeathEvent e) {
        dead = true;
    }
}
