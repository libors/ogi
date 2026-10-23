package ext.fixtures;

import java.util.ArrayList;

/** Calls inherited APIs through subclasses of its own, as javac names them; never run, only checked. */
public class InheritingRobot {

    static final class Worker extends Thread {
    }

    static final class Numbers extends ArrayList<Integer> {
        private static final long serialVersionUID = 1;
    }

    long sum() {
        Worker.setDefaultUncaughtExceptionHandler(null);
        return new Numbers().parallelStream().count();
    }
}
