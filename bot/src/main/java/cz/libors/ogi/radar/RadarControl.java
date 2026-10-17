package cz.libors.ogi.radar;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.world.EnemyState;
import cz.libors.ogi.world.Phase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Where the radar turns in each turn (bot.md "A turn", 3): in a duel locked on the target; in a team's endgame each
 * of our robots keeps the last enemy in its sweep; in a melee the team's plan ({@link TeamRadar}) or the stalest
 * enemy ({@link MeleeRadar}), and all round when no enemy has been seen lately. At a melee round's start, while the
 * radar spins and the gun is too hot to fire soon, the gun spins along and carries the radar (engine-model G-09): 65°
 * a turn instead of 45°.
 */
public final class RadarControl {

    /** Whether the gun carries the radar at a melee round's start. */
    static final boolean FAST_FIRST_SWEEP = true;

    /**
     * This turn's radar command.
     *
     * @param radarTurn    the radar's turn, mathematical (counter-clockwise positive); infinite to spin
     * @param gunCarries   whether the gun turns as far as the radar in this turn, carrying it
     * @param sweepChanged whether the gun's carrying starts or ends with this turn: the radar's adjustment for the gun
     *                     turn is to be switched off or on
     */
    public record Command(double radarTurn, boolean gunCarries, boolean sweepChanged) {
    }

    private final MeleeRadar melee = new MeleeRadar();
    /** The team's radar plan; null outside a team. */
    private final TeamRadar team;
    private boolean sweeping;

    public RadarControl(TeamRadar team) {
        this.team = team;
    }

    /** Whether the gun carries the radar: it is not to be turned for the aim meanwhile. */
    public boolean sweeping() {
        return sweeping;
    }

    /**
     * The radar's command for this turn, from our position and the radar's heading (mathematical) at the end of it.
     *
     * @param me        our name, for the team's plan
     * @param enemies   the enemies alive
     * @param teammates our teammates alive
     * @param known     the enemies as our side last saw them
     * @param target    the gun's target, null without one
     * @param gunSoon   whether our gun is cool within the next turns: then the target's scan goes first
     * @param gunIdle   whether our gun is too hot to aim soon: then it may carry the radar
     */
    public Command steer(long now, String me, double x, double y, double radarHeading, Phase phase, int enemies,
                         int teammates, Collection<EnemyState> known, EnemyState target, boolean gunSoon,
                         boolean gunIdle) {
        if (phase == Phase.MELEE) {
            List<MeleeRadar.Seen> seen = new ArrayList<>();
            for (EnemyState e : known) {
                seen.add(new MeleeRadar.Seen(e.name(), e.x(), e.y(), e.time()));
            }
            double turn;
            if (target == null && !MeleeRadar.spinning(seen, enemies)) {
                // No enemy seen for a while: the radar looks all round instead of where they were.
                turn = Double.POSITIVE_INFINITY;
            } else {
                List<MeleeRadar.Seen> watched = teamWatch(now, me, teammates, seen, enemies, target, gunSoon);
                turn = watched != null
                        ? TeamRadar.sweep(radarHeading, TeamRadar.arc(now, x, y, watched))
                        : melee.turn(now, x, y, radarHeading, seen, enemies, target == null ? null : target.name(),
                        gunSoon);
            }
            boolean sweep = FAST_FIRST_SWEEP && MeleeRadar.spinning(seen, enemies) && gunIdle;
            boolean changed = sweep != sweeping;
            sweeping = sweep;
            return new Command(turn, sweeping, changed);
        }
        if (phase == Phase.TEAM_ENDGAME && target != null) {
            // The last enemy, with teammates alive: each of us keeps it in the swept arc every turn, as in a duel.
            return new Command(TeamRadar.sweep(radarHeading, TeamRadar.arc(now, x, y,
                    List.of(new MeleeRadar.Seen(target.name(), target.x(), target.y(), target.time())))), false,
                    false);
        }
        if (phase == Phase.DUEL && target != null && now - target.time() <= 1) {
            double bearing = Angles.bearing(x, y, target.x(), target.y());
            double turn = Angles.robocodeTurn(radarHeading, bearing);
            // Overshooting keeps the target inside the swept arc every tick.
            return new Command(-2 * turn, false, false);
        }
        return new Command(Double.NEGATIVE_INFINITY, false, false);
    }

    /**
     * The enemies our radar watches under the team's plan ({@link TeamRadar}), as our side last saw them; null when the
     * melee radar decides: outside a team, until every enemy has been seen this round, while the gun's target goes
     * first before a shot, and when the plan gives us no enemy.
     */
    private List<MeleeRadar.Seen> teamWatch(long now, String me, int teammates, List<MeleeRadar.Seen> seen,
                                            int enemies, EnemyState target, boolean gunSoon) {
        if (team == null || teammates == 0 || MeleeRadar.spinning(seen, enemies)
                || gunSoon && target != null && now - target.time() > 1) {
            return null;
        }
        List<String> names = team.watch(now, me);
        if (names == null || names.isEmpty()) {
            return null;
        }
        List<MeleeRadar.Seen> watched = new ArrayList<>();
        for (MeleeRadar.Seen s : seen) {
            if (names.contains(s.name())) {
                watched.add(s);
            }
        }
        return watched.isEmpty() ? null : watched;
    }
}
