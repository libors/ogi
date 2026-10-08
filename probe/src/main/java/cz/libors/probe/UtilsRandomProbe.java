package cz.libors.probe;

import robocode.util.Utils;

/** {@link RandomProbe} drawing from {@link Utils#getRandom()}, to test whether an engine seed controls it. */
public class UtilsRandomProbe extends RandomProbe {

    @Override
    protected double nextRandom() {
        return Utils.getRandom().nextDouble();
    }
}
