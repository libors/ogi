package cz.libors.harness.compare;

import cz.libors.harness.BattleOutcome;
import cz.libors.harness.compare.OpponentSet.Format;
import cz.libors.harness.queue.BattleSpec;
import cz.libors.harness.queue.QueueJob;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Turn-by-turn recordings run by the queue: {@link RecordBattles} for one version of a robot against an opponent set,
 * on the seeds {@link Compare} uses, each battle on any free slot. Made for the gun's logs ({@code OgiGunLog}, keys
 * {@code gunshot,gunpass,gunshield}), which the gun bench then reads on this machine, and for full recordings of Ogi
 * or of a foreign robot, to see how a whole game goes.
 *
 * <ul>
 *   <li>The subject is a robot of the job's jars, or, without a jar, a robot the worker has, like an opponent (an
 *       approved foreign jar on a worker that may run foreign robots).</li>
 *   <li>The slot sends each recording back inside its result; the job writes it to
 *       {@code recordings/<dir>/<opponent>_p<pair>.rec.gz} and a line to {@code recordings/<dir>/battles.tsv}
 *       (RecordBattles' columns, the robot's own turn times, then both sides' score components and shots and the
 *       turns of every round). {@code <dir>} is the version for a subject of the job's jars, else the subject's
 *       name and version.</li>
 *   <li>Battles go out pair by pair over all opponents, so a job stopped early covers every opponent alike.</li>
 *   <li>Against a melee set, the battles are the draws of {@link MeleeJob} ({@link MeleeDraws}, the same run seed
 *       gives the same fields): {@code draws=n} records draws 0 to n − 1 into {@code draw<d>.rec.gz}, with an index of
 *       their own ({@link #MELEE_INDEX_COLUMNS}). They run without debug mode by default: among ten robots one whose
 *       thread never stops is likely, and debug mode would wait for it until the timeout.</li>
 *   <li>Against a team set, the subject is a team (ours: {@code cz.libors.OgiTeam}), the battles are pairs as against
 *       a 1v1 set, and like melee draws they run without debug mode and with a melee battle's timeout by default.</li>
 *   <li>By default, like {@code recordBattles}, in the engine's debug mode: the executor runs these battles on slots it
 *       restarted with {@code -Ddebug=true}. Without it a robot that skips a turn can lose that turn's debug
 *       properties, which broke the gun bench's parity against 4 of 125 opponents (2026-10-09). With
 *       {@code debug=false} they run under the turn time limit, so robots skip turns as they would in a rumble; debug
 *       mode would wait up to 600 s for a heavy robot's turn instead.</li>
 *   <li>A recording that exists is not played again, so a restarted server goes on where it stopped. A failed battle
 *       is retried twice, then given up (the job's reason lists it); a lost one goes back to the queue. The default
 *       timeout is 300 s, for a melee or team battle 600 s: in debug mode a robot that hangs holds its battle until
 *       then.</li>
 * </ul>
 */
public final class RecordJob extends QueueJob {

    private static final int ATTEMPTS = 3;
    /** The columns of {@code battles.tsv}: RecordBattles' first, so that readers of those keep working. */
    static final List<String> INDEX_COLUMNS = List.of("file", "opponent", "pair", "seed", "fired", "hits",
            "skippedTurns", "problems", "opponentSkippedTurns", "turns", "turnMaxMicros", "turnsOverHalf",
            "turnsOverIdleConstant", "debug", "score", "survival", "lastSurvivorBonus", "bulletDamage",
            "bulletDamageBonus", "ramDamage", "ramDamageBonus", "firsts", "opponentScore", "opponentSurvival",
            "opponentLastSurvivorBonus", "opponentBulletDamage", "opponentBulletDamageBonus", "opponentRamDamage",
            "opponentRamDamageBonus", "opponentFirsts", "opponentFired", "opponentHits", "elapsedMillis",
            "roundTurns");
    /**
     * The columns of a melee recording's {@code battles.tsv}: the subject's, its mean pairwise share against the nine
     * (the rumble's measure of the battle, as in {@link MeleeJob}) and the opponents, separated by " | ".
     */
    static final List<String> MELEE_INDEX_COLUMNS = List.of("file", "draw", "seed", "subjectIndex", "fired", "hits",
            "skippedTurns", "problems", "opponentSkippedTurns", "turns", "turnMaxMicros", "turnsOverHalf",
            "turnsOverIdleConstant", "debug", "score", "survival", "lastSurvivorBonus", "bulletDamage",
            "bulletDamageBonus", "ramDamage", "ramDamageBonus", "firsts", "share", "elapsedMillis", "roundTurns",
            "opponents");
    private static final long RATE_WINDOW_MILLIS = 120_000;
    /**
     * Seconds per melee battle by default. The longest of 199 draws took 185 s in debug mode (2026-10-15); in debug mode
     * a robot whose thread never stops (SHAM.WOW) holds its battle until the timeout, since the engine waits for every
     * turn.
     */
    static final int MELEE_TIMEOUT = 600;

    private final OpponentSet set;
    private final Format format;
    private final String robot;
    private final String version;
    private final int pairs;
    /** The fields of a melee set, else null. */
    private final MeleeDraws draws;
    private final int rounds;
    private final boolean fixedStarts;
    private final long runSeed;
    private final List<String> keys;
    private final boolean debug;
    private final long timeoutSeconds;
    private final Map<String, String> jars = new LinkedHashMap<>();
    private final Path out;
    private final Deque<Work> todo = new ArrayDeque<>();
    private final Map<String, Integer> attempts = new HashMap<>();
    private final Deque<Long> completions = new ArrayDeque<>();
    private final int total;
    private int done;
    private int outstanding;
    private int failures;
    private final List<String> givenUp = new ArrayList<>();
    private int problems;
    private int skippedSubject;
    private int skippedOpponents;
    private BattleOutcome.TurnTimes turnTimes = BattleOutcome.TurnTimes.NONE;

    private RecordJob(Path dir, Properties spec) throws IOException {
        super(dir, spec);
        set = OpponentSet.parse(spec.getProperty("opponentSet"), Files.readAllLines(dir.resolve("opponents.txt")));
        format = set.format();
        robot = spec.getProperty("robot");
        version = spec.getProperty("version");
        draws = format == Format.MELEE ? new MeleeDraws(set) : null;
        pairs = Integer.parseInt(spec.getProperty(draws == null ? "pairs" : "draws", "1"));
        int roundsOption = Integer.parseInt(spec.getProperty("rounds", "0"));
        rounds = roundsOption > 0 ? roundsOption : format.rounds;
        fixedStarts = Compare.fixedStarts(spec.getProperty("starts", "random"));
        runSeed = Long.parseLong(spec.getProperty("seed", "1"));
        String keyList = spec.getProperty("keys", "");
        keys = keyList.isEmpty() ? null : Arrays.stream(keyList.split(",")).map(String::trim).toList();
        debug = Boolean.parseBoolean(spec.getProperty("debug", Boolean.toString(format == Format.DUEL)));
        // Debug mode has no turn time limit, so a robot that hangs holds the battle until this timeout; a melee or
        // team battle of ten robots takes several times a duel's time.
        timeoutSeconds = Long.parseLong(spec.getProperty("timeout",
                Integer.toString(format == Format.DUEL ? 300 : MELEE_TIMEOUT)));
        spec.stringPropertyNames().stream().filter(k -> k.startsWith("jar.")).sorted()
                .forEach(k -> jars.put(k.substring(4), spec.getProperty(k)));
        out = dir.resolve("recordings").resolve(shipped(spec) ? version
                : RecordBattles.fileName(robot + " " + version));
        // A recording counts once its index line is written: a crash between the two leaves a file to play again.
        Set<String> indexed = new HashSet<>();
        Path index = out.resolve("battles.tsv");
        if (Files.exists(index)) {
            List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String[] f = lines.get(i).split("\t");
                indexed.add(f[0]);
                if (i > 0 && f.length > 12) {
                    // A resumed job counts its earlier recordings again, as CompareJob does from its log: the index
                    // holds the subject's skipped turns and own turn times (both layouts in the same columns).
                    skippedSubject += Integer.parseInt(f[6]);
                    skippedOpponents += Integer.parseInt(f[8]);
                    turnTimes = turnTimes.plus(new BattleOutcome.TurnTimes(Integer.parseInt(f[9]),
                            Long.parseLong(f[10]), Integer.parseInt(f[11]), Integer.parseInt(f[12])));
                }
            }
        }
        // Problems and failures from their log: one line per problem, one per failed battle.
        Path problemLog = dir.resolve("problems.log");
        if (Files.exists(problemLog)) {
            for (String line : Files.readAllLines(problemLog, StandardCharsets.UTF_8)) {
                if (line.contains(") failed: ")) {
                    failures++;
                } else {
                    problems++;
                }
            }
        }
        int count = 0;
        for (int pair = 0; pair < pairs && draws != null; pair++) {
            count++;
            Path file = out.resolve(drawFileName(pair));
            if (Files.exists(file) && indexed.contains(file.getFileName().toString())) {
                done++;
            } else if (state().active()) {
                todo.add(drawWork(pair));
            }
        }
        for (int pair = 0; pair < pairs && draws == null; pair++) {
            for (OpponentSet.Opponent o : set.opponents()) {
                count++;
                Path file = out.resolve(fileName(o.name(), pair));
                if (Files.exists(file) && indexed.contains(file.getFileName().toString())) {
                    done++;
                } else if (state().active()) {
                    todo.add(work(o.name(), pair));
                }
            }
        }
        total = count;
    }

    public static RecordJob open(Path dir, Properties spec) throws IOException {
        return new RecordJob(dir, spec);
    }

    /** Checks a recording job before it is queued and describes it. */
    public static String check(Properties spec, List<String> opponentLines) {
        OpponentSet set = OpponentSet.parse(spec.getProperty("opponentSet"), opponentLines);
        boolean melee = set.format() == Format.MELEE;
        if (melee) {
            new MeleeDraws(set);
            if (!spec.getProperty("starts", "random").equals("random")) {
                throw new IllegalArgumentException("melee battles start at random only, as in a melee comparison");
            }
            if (spec.getProperty("pairs") != null) {
                throw new IllegalArgumentException("a melee set has draws, not opponents: use draws=n");
            }
            Compare.intOption(spec, "draws", 1, 1);
        } else {
            if (spec.getProperty("draws") != null) {
                throw new IllegalArgumentException("draws are for melee sets; " + set.name() + " has pairs");
            }
            Compare.fixedStarts(spec.getProperty("starts", "random"));
            Compare.intOption(spec, "pairs", 1, 1);
        }
        Long.parseLong(spec.getProperty("seed", "1"));
        Compare.intOption(spec, "rounds", 0, 0);
        Compare.intOption(spec, "timeout", set.format() == Format.DUEL ? 300 : MELEE_TIMEOUT, 1);
        String subject = spec.getProperty("robot") + " " + spec.getProperty("version");
        boolean trusted = cz.libors.harness.Sandbox.trusted(subject);
        if (!shipped(spec) && trusted) {
            // Only foreign robots come from the worker; ours and the samples' always come with the job.
            throw new IllegalArgumentException("no jar " + jarName(spec) + " in the job");
        }
        if (shipped(spec) && !trusted) {
            // A foreign robot runs only from the worker's approved jars, which passed the jar check.
            throw new IllegalArgumentException(subject + " is foreign: it comes from the worker's approved jars, "
                    + "not with the job");
        }
        if (set.opponents().stream().anyMatch(o -> o.name().equals(subject))) {
            throw new IllegalArgumentException(subject + " is in the opponent set; a battle against itself would "
                    + "name both robots alike");
        }
        String mode = spec.getProperty("debug", Boolean.toString(set.format() == Format.DUEL));
        if (!mode.equals("true") && !mode.equals("false")) {
            throw new IllegalArgumentException("debug must be true or false: " + mode);
        }
        return String.format(Locale.ROOT, "recordings of %s against %s (%s, keys %s%s%s)", subject, set.name(),
                melee ? spec.getProperty("draws", "1") + " melee draws from " + set.opponents().size() + " opponents"
                        : set.opponents().size() + " opponents × " + spec.getProperty("pairs", "1") + " pairs",
                spec.getProperty("keys", "all"), shipped(spec) ? "" : ", the worker's jar",
                mode.equals("true") ? "" : ", no debug mode");
    }

    private static String jarName(Properties spec) {
        return spec.getProperty("robot") + "_" + spec.getProperty("version") + ".jar";
    }

    /** Whether the subject comes with the job's jars, rather than from the worker's robots. */
    private static boolean shipped(Properties spec) {
        return spec.getProperty("jar." + jarName(spec)) != null;
    }

    private Work work(String opponent, int pair) {
        long seed = Compare.pairSeed(runSeed, opponent, pair);
        BattleSpec battle = new BattleSpec(format.label + " vs " + opponent, format.width, format.height, rounds,
                List.of(robot + " " + version, opponent), seed, fixedStarts ? Compare.mix(seed) : null, true, keys,
                debug);
        return new Work(this, opponent + "\t" + pair, battle, timeoutSeconds);
    }

    /** Draw {@code d} of a melee set, the subject at the draw's place among its nine opponents. */
    private Work drawWork(int d) {
        MeleeDraws.Draw draw = draws.draw(runSeed, d);
        BattleSpec battle = new BattleSpec("melee draw " + d, format.width, format.height, rounds,
                draw.robots(robot + " " + version), draw.seed(), null, true, keys, debug);
        return new Work(this, "draw\t" + d, battle, timeoutSeconds);
    }

    /** The file name of a melee draw's recording. */
    static String drawFileName(int draw) {
        return "draw" + draw + ".rec.gz";
    }

    /** RecordBattles' file name of a recording. */
    static String fileName(String opponent, int pair) {
        return RecordBattles.fileName(opponent) + "_p" + pair + ".rec.gz";
    }

    @Override
    public Map<String, String> jars() {
        return jars;
    }

    /** The opponents, and the subject unless it comes with the job's jars: the worker must have them all. */
    @Override
    public List<String> opponents() {
        List<String> robots = new ArrayList<>(set.opponents().stream().map(OpponentSet.Opponent::name).toList());
        String jar = robot + "_" + version + ".jar";
        if (!jars.containsKey(jar)) {
            robots.add(robot + " " + version);
        }
        return robots;
    }

    @Override
    public boolean debug() {
        return debug;
    }

    @Override
    public boolean needsForeign() {
        return !cz.libors.harness.Sandbox.foreign(opponents()).isEmpty();
    }

    @Override
    public String format() {
        return format.label;
    }

    @Override
    public String title() {
        return String.format(Locale.ROOT, "%s %s · recordings · %s · %d %s%s%s", robot, version, set.name(), pairs,
                draws == null ? "pairs" : "draws",
                keys == null ? "" : " · " + String.join(",", keys), debug ? "" : " · no debug mode");
    }

    @Override
    public List<Work> next(String worker) throws IOException {
        if (state().active() && todo.isEmpty() && outstanding == 0 && done >= total) {
            // Everything was recorded before this server run: the job ends now.
            finish(State.DONE, (done - givenUp.size()) + " recordings in " + out, results());
        }
        if (!state().active() || todo.isEmpty()) {
            return List.of();
        }
        outstanding++;
        return List.of(todo.poll());
    }

    @Override
    public Optional<String> completed(Work work, Map<String, Object> outcomeMap, String worker)
            throws IOException {
        outstanding--;
        String[] key = work.key().split("\t");
        String opponent = key[0];
        int pair = Integer.parseInt(key[1]);
        Object recording = outcomeMap.get("recording");
        if (recording == null) {
            failed(work, "the slot sent no recording", worker);
            outstanding++;
            return Optional.of("the slot sent no recording");
        }
        Files.createDirectories(out);
        if (draws != null) {
            completedDraw(work, outcomeMap, (String) recording, pair, worker);
            return Optional.empty();
        }
        Path file = out.resolve(fileName(opponent, pair));
        Path tmp = out.resolve(file.getFileName() + ".tmp");
        byte[] bytes = Base64.getDecoder().decode((String) recording);
        Files.write(tmp, bytes);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        BattleOutcome outcome = work.spec().outcomeFromMap(outcomeMap);
        String subject = work.spec().robots().get(0);
        BattleOutcome.Shots shots = outcome.shots(subject);
        // A team's robots are named after their own class, not after the team.
        Own own = Own.of(outcome, robotsOf(bytes, subject));
        int skipped = own.skippedTurns();
        int allSkipped = outcome.skippedTurns().values().stream().mapToInt(Integer::intValue).sum();
        BattleOutcome.TurnTimes times = own.turnTimes();
        Path index = out.resolve("battles.tsv");
        String header = Files.exists(index) ? "" : String.join("\t", INDEX_COLUMNS) + "\n";
        List<String> fields = new ArrayList<>(List.of(file.getFileName().toString(), opponent,
                Integer.toString(pair), Long.toString(outcome.seed()), Integer.toString(shots.fired()),
                Integer.toString(shots.hits()), Integer.toString(skipped), Integer.toString(own.problems()),
                Integer.toString(allSkipped - skipped), Integer.toString(times.turns()),
                Long.toString(times.maxMicros()), Integer.toString(times.overHalf()),
                Integer.toString(times.overIdleConstant()), Boolean.toString(work.spec().debug())));
        fields.addAll(components(outcome, subject));
        fields.addAll(components(outcome, opponent));
        BattleOutcome.Shots opponentShots = outcome.shots(opponent);
        fields.add(Integer.toString(opponentShots.fired()));
        fields.add(Integer.toString(opponentShots.hits()));
        fields.add(Long.toString(outcome.elapsedMillis()));
        fields.add(roundTurns(bytes));
        Files.writeString(index, header + String.join("\t", fields) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (!outcome.problems().isEmpty()) {
            Files.write(dir.resolve("problems.log"), outcome.problems().stream()
                            .map(p -> opponent + " pair " + pair + " (" + worker + "): " + p).toList(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        counted(outcome, skipped, allSkipped, times);
        return Optional.empty();
    }

    /** A recording written: the counts, and the job's end after the last one. */
    private void counted(BattleOutcome outcome, int skipped, int allSkipped, BattleOutcome.TurnTimes times)
            throws IOException {
        done++;
        problems += outcome.problems().size();
        skippedSubject += skipped;
        skippedOpponents += allSkipped - skipped;
        turnTimes = turnTimes.plus(times);
        long now = System.currentTimeMillis();
        completions.add(now);
        while (!completions.isEmpty() && completions.peek() < now - RATE_WINDOW_MILLIS) {
            completions.poll();
        }
        if (state().active() && done >= total) {
            finish(State.DONE, (done - givenUp.size()) + " recordings in " + out
                    + (givenUp.isEmpty() ? "" : "; given up after " + ATTEMPTS + " failures: " + givenUp), results());
        }
    }

    /** A melee draw's recording came back: the file, a line of the melee index, the counts. */
    private void completedDraw(Work work, Map<String, Object> outcomeMap, String recording, int d, String worker)
            throws IOException {
        Path file = out.resolve(drawFileName(d));
        Path tmp = out.resolve(file.getFileName() + ".tmp");
        byte[] bytes = Base64.getDecoder().decode(recording);
        Files.write(tmp, bytes);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        BattleOutcome outcome = work.spec().outcomeFromMap(outcomeMap);
        List<String> robots = work.spec().robots();
        String subject = robot + " " + version;
        String share = "";
        try {
            share = Double.toString(MeleeJob.battle(outcome, new Compare.Subject(CompareLog.Side.CANDIDATE, subject,
                    subject, ""), d, robots).share());
        } catch (IllegalStateException e) {
            // A result short of a robot: the recording stays, without the share.
        }
        BattleOutcome.Shots shots = outcome.shots(subject);
        int skipped = outcome.skippedTurns(subject);
        int allSkipped = outcome.skippedTurns().values().stream().mapToInt(Integer::intValue).sum();
        BattleOutcome.TurnTimes times = outcome.turnTimes(subject);
        Path index = out.resolve("battles.tsv");
        String header = Files.exists(index) ? "" : String.join("\t", MELEE_INDEX_COLUMNS) + "\n";
        List<String> fields = new ArrayList<>(List.of(file.getFileName().toString(), Integer.toString(d),
                Long.toString(outcome.seed()), Integer.toString(robots.indexOf(subject)),
                Integer.toString(shots.fired()), Integer.toString(shots.hits()), Integer.toString(skipped),
                Integer.toString(outcome.problems(subject).size()), Integer.toString(allSkipped - skipped),
                Integer.toString(times.turns()), Long.toString(times.maxMicros()), Integer.toString(times.overHalf()),
                Integer.toString(times.overIdleConstant()), Boolean.toString(work.spec().debug())));
        fields.addAll(components(outcome, subject));
        fields.add(share);
        fields.add(Long.toString(outcome.elapsedMillis()));
        fields.add(roundTurns(bytes));
        fields.add(String.join(" | ", robots.stream().filter(r -> !r.equals(subject)).toList()));
        Files.writeString(index, header + String.join("\t", fields) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (!outcome.problems().isEmpty()) {
            Files.write(dir.resolve("problems.log"), outcome.problems().stream()
                            .map(p -> "draw " + d + " (" + worker + "): " + p).toList(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        counted(outcome, skipped, allSkipped, times);
    }

    /** A contestant's score components, in the index's order; zeros if the results do not name it. */
    private static List<String> components(BattleOutcome outcome, String name) {
        return outcome.result(name).map(r -> List.of(r.score(), r.survival(), r.lastSurvivorBonus(),
                        r.bulletDamage(), r.bulletDamageBonus(), r.ramDamage(), r.ramDamageBonus(), r.firsts()))
                .orElse(List.of(0, 0, 0, 0, 0, 0, 0, 0)).stream().map(String::valueOf).toList();
    }

    /**
     * The turns of every round, comma-separated, from the recording's {@code end} records; empty if the bytes are not
     * gzip-compressed (tests send plain text).
     */
    static String roundTurns(byte[] recording) throws IOException {
        if (!gzipped(recording)) {
            return "";
        }
        List<String> turns = new ArrayList<>();
        try (BufferedReader in = lines(recording)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("end\t")) {
                    turns.add(line.split("\t")[2]);
                }
            }
        }
        return String.join(",", turns);
    }

    /**
     * The robots that play for {@code contestant}, from the recording's {@code robot} records: a team's members,
     * which the engine names after their own class ({@code cz.libors.Ogi v (1)} in {@code cz.libors.OgiTeam v (1)}),
     * or the robot itself. Just the contestant if the bytes are not gzip-compressed (tests send plain text) or name
     * no robot of it.
     */
    static List<String> robotsOf(byte[] recording, String contestant) throws IOException {
        List<String> robots = new ArrayList<>();
        if (gzipped(recording)) {
            try (BufferedReader in = lines(recording)) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("robot\t")) {
                        String[] f = line.split("\t");
                        if (f[4].equals(contestant) || f[4].startsWith(contestant + " (")) {
                            robots.add(f[2]);
                        }
                    } else if (!line.startsWith("#") && !line.startsWith("battle\t")) {
                        break;
                    }
                }
            }
        }
        return robots.isEmpty() ? List.of(contestant) : robots;
    }

    /**
     * The skipped turns, problems (engine errors included) and own turn times of a contestant's robots
     * ({@link #robotsOf}): {@link BattleOutcome} keys them by robot, so a team's would not match its name.
     */
    record Own(int skippedTurns, int problems, BattleOutcome.TurnTimes turnTimes) {

        static Own of(BattleOutcome outcome, List<String> robots) {
            int skipped = robots.stream().mapToInt(outcome::skippedTurns).sum();
            int problems = (int) outcome.problems().stream()
                    .filter(p -> p.startsWith("engine:") || robots.stream().anyMatch(p::startsWith)).count();
            BattleOutcome.TurnTimes times = robots.stream().map(outcome::turnTimes)
                    .reduce(BattleOutcome.TurnTimes.NONE, BattleOutcome.TurnTimes::plus);
            return new Own(skipped, problems, times);
        }
    }

    private static boolean gzipped(byte[] recording) {
        return recording.length >= 2 && (recording[0] & 0xff) == 0x1f && (recording[1] & 0xff) == 0x8b;
    }

    private static BufferedReader lines(byte[] recording) throws IOException {
        return new BufferedReader(new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(recording)),
                StandardCharsets.UTF_8));
    }

    @Override
    public void failed(Work work, String reason, String worker) throws IOException {
        outstanding--;
        failures++;
        Files.write(dir.resolve("problems.log"), List.of(work.key().replace('\t', ' ') + " (" + worker + ") failed: "
                + reason), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (!state().active()) {
            return;
        }
        if (attempts.merge(work.key(), 1, Integer::sum) < ATTEMPTS) {
            todo.addFirst(work);
            return;
        }
        // One battle that keeps failing (a robot that hangs in debug mode) costs one recording, not the job.
        givenUp.add(work.key().replace("\t", draws == null ? " pair " : " "));
        done++;
        if (done >= total) {
            finish(State.DONE, (done - givenUp.size()) + " recordings in " + out + "; given up after " + ATTEMPTS
                    + " failures: " + givenUp, results());
        }
    }

    @Override
    public void lost(Work work) {
        outstanding--;
        if (state().active()) {
            todo.addFirst(work);
        }
    }

    @Override
    public void workerLost(String worker) {
        // Every battle stands alone; the lost ones went back to the queue.
    }

    @Override
    public void cancel() throws IOException {
        if (state().active()) {
            finish(State.CANCELLED, "cancelled after " + done + " of " + total + " recordings", results());
        }
    }

    @Override
    public int inFlight() {
        return outstanding;
    }

    private Map<String, String> results() {
        Map<String, String> m = new LinkedHashMap<>();
        progress().forEach((k, v) -> {
            if (v != null && !List.of("remainingPairs", "etaSeconds", "battlesPerSecond", "pendingPairs").contains(k)) {
                m.put(k, v.toString());
            }
        });
        return m;
    }

    @Override
    public Map<String, Object> progress() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unit", "battles");
        m.put("metric", "recordings");
        m.put("opponents", set.opponents().size());
        m.put("opponentSet", set.name());
        m.put("opponentsTitle", set.title().isEmpty() ? null : set.title());
        m.put("pairs", done);
        m.put("pendingPairs", outstanding);
        m.put("maxPairs", total);
        m.put("battles", done);
        m.put("failures", failures);
        m.put("problems", problems);
        m.put("skippedSubject", skippedSubject);
        m.put("skippedOpponents", skippedOpponents);
        m.put("ownTurns", turnTimes.turns());
        m.put("ownTurnMaxMicros", turnTimes.maxMicros());
        m.put("ownTurnsOverHalfCandidate", turnTimes.overHalf());
        m.put("ownTurnsOverIdleCandidate", turnTimes.overIdleConstant());
        double rate = completions.size() < 2 ? Double.NaN
                : (completions.size() - 1) * 1000.0 / Math.max(1, completions.peekLast() - completions.peekFirst());
        m.put("battlesPerSecond", Double.isFinite(rate) ? rate : null);
        m.put("remainingPairs", (double) (total - done));
        m.put("etaSeconds", rate > 0 ? (total - done) / rate : null);
        return m;
    }

    @Override
    public Object detail() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>(progress());
        m.put("recordings", out.toString());
        Path index = out.resolve("battles.tsv");
        if (Files.exists(index)) {
            List<String> lines = Files.readAllLines(index);
            m.put("index", new ArrayList<>(lines.subList(Math.max(0, lines.size() - 30), lines.size())));
        }
        return m;
    }
}
