package cz.libors.ogi.world;

import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.team.EnemySighting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One robot's view of the battle: enemies and teammates, fed by its own scans and by teammates' messages. */
public final class World {

    /** A teammate's last known position. */
    public record Teammate(String name, double x, double y, long time) {
    }

    private final Map<String, EnemyState> enemies = new HashMap<>();
    private final Map<String, Teammate> teammates = new HashMap<>();

    /** Records a sighting; older or duplicate sightings of the same enemy are ignored. */
    public void observeEnemy(EnemySighting s) {
        EnemyState previous = enemies.get(s.name());
        if (previous != null && s.time() <= previous.time()) {
            return;
        }
        double turnRate = previous == null
                ? 0
                : Angles.normalizeRelative(s.heading() - previous.heading()) / (s.time() - previous.time());
        enemies.put(s.name(), new EnemyState(s.name(), s.x(), s.y(), s.heading(), s.velocity(), turnRate,
                s.energy(), s.time()));
    }

    public void observeTeammate(String name, double x, double y, long time) {
        teammates.put(name, new Teammate(name, x, y, time));
    }

    public void robotDied(String name) {
        enemies.remove(name);
        teammates.remove(name);
    }

    public int enemyCount() {
        return enemies.size();
    }

    public Collection<EnemyState> enemies() {
        return enemies.values();
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
