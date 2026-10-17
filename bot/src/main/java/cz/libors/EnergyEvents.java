package cz.libors;

import cz.libors.ogi.enemy.DetectedShot;
import cz.libors.ogi.move.EnemyWaves;
import cz.libors.ogi.team.EnemySighting;
import cz.libors.ogi.team.TeamEvent;
import cz.libors.ogi.team.TeamFeed;
import cz.libors.ogi.team.TeamOutbox;

import java.util.Optional;

/**
 * Where the events that change an enemy's energy go, so that the fire detectors ({@link EnemyWaves}) can tell its
 * shots from its other energy changes (engine-model G-14, fire-detection.md): our scans of it, our bullets that hit
 * it, its bullets that hit us, our rams and the inactivity penalty. Outside a team they reach the detectors at once
 * ({@link #direct}); in a team every robot needs the whole team's events, which are complete only a turn later, so
 * they go through a {@link TeamFeed} and to the teammates ({@link #team}).
 */
interface EnergyEvents {

    /**
     * A scan of an enemy, ours or a teammate's, after the waves saw where it is ({@link EnemyWaves#seen}); the shot
     * it reveals, if the detectors take the scan at once.
     */
    Optional<DetectedShot> scanned(EnemySighting s);

    /** One of our bullets of this power hit the enemy in turn {@code time}. */
    void hitByUs(String enemy, double power, long time);

    /** The enemy's bullet of this power hit us in turn {@code time}. */
    void hitUs(String enemy, double power, long time);

    /** We and the enemy collided in turn {@code time}. */
    void rammed(String enemy, long time);

    /** A teammate's report of an event that changed an enemy's energy. */
    void reported(TeamEvent e);

    /** The engine's inactivity penalty took energy from every robot in turn {@code time}, before its scans. */
    void zapped(long time);

    /** Turn {@code now} starts, after our state went to the waves; {@code teammates}: some teammate lives. */
    void turnStarted(long now, boolean teammates);

    /** Straight to the fire detectors: outside a team. */
    static EnergyEvents direct(EnemyWaves waves) {
        return new EnergyEvents() {
            @Override
            public Optional<DetectedShot> scanned(EnemySighting s) {
                return waves.energySeen(s.name(), s.time(), s.x(), s.y(), s.velocity(), s.energy(), s.time());
            }

            @Override
            public void hitByUs(String enemy, double power, long time) {
                waves.ourBulletHit(enemy, power);
            }

            @Override
            public void hitUs(String enemy, double power, long time) {
                waves.enemyHit(enemy, power);
            }

            @Override
            public void rammed(String enemy, long time) {
                waves.rammed(enemy);
            }

            @Override
            public void reported(TeamEvent e) {
                // Nobody reports outside a team.
            }

            @Override
            public void zapped(long time) {
                waves.zapped();
            }

            @Override
            public void turnStarted(long now, boolean teammates) {
            }
        };
    }

    /**
     * Through the team's feed, together with the teammates' scans and events, which the feed takes from their packets;
     * our own events also go to the teammates ({@code outbox}).
     */
    static EnergyEvents team(EnemyWaves waves, TeamFeed feed, TeamOutbox outbox, int round) {
        return new EnergyEvents() {
            /** The turn the feed hands its turns on in: the end of the waves' catching up. */
            private long now;

            /** Where the feed hands each turn: to the fire detectors. */
            private final TeamFeed.Sink detectors = new TeamFeed.Sink() {
                @Override
                public void zapped(long time) {
                    waves.zapped();
                }

                @Override
                public void event(TeamEvent e) {
                    switch (e.kind()) {
                        case HIT_BY_US -> waves.ourBulletHit(e.enemy(), e.power());
                        case HIT_US -> waves.enemyHit(e.enemy(), e.power());
                        case RAM -> waves.rammed(e.enemy());
                    }
                }

                @Override
                public void scanned(EnemySighting s) {
                    waves.energySeen(s.name(), s.time(), s.x(), s.y(), s.velocity(), s.energy(), now);
                }
            };

            @Override
            public Optional<DetectedShot> scanned(EnemySighting s) {
                feed.sighting(s);
                return Optional.empty();
            }

            @Override
            public void hitByUs(String enemy, double power, long time) {
                event(new TeamEvent(enemy, TeamEvent.Kind.HIT_BY_US, power, time, round));
            }

            @Override
            public void hitUs(String enemy, double power, long time) {
                event(new TeamEvent(enemy, TeamEvent.Kind.HIT_US, power, time, round));
            }

            @Override
            public void rammed(String enemy, long time) {
                event(new TeamEvent(enemy, TeamEvent.Kind.RAM, 0, time, round));
            }

            private void event(TeamEvent e) {
                feed.event(e);
                outbox.event(e);
            }

            @Override
            public void reported(TeamEvent e) {
                feed.event(e);
            }

            @Override
            public void zapped(long time) {
                feed.zapped(time);
            }

            @Override
            public void turnStarted(long now, boolean teammates) {
                this.now = now;
                // The teammates' reports of the last turn are in; with none alive, ours of this turn are all there is.
                feed.flush(teammates ? now - 1 : now, detectors);
            }
        };
    }
}
