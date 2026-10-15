package cz.libors.harness.meleebench;

import cz.libors.harness.meleebench.MeleeBattle.Round;
import cz.libors.harness.meleebench.MeleeBattle.Shot;
import cz.libors.ogi.geom.Angles;
import cz.libors.ogi.geom.Field;
import cz.libors.ogi.gun.MeleeGun;
import cz.libors.ogi.gun.MeleeGunLog;
import cz.libors.ogi.gun.MeleeGunParameters;
import cz.libors.ogi.physics.BulletMotion;
import cz.libors.ogi.physics.EngineBullet;
import cz.libors.ogi.physics.RobotBox;

/**
 * Replays one battle's melee gun log through a {@link MeleeGun} with some parameters and scores each bullet the bot
 * fired on the melee gun's aim as if it had flown along the replayed aim: from where the bot fired it, with its
 * power, against every robot's recorded positions, by the engine's rules ({@link BulletMotion}, engine-model G-02:
 * a bullet's move in turn t is tested against the boxes of the end of turn t − 1).
 */
final class MeleeReplay {

    /** A bullet that left the field. */
    static final int MISS = -1;
    /** A bullet still in the air when the round ended: no verdict. */
    static final int OPEN = -2;
    /** Radians: replayed and logged aims closer than this are the same aim. */
    static final double PARITY_TOLERANCE = 1e-9;

    /**
     * What a replay counted.
     *
     * @param shots         bullets scored: fired on the melee gun's aim, neither shot down nor still flying at the
     *                      round's end
     * @param hits          of those, the replayed aim's that hit a robot
     * @param damage        the damage those hits dealt
     * @param noAim         shots the replayed gun had no aim for, scored as misses
     * @param shotDown      bullets an enemy bullet destroyed: the aim did not decide them
     * @param open          replayed bullets still in the air at the round's end
     * @param differing     replayed aims that differ from the log (counted only when asked)
     * @param realChecked   bullets whose real end the bot learned, flown along their real heading
     * @param realHits      of those, the engine's hits
     * @param realDiffering of those, bullets whose flight here ends otherwise than in the engine
     * @param hitsAimed     replayed hits on the robot the replayed gun aimed at (the others hit a robot in the way)
     */
    record Tally(long shots, long hits, double damage, long noAim, long shotDown, long open, long differing,
                 long realChecked, long realHits, long realDiffering, long hitsAimed) {

        static final Tally ZERO = new Tally(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        Tally plus(Tally o) {
            return new Tally(shots + o.shots, hits + o.hits, damage + o.damage, noAim + o.noAim,
                    shotDown + o.shotDown, open + o.open, differing + o.differing, realChecked + o.realChecked,
                    realHits + o.realHits, realDiffering + o.realDiffering, hitsAimed + o.hitsAimed);
        }

        /** Hits per scored bullet, in percent. */
        double firedRate() {
            return shots == 0 ? Double.NaN : 100.0 * hits / shots;
        }

        double damagePerShot() {
            return shots == 0 ? Double.NaN : damage / shots;
        }

        double realRate() {
            return realChecked == 0 ? Double.NaN : 100.0 * realHits / realChecked;
        }
    }

    private MeleeReplay() {
    }

    /**
     * Replays {@code battle} with {@code parameters}.
     *
     * @param parity whether to count the replayed aims that differ from the log: they must not when the logging bot
     *               played with these parameters
     * @param real   whether to fly the real bullets too and check them against how they ended in the engine
     */
    static Tally replay(MeleeBattle battle, MeleeGunParameters parameters, boolean parity, boolean real) {
        Field field = new Field(battle.width(), battle.height());
        MeleeGun.Memory memory = new MeleeGun.Memory();
        long shots = 0;
        long hits = 0;
        double damage = 0;
        long noAim = 0;
        long shotDown = 0;
        long open = 0;
        long differing = 0;
        long realChecked = 0;
        long realHits = 0;
        long realDiffering = 0;
        long hitsAimed = 0;
        for (Round r : battle.rounds()) {
            MeleeGun gun = new MeleeGun(field, memory, parameters);
            for (Object e : r.events()) {
                if (e instanceof MeleeGunLog.Scan s) {
                    gun.scanned(s.name(), s.time(), s.x(), s.y(), s.heading(), s.velocity(), s.closestX(),
                            s.closestY(), s.enemies());
                } else if (e instanceof MeleeBattle.Death d) {
                    gun.robotDied(d.name());
                } else if (e instanceof Shot shot) {
                    MeleeGunLog.Aim a = shot.aim();
                    double aim = gun.aim(a.fireTime(), a.x(), a.y(), a.speed());
                    if (parity && (!same(aim, a.heading()) || !java.util.Objects.equals(gun.aimedAt(), a.aimedAt()))) {
                        differing++;
                        if (Boolean.getBoolean("meleebench.debug") && differing <= 3) {
                            System.out.println("DIFF " + battle.file().getFileName() + " round " + r.round() + " fire "
                                    + a.fireTime() + " logged " + a.heading() + " " + a.aimedAt() + " replayed " + aim
                                    + " " + gun.aimedAt());
                        }
                    }
                    if (real && shot.end() != null && shot.end().how() != 'b') {
                        int v = verdict(battle, r, shot.fireTurn(), shot.fire().x(), shot.fire().y(),
                                shot.fire().heading(), shot.fire().power());
                        realChecked++;
                        boolean engineHit = shot.end().how() == 'h';
                        if (engineHit) {
                            realHits++;
                        }
                        boolean same = engineHit ? v >= 0 && battle.names().get(v).equals(shot.end().robot())
                                : v == MISS;
                        if (!same) {
                            realDiffering++;
                            if (Boolean.getBoolean("meleebench.debug")) {
                                System.out.println("REAL " + battle.file().getFileName() + " round " + r.round()
                                        + " fired " + shot.fireTurn() + " engine " + shot.end().how() + " "
                                        + shot.end().robot() + " bench " + (v >= 0 ? battle.names().get(v) : v));
                            }
                        }
                    }
                    if (shot.end() != null && shot.end().how() == 'b') {
                        shotDown++;
                        continue;
                    }
                    if (Double.isNaN(aim)) {
                        noAim++;
                        shots++;
                        continue;
                    }
                    int v = verdict(battle, r, shot.fireTurn(), shot.fire().x(), shot.fire().y(), aim,
                            shot.fire().power());
                    if (v == OPEN) {
                        open++;
                    } else {
                        shots++;
                        if (v >= 0) {
                            hits++;
                            damage += BulletMotion.damage(shot.fire().power());
                            if (battle.names().get(v).equals(gun.aimedAt())) {
                                hitsAimed++;
                            }
                        }
                    }
                }
            }
        }
        return new Tally(shots, hits, damage, noAim, shotDown, open, differing, realChecked, realHits,
                realDiffering, hitsAimed);
    }

    /** Distances (px) from the bullet's origin to the robot aimed at that bound the bands of {@link #powers}. */
    static final double[] DISTANCE_BANDS = {200, 350, 500};

    /**
     * Replays {@code battle} with {@code parameters} once per power: each bullet fired on the melee gun's aim is aimed
     * and flown with that power instead of its own, at the same moment. Returns per power and band of the distance to
     * the robot aimed at (as {@link #DISTANCE_BANDS} bound them) the bullets scored and their hits: {@code
     * [power][band][0 shots, 1 hits]}.
     */
    static long[][][] powers(MeleeBattle battle, MeleeGunParameters parameters, double[] powers) {
        long[][][] counts = new long[powers.length][DISTANCE_BANDS.length + 1][2];
        Field field = new Field(battle.width(), battle.height());
        for (int k = 0; k < powers.length; k++) {
            MeleeGun.Memory memory = new MeleeGun.Memory();
            double speed = BulletMotion.speed(powers[k]);
            for (Round r : battle.rounds()) {
                MeleeGun gun = new MeleeGun(field, memory, parameters);
                for (Object e : r.events()) {
                    if (e instanceof MeleeGunLog.Scan s) {
                        gun.scanned(s.name(), s.time(), s.x(), s.y(), s.heading(), s.velocity(), s.closestX(),
                                s.closestY(), s.enemies());
                    } else if (e instanceof MeleeBattle.Death d) {
                        gun.robotDied(d.name());
                    } else if (e instanceof Shot shot) {
                        MeleeGunLog.Aim a = shot.aim();
                        double aim = gun.aim(a.fireTime(), a.x(), a.y(), speed);
                        if (shot.end() != null && shot.end().how() == 'b' || Double.isNaN(aim)) {
                            continue;
                        }
                        int target = battle.names().indexOf(gun.aimedAt());
                        double[] at = r.positions()[shot.fireTurn()];
                        double distance = target < 0 || Double.isNaN(at[2 * target]) ? Double.NaN
                                : Math.hypot(at[2 * target] - shot.fire().x(), at[2 * target + 1] - shot.fire().y());
                        int band = 0;
                        while (band < DISTANCE_BANDS.length && !(distance < DISTANCE_BANDS[band])) {
                            band++;
                        }
                        int v = verdict(battle, r, shot.fireTurn(), shot.fire().x(), shot.fire().y(), aim, powers[k]);
                        if (v != OPEN) {
                            counts[k][band][0]++;
                            if (v >= 0) {
                                counts[k][band][1]++;
                            }
                        }
                    }
                }
            }
        }
        return counts;
    }

    /**
     * Whether a replayed aim equals the logged one: within {@link #PARITY_TOLERANCE}, since logs from the Windows worker
     * (x86_64) differ from a replay on the Mac (arm64) in the last bit of some doubles (docs/build-and-run.md,
     * "Determinism across machines"); both NaN if the gun had no aim.
     */
    static boolean same(double replayed, double logged) {
        return Double.isNaN(replayed) ? Double.isNaN(logged) : Math.abs(replayed - logged) <= PARITY_TOLERANCE;
    }

    /**
     * The robot a bullet hits first: fired in turn {@code fireTurn} from ({@code x}, {@code y}) along
     * {@code heading} (mathematical), it moves first in the next turn. Its index, {@link #MISS} if it leaves the field
     * first, {@link #OPEN} if the round ends first.
     */
    static int verdict(MeleeBattle battle, Round round, int fireTurn, double x, double y, double heading,
                       double power) {
        EngineBullet b = new EngineBullet();
        b.x = b.lastX = x;
        b.y = b.lastY = y;
        b.heading = Angles.toRobocode(heading);
        b.power = power;
        int robots = battle.names().size();
        double[][] positions = round.positions();
        for (int turn = fireTurn + 1; turn <= round.turns(); turn++) {
            BulletMotion.move(b);
            if (BulletMotion.hitsWall(b, battle.width(), battle.height())) {
                return MISS;
            }
            double[] p = positions[turn - 1];
            for (int i = 0; i < robots; i++) {
                if (i == battle.subjectIndex() || Double.isNaN(p[2 * i])) {
                    continue;
                }
                if (BulletMotion.hits(b, new RobotBox(p[2 * i], p[2 * i + 1]))) {
                    return i;
                }
            }
        }
        return OPEN;
    }
}
