package cz.libors.ogi.trace;

/** The overlay's switches for the tests of other packages: statics, they outlive a test class. */
public final class OverlaySwitches {

    private OverlaySwitches() {
    }

    /** Back to the state before a battle's first round ({@link Overlay#reset}). */
    public static void reset() {
        Overlay.reset();
    }

    /** Whether the layer of this key is shown. */
    public static boolean shown(char key) {
        return Overlay.shown(key);
    }
}
