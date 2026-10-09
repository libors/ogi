/**
 * Measures how long it takes to hand a turn from one thread to another and back, with {@code wait}/{@code notifyAll}
 * on one monitor, as Robocode's engine and a robot do in every turn (docs/build-and-run.md, "Remote worker").
 *
 * <p>Usage, with a JDK: {@code java PingPong.java [round trips]}; prints the mean round trip three times. On a Mac
 * (M3 Pro) about 5 µs; in a WSL2 VM with the default Hyper-V scheduler about 55 µs when the threads run on two cores.
 */
public class PingPong {

    private static final Object LOCK = new Object();
    private static boolean robotsTurn;

    public static void main(String[] args) throws InterruptedException {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 300_000;
        Thread robot = new Thread(() -> {
            for (int i = 0; i < n; i++) {
                synchronized (LOCK) {
                    while (!robotsTurn) {
                        try {
                            LOCK.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    robotsTurn = false;
                    LOCK.notifyAll();
                }
            }
        });
        robot.start();
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < n / 3; i++) {
                synchronized (LOCK) {
                    robotsTurn = true;
                    LOCK.notifyAll();
                    while (robotsTurn) {
                        LOCK.wait();
                    }
                }
            }
            System.out.printf("%.1f us per round trip%n", (System.nanoTime() - start) / 1e3 / (n / 3));
        }
        robot.interrupt();
    }
}
