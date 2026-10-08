package cz.libors.ogi.physics;

/**
 * The engine's inactivity rule ({@code Battle.resetInactiveTurnCount}, {@code updateRobots}): when the robots have
 * lost less than 10 energy in total for more than {@code inactivityTime} turns, every robot loses 0.1 energy per turn
 * until they lose 10 again.
 *
 * <p>Losses add up across robots, turns and even rounds; every full 10 restarts the count. Energy regained from hits
 * does not count, nor do wall hits. Firing, bullet damage, rams (both robots) and deaths do; a death counts 10
 * (engine-model G-38).
 */
public final class Inactivity {

    /** Default {@code inactivityTime} of a battle. */
    public static final int DEFAULT_TIME = 450;
    public static final double ZAP = 0.1;

    private int inactiveTurns;
    private double lostEnergy;

    /** {@code Battle.initializeRound}: the turn count restarts, the lost-energy sum does not. */
    public void startRound() {
        inactiveTurns = 0;
    }

    /** {@code Battle.resetInactiveTurnCount}. */
    public void lose(double energyLoss) {
        if (energyLoss < 0) {
            return;
        }
        lostEnergy += energyLoss;
        while (lostEnergy >= 10) {
            lostEnergy -= 10;
            inactiveTurns = 0;
        }
    }

    /** A robot died ({@code RobotPeer.kill}). */
    public void death() {
        lose(10.0);
    }

    /** Whether robots are zapped in this turn's move phase; decided after bullets moved, before robots move. */
    public boolean zapping(long inactivityTime) {
        return inactiveTurns > inactivityTime;
    }

    /** End of a turn ({@code Battle.runTurn}). */
    public void endTurn() {
        inactiveTurns++;
    }
}
