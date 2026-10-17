package cz.libors.ogi.world;

/**
 * What a round has come to, by the robots alive: which radar, gun and movement act (bot.md "A turn").
 */
public enum Phase {
    /**
     * One enemy and no teammate: radar lock, the duel's guns and bullet powers, surfing (in a duel battle the bullet
     * shield until its trial fails), ramming a disabled enemy.
     */
    DUEL,
    /** Two enemies or more: the melee radar (in a team the team radar), the melee gun, the melee movement. */
    MELEE,
    /**
     * A team battle's last enemy, with teammates alive: every radar keeps it in its sweep, the virtual guns aim at it
     * (no mirror gun, no shadow shots, no race), and we move as in a melee, drawn to it by the finisher.
     */
    TEAM_ENDGAME,
    /** No enemy alive: the round is ending. */
    NO_ENEMY;

    /** The phase with this many enemies and teammates alive. */
    public static Phase of(int enemies, int teammates) {
        if (enemies > 1) {
            return MELEE;
        }
        if (enemies == 1) {
            return teammates > 0 ? TEAM_ENDGAME : DUEL;
        }
        return NO_ENEMY;
    }
}
