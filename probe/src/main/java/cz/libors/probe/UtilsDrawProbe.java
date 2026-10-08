package cz.libors.probe;

import robocode.util.Utils;

/**
 * {@link RandomProbe} that behaves exactly like it but also draws a number from {@link Utils#getRandom()} for every
 * number it uses, and discards it. Comparing the two shows whether such draws shift the engine's random generator.
 */
public class UtilsDrawProbe extends RandomProbe {

    @Override
    protected double nextRandom() {
        Utils.getRandom().nextDouble();
        return super.nextRandom();
    }
}
