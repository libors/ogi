package cz.libors.harness;

import robocode.BattleResults;

/** Final score of one contestant (robot or team), split into the Robocode score components. */
public record ContestantResult(
        String name,
        int rank,
        int score,
        int survival,
        int lastSurvivorBonus,
        int bulletDamage,
        int bulletDamageBonus,
        int ramDamage,
        int ramDamageBonus,
        int firsts) {

    static ContestantResult from(BattleResults r) {
        return new ContestantResult(r.getTeamLeaderName(), r.getRank(), r.getScore(), r.getSurvival(),
                r.getLastSurvivorBonus(), r.getBulletDamage(), r.getBulletDamageBonus(), r.getRamDamage(),
                r.getRamDamageBonus(), r.getFirsts());
    }
}
