package cz.libors.probe;

/** {@link RandomProbe} drawing from {@link Math#random()}, to test whether an engine seed controls it. */
public class MathRandomProbe extends RandomProbe {

    @Override
    protected double nextRandom() {
        return Math.random();
    }
}
