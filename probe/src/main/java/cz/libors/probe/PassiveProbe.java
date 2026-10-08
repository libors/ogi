package cz.libors.probe;

/** {@link RandomProbe} that never fires, so that battles reach the engine's inactivity penalty. */
public class PassiveProbe extends RandomProbe {

    @Override
    protected boolean fires() {
        return false;
    }
}
