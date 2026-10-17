package cz.libors.ogi.world;

/**
 * The kind of battle, fixed at its first round's start: what the bot may try in it (the bullet shield only in a duel)
 * and which overlay layers it offers.
 */
public enum BattleKind {
    /** One enemy from the start. */
    DUEL,
    /** Three robots or more, each for itself. */
    MELEE,
    /** Our team against other teams. */
    TEAM;

    /** The kind of a battle with or without teammates and this many other robots at a round's start. */
    public static BattleKind of(boolean teammates, int others) {
        return teammates ? TEAM : others > 1 ? MELEE : DUEL;
    }
}
