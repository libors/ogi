package cz.libors.harness.gunbench;

import cz.libors.ogi.gun.GunLog;
import cz.libors.ogi.gun.GunModel;
import cz.libors.ogi.gun.GunParameters;
import cz.libors.ogi.world.Names;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays a battle's gun log with some {@link GunParameters}: the plans ask a fresh {@link GunModel} per target, as
 * the bot's gun did (one per base name, so that the copies of a robot in an enemy team share it, {@link Names#base}),
 * and each passed wave teaches it what it taught the bot. The recorded movement stays the same, so against a target
 * that reacts to our aim the replay is optimistic.
 */
public final class GunReplay {

    /**
     * Which plans the hit counts take. Every wave teaches the model and every plan is checked against the log in
     * either case.
     */
    public enum Scope {
        /** Every plan. */
        ALL,
        /**
         * Plans made while one enemy lived ({@link GunBattle.EnemyDied}): in a melee or a team battle the turns in
         * which this gun aims the bullets; before, the melee gun aims them and this gun's waves only teach it. Every
         * plan of a 1v1 log.
         */
        ONE_ENEMY
    }

    /**
     * What a replay counted. A guess-factor try is a passed wave whose plan had a guess factor; a fired try is a
     * passed wave with a bullet, aimed by the chosen gun.
     *
     * @param differing plans whose guess factor or gun differ from the logged decision; 0 with the bot's parameters
     * @param orphans   passed waves without their plan in the log
     * @param shielded  passed waves whose bullet an enemy bullet destroyed: left out of the fired and real counts,
     *                  because the aim did not decide them
     */
    public record Tally(long shots, long searched, long differing, long orphans, long guessFactorTries,
                        long guessFactorHits, long realGuessFactorTries, long realGuessFactorHits, long firedTries,
                        long firedHits, long shielded) {

        public static final Tally ZERO = new Tally(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        public Tally plus(Tally o) {
            return new Tally(shots + o.shots, searched + o.searched, differing + o.differing, orphans + o.orphans,
                    guessFactorTries + o.guessFactorTries, guessFactorHits + o.guessFactorHits,
                    realGuessFactorTries + o.realGuessFactorTries, realGuessFactorHits + o.realGuessFactorHits,
                    firedTries + o.firedTries, firedHits + o.firedHits, shielded + o.shielded);
        }

        /** The guess-factor gun's hits per try over all passed waves, in percent. */
        public double guessFactorRate() {
            return percent(guessFactorHits, guessFactorTries);
        }

        /** The guess-factor gun's hits per try over the waves with a bullet, in percent. */
        public double realGuessFactorRate() {
            return percent(realGuessFactorHits, realGuessFactorTries);
        }

        /** Hits per bullet fired, with the chosen gun's aim, in percent. */
        public double firedRate() {
            return percent(firedHits, firedTries);
        }

        private static double percent(long hits, long tries) {
            return tries == 0 ? Double.NaN : 100.0 * hits / tries;
        }
    }

    private GunReplay() {
    }

    public static Tally replay(GunBattle battle, GunParameters parameters) {
        return replay(battle, parameters, Scope.ALL);
    }

    /** The plans' and the hit counts of {@code scope}'s plans; {@code shots} and {@code differing} count every plan. */
    public static Tally replay(GunBattle battle, GunParameters parameters, Scope scope) {
        return replay(battle, parameters, scope, false);
    }

    /**
     * As {@link #replay(GunBattle, GunParameters, Scope)}; with {@code learnInScope} only the waves of {@code scope}'s
     * plans teach the model (an experiment: the bot learns from every wave, so the replay no longer decides as it did).
     */
    public static Tally replay(GunBattle battle, GunParameters parameters, Scope scope, boolean learnInScope) {
        return replayRounds(battle, parameters, scope, learnInScope).stream().reduce(Tally.ZERO, Tally::plus);
    }

    /** The same, counted per round: element r is round r's tally (a wave counts in the round its plan was made). */
    public static List<Tally> replayRounds(GunBattle battle, GunParameters parameters) {
        return replayRounds(battle, parameters, Scope.ALL);
    }

    /** As {@link #replayRounds(GunBattle, GunParameters)}, the hits counted for {@code scope}'s plans. */
    public static List<Tally> replayRounds(GunBattle battle, GunParameters parameters, Scope scope) {
        return replayRounds(battle, parameters, scope, false);
    }

    private static List<Tally> replayRounds(GunBattle battle, GunParameters parameters, Scope scope,
                                            boolean learnInScope) {
        Map<Integer, Tally> byRound = new HashMap<>();
        int round = 0;
        int enemies = battle.enemies();
        Set<Long> counted = new HashSet<>();
        Map<String, GunModel> models = new HashMap<>();
        Map<Long, GunLog.LoggedShot> shots = new HashMap<>();
        Map<Long, GunModel.Aim> aims = new HashMap<>();
        long shotCount = 0;
        long searched = 0;
        long differing = 0;
        long orphans = 0;
        long gfTries = 0;
        long gfHits = 0;
        long realGfTries = 0;
        long realGfHits = 0;
        long firedTries = 0;
        long firedHits = 0;
        long shieldedCount = 0;
        Set<Long> shielded = new HashSet<>();
        // A turn snapshot the recording repeats carries its passes again: each wave is learned once, as online.
        Set<String> learned = new HashSet<>();
        for (Object event : battle.events()) {
            if (event instanceof GunBattle.RoundStart start) {
                byRound.merge(round, new Tally(shotCount, searched, differing, orphans, gfTries, gfHits, realGfTries,
                        realGfHits, firedTries, firedHits, shieldedCount), Tally::plus);
                round = start.round();
                shotCount = searched = differing = orphans = gfTries = gfHits = 0;
                realGfTries = realGfHits = firedTries = firedHits = shieldedCount = 0;
                shots.clear();
                aims.clear();
                shielded.clear();
                learned.clear();
                counted.clear();
                enemies = battle.enemies();
            } else if (event instanceof GunBattle.Shielded s) {
                shielded.add(s.time());
            } else if (event instanceof GunBattle.EnemyDied) {
                enemies--;
            } else if (event instanceof GunModel.Pass pass) {
                GunLog.LoggedShot logged = shots.get(pass.time());
                if (logged == null) {
                    orphans++;
                    continue;
                }
                if (!learned.add(pass.target() + "@" + pass.time())) {
                    continue;
                }
                if (learnInScope && !counted.contains(pass.time())) {
                    continue;
                }
                GunModel.Aim aim = aims.get(pass.time());
                int hit = model(models, pass.target(), parameters).learn(logged.shot(), aim, pass);
                if (!counted.contains(pass.time())) {
                    continue;
                }
                boolean real = pass.real() && !shielded.contains(pass.time());
                shieldedCount += pass.real() && !real ? 1 : 0;
                if (!Double.isNaN(aim.guessFactor())) {
                    boolean gfHit = (hit & 1 << GunModel.GUESS_FACTOR) != 0;
                    gfTries++;
                    gfHits += gfHit ? 1 : 0;
                    if (real) {
                        realGfTries++;
                        realGfHits += gfHit ? 1 : 0;
                    }
                }
                if (real) {
                    firedTries++;
                    firedHits += (hit & 1 << aim.gun()) != 0 ? 1 : 0;
                }
            } else if (event instanceof GunLog.LoggedShot logged) {
                GunModel.Aim aim = model(models, logged.shot().target(), parameters).aim(logged.shot());
                shotCount++;
                searched += logged.shot().search() ? 1 : 0;
                if (Double.doubleToLongBits(aim.guessFactor()) != Double.doubleToLongBits(logged.guessFactor())
                        || aim.gun() != logged.gun()) {
                    differing++;
                }
                shots.put(logged.shot().time(), logged);
                aims.put(logged.shot().time(), aim);
                if (scope == Scope.ALL || enemies <= 1) {
                    counted.add(logged.shot().time());
                }
            }
        }
        byRound.merge(round, new Tally(shotCount, searched, differing, orphans, gfTries, gfHits, realGfTries,
                realGfHits, firedTries, firedHits, shieldedCount), Tally::plus);
        List<Tally> rounds = new ArrayList<>();
        int last = byRound.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (int r = 0; r <= last; r++) {
            rounds.add(byRound.getOrDefault(r, Tally.ZERO));
        }
        return rounds;
    }

    private static GunModel model(Map<String, GunModel> models, String target, GunParameters parameters) {
        return models.computeIfAbsent(Names.base(target), t -> new GunModel(parameters));
    }
}
