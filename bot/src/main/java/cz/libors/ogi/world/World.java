package cz.libors.ogi.world;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.team.EnemySighting;
import cz.libors.ogi.team.TeammateState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** One robot's view of the battle: enemies and teammates, fed by its own scans and by teammates' messages. */
public final class World {

    /**
     * A teammate's last known state: where it is at the end of turn {@code time}, with its heading (mathematical),
     * velocity and energy as last reported.
     */
    public record Teammate(String name, double x, double y, double heading, double velocity, double energy,
                           long time) {
    }

    private final Map<String, EnemyState> enemies = new HashMap<>();
    private final Map<String, Teammate> teammates = new HashMap<>();
    /** Robots that died this round: a teammate's scan of one, taken just before it died, arrives a turn late. */
    private final Set<String> dead = new HashSet<>();

    /** Records a sighting; older or duplicate sightings of the same enemy are ignored. */
    public void observeEnemy(EnemySighting s) {
        EnemyState previous = enemies.get(s.name());
        if (dead.contains(s.name()) || previous != null && s.time() <= previous.time()) {
            return;
        }
        double turnRate = previous == null
                ? 0
                : Angles.normalizeRelative(s.heading() - previous.heading()) / (s.time() - previous.time());
        enemies.put(s.name(), new EnemyState(s.name(), s.x(), s.y(), s.heading(), s.velocity(), turnRate,
                s.energy(), s.time()));
    }

    /** Records a teammate's state; an older one than we have is ignored, one of the same turn replaces it. */
    public void observeTeammate(Teammate t) {
        Teammate previous = teammates.get(t.name());
        if (!dead.contains(t.name()) && (previous == null || t.time() >= previous.time())) {
            teammates.put(t.name(), t);
        }
    }

    /**
     * Records a teammate's own report: it was at ({@code x}, {@code y}) at the end of turn {@code time} and its
     * commands take it to ({@code nextX}, {@code nextY}) by the end of the next turn, the turn the report arrives in.
     */
    public void teammateReported(TeammateState s) {
        observeTeammate(new Teammate(s.name(), s.nextX(), s.nextY(), s.heading(), s.velocity(), s.energy(),
                s.time() + 1));
    }

    public void robotDied(String name) {
        enemies.remove(name);
        teammates.remove(name);
        dead.add(name);
    }

    /** Whether the robot died this round. */
    public boolean dead(String name) {
        return dead.contains(name);
    }

    public int enemyCount() {
        return enemies.size();
    }

    public Collection<EnemyState> enemies() {
        return enemies.values();
    }

    /** The enemy's last known state, empty if we never saw it or it died. */
    public Optional<EnemyState> enemy(String name) {
        return Optional.ofNullable(enemies.get(name));
    }

    /** Teammates seen within the last {@code maxAge} ticks. */
    public List<Teammate> recentTeammates(long now, long maxAge) {
        List<Teammate> result = new ArrayList<>();
        for (Teammate t : teammates.values()) {
            if (now - t.time() <= maxAge) {
                result.add(t);
            }
        }
        return result;
    }

    /** The closest enemy seen within the last {@code maxAge} ticks. */
    public Optional<EnemyState> closestEnemy(double x, double y, long now, long maxAge) {
        EnemyState best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (EnemyState e : enemies.values()) {
            double d = e.distanceTo(x, y);
            if (now - e.time() <= maxAge && d < bestDistance) {
                best = e;
                bestDistance = d;
            }
        }
        return Optional.ofNullable(best);
    }
}
