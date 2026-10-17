package cz.libors.ogi.move;

/**
 * The tunable numbers of the melee movement's choice among its candidates ({@link MeleeMovement}). The bot plays with
 * {@link #DEFAULT}; the offline position bench replays logged decisions with others.
 *
 * @param teammateWeight      a teammate repels with this over its distance squared
 * @param distanceExponent    an enemy's risk falls with this power of the distance
 * @param closerExponent      an enemy's risk is divided by this power of 1 + the robots closer to it
 * @param closerCountsEnemies whether the robots closer to an enemy count its own teammates too; otherwise only ours
 * @param perpendicularWeight driving along the line to an enemy adds up to this share more risk
 * @param minThreat           the enemy's energy over ours counts from this ...
 * @param maxThreat           ... up to this
 * @param historyWeight       each of our recent positions repels with this over its distance squared
 * @param keep                last turn's destination competes with its score times this
 * @param waveCandidates      the candidates with the least risk that are predicted against the enemy waves
 * @param waveWeight          the waves' share of a candidate's score against the position's, each over its median
 * @param minWaveMedian       the waves' median damage counts as at least this
 * @param teamPull            in a team, the pull to a teammate: risk × e^(pull · change of distance / 100 px)
 * @param helpDistance        helpers are drawn to a ganged teammate down to this distance
 * @param finishDistance      with one enemy left and teammates alive, a robot with {@code finishEnergy} or more is
 *                            drawn to the enemy down to this distance; 0: not
 * @param finishEnergy        the energy a robot needs to close in on the last enemy
 * @param finishPull          how hard: risk × e^(pull · change of distance / 100 px)
 */
public record MeleeMovementParameters(double teammateWeight, double distanceExponent, double closerExponent,
                                      boolean closerCountsEnemies, double perpendicularWeight, double minThreat,
                                      double maxThreat, double historyWeight, double keep, int waveCandidates,
                                      double waveWeight, double minWaveMedian, double teamPull, double helpDistance,
                                      double finishDistance, double finishEnergy, double finishPull) {

    /**
     * What the bot plays with. A teammate's weight 1 gained about 4 APS on the team rumble against 0.3, and 3 another
     * 2; 10 gained nothing more (2026-10-16).
     */
    public static final MeleeMovementParameters DEFAULT = new MeleeMovementParameters(3, 2, 1, true, 1, 0.5, 2, 400,
            0.9, 8, 3, 0.1, 0.5, 300, 350, 30, 1.0);

    public MeleeMovementParameters {
        if (waveCandidates < 1) {
            throw new IllegalArgumentException("waveCandidates must be positive: " + waveCandidates);
        }
    }
}
