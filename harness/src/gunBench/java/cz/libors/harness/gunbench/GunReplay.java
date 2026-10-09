package cz.libors.harness.gunbench;

import cz.libors.ogi.gun.GunLog;
import cz.libors.ogi.gun.GunModel;
import cz.libors.ogi.gun.GunParameters;

import java.util.HashMap;
import java.util.Map;

/**
 * Replays a battle's gun log with some {@link GunParameters}: the plans ask a fresh {@link GunModel} per target, as
 * the bot's gun did, and each passed wave teaches it what it taught the bot. The recorded movement stays the same, so
 * against a target that reacts to our aim the replay is optimistic.
 */
public final class GunReplay {

    /**
     * What a replay counted. A guess-factor try is a passed wave whose plan had a guess factor; a fired try is a
     * passed wave with a bullet, aimed by the chosen gun.
     *
     * @param differing plans whose guess factor or gun differ from the logged decision; 0 with the bot's parameters
     * @param orphans   passed waves without their plan in the log
     */
    public record Tally(long shots, long searched, long differing, long orphans, long guessFactorTries,
                        long guessFactorHits, long realGuessFactorTries, long realGuessFactorHits, long firedTries,
                        long firedHits) {

        public static final Tally ZERO = new Tally(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        public Tally plus(Tally o) {
            return new Tally(shots + o.shots, searched + o.searched, differing + o.differing, orphans + o.orphans,
                    guessFactorTries + o.guessFactorTries, guessFactorHits + o.guessFactorHits,
                    realGuessFactorTries + o.realGuessFactorTries, realGuessFactorHits + o.realGuessFactorHits,
                    firedTries + o.firedTries, firedHits + o.firedHits);
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
        for (Object event : battle.events()) {
            if (event instanceof GunBattle.RoundStart) {
                shots.clear();
                aims.clear();
            } else if (event instanceof GunModel.Pass pass) {
                GunLog.LoggedShot logged = shots.get(pass.time());
                if (logged == null) {
                    orphans++;
                    continue;
                }
                GunModel.Aim aim = aims.get(pass.time());
                int hit = model(models, pass.target(), parameters).learn(logged.shot(), aim, pass);
                if (!Double.isNaN(aim.guessFactor())) {
                    boolean gfHit = (hit & 1 << GunModel.GUESS_FACTOR) != 0;
                    gfTries++;
                    gfHits += gfHit ? 1 : 0;
                    if (pass.real()) {
                        realGfTries++;
                        realGfHits += gfHit ? 1 : 0;
                    }
                }
                if (pass.real()) {
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
            }
        }
        return new Tally(shotCount, searched, differing, orphans, gfTries, gfHits, realGfTries, realGfHits,
                firedTries, firedHits);
    }

    private static GunModel model(Map<String, GunModel> models, String target, GunParameters parameters) {
        return models.computeIfAbsent(target, t -> new GunModel(parameters));
    }
}
