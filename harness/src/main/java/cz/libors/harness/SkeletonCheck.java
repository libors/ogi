package cz.libors.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Walking-skeleton check: the bot and the team must run without crashes or skipped turns and beat the sample bots.
 *
 * <p>Usage: {@code SkeletonCheck <robocodeHome> <robot "name version"> <team "name version"> <output.json>}
 */
public final class SkeletonCheck {

    private static final int DUEL_ROUNDS = 10;
    private static final int MELEE_ROUNDS = 10;
    private static final int TEAM_ROUNDS = 10;
    private static final double MIN_DUEL_SHARE = 0.75;
    private static final double MIN_TEAM_SHARE = 0.70;
    private static final String SITTING_DUCK = "sample.SittingDuck";
    /**
     * Shares of our robots' own turns that may take longer than half the CPU constant, and than all of it.
     * Ogi takes ~10 µs in a median turn; the few longer ones are the JIT at a battle's start and garbage collections,
     * which reached 2.1 ms once against a 2.0 ms constant in a team battle (the engine allows up to a millisecond more,
     * engine-model G-45, and skipped turns are checked on their own).
     */
    private static final double MAX_SHARE_OVER_HALF = 0.005;
    private static final double MAX_SHARE_OVER_CONSTANT = 0.0005;

    private static final List<String> DUEL_OPPONENTS = List.of(
            "sample.Corners", "sample.Crazy", "sample.Fire", "sample.MyFirstJuniorRobot", "sample.MyFirstRobot",
            "sample.RamFire", "sample.SittingDuck", "sample.SpinBot", "sample.Target", "sample.Tracker",
            "sample.TrackFire", "sample.VelociRobot", "sample.Walls");

    private static final List<String> MELEE_OPPONENTS = List.of(
            "sample.Corners", "sample.Crazy", "sample.Fire", "sample.RamFire", "sample.SpinBot", "sample.Tracker",
            "sample.TrackFire", "sample.VelociRobot", "sample.Walls");

    /** A battle together with what the subject must achieve in it. */
    private record Check(BattleSetup setup, String subject, double minShare, int maxRank) {
    }

    /** One line of the report. */
    record Verdict(String label, boolean passed, double share, int rank, int firsts, int rounds,
                   int skippedTurns, List<String> problems, BattleOutcome.TurnTimes turnTimes, BattleOutcome outcome) {
    }

    private SkeletonCheck() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            System.err.println("Usage: SkeletonCheck <robocodeHome> <robot> <team> <output.json>");
            System.exit(2);
        }
        Path home = Path.of(args[0]);
        String robot = args[1];
        String team = args[2];
        Path output = Path.of(args[3]);

        List<Check> checks = new ArrayList<>();
        for (String opponent : DUEL_OPPONENTS) {
            checks.add(new Check(BattleSetup.duel(robot, opponent, DUEL_ROUNDS), robot, MIN_DUEL_SHARE, 1));
        }
        List<String> melee = new ArrayList<>(MELEE_OPPONENTS);
        melee.add(0, robot);
        checks.add(new Check(BattleSetup.melee("melee vs 9 sample bots", melee, MELEE_ROUNDS), robot, 0, 1));
        checks.add(new Check(BattleSetup.team(team, "sampleteam.MyFirstTeam", TEAM_ROUNDS), team, MIN_TEAM_SHARE, 1));

        List<Verdict> verdicts = new ArrayList<>();
        try (BattleRunner runner = new BattleRunner(home)) {
            for (Check check : checks) {
                Verdict v = evaluate(check, runner.run(check.setup()));
                verdicts.add(v);
                System.out.println(format(v));
            }
        }

        Files.createDirectories(output.getParent());
        Files.writeString(output, Json.write(verdicts));
        long failed = verdicts.stream().filter(v -> !v.passed()).count();
        System.out.printf(Locale.ROOT, "%n%d/%d checks passed. Details: %s%n",
                verdicts.size() - failed, verdicts.size(), output);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static Verdict evaluate(Check check, BattleOutcome outcome) {
        // Robocode reports the leader's name for teams, so match on the package prefix of the subject.
        String className = check.subject().split(" ")[0];
        String prefix = className.substring(0, className.lastIndexOf('.') + 1);
        double share = outcome.scoreShare(prefix);
        ContestantResult result = outcome.result(prefix).orElse(null);
        int rank = result == null ? Integer.MAX_VALUE : result.rank();
        int firsts = result == null ? 0 : result.firsts();
        int skipped = outcome.skippedTurns(prefix);
        List<String> problems = outcome.problems(prefix);
        // SittingDuck never fires, so any bullet counted for it is a counting error (e.g. its death explosion).
        boolean shotsCounted = !check.setup().robots().contains(SITTING_DUCK)
                || outcome.shots(SITTING_DUCK).fired() == 0;
        // Our robots report their own turn times: few may come near the idle CPU constant, fewer still exceed it.
        BattleOutcome.TurnTimes times = outcome.turnTimes("cz.libors.");
        boolean light = times.turns() > 0 && times.overIdleConstant() <= MAX_SHARE_OVER_CONSTANT * times.turns()
                && times.overHalf() <= MAX_SHARE_OVER_HALF * times.turns();
        boolean passed = result != null && share >= check.minShare() && rank <= check.maxRank()
                && skipped == 0 && problems.isEmpty() && shotsCounted && light;
        return new Verdict(check.setup().label(), passed, share, rank, firsts, check.setup().rounds(), skipped,
                problems, times, outcome);
    }

    private static String format(Verdict v) {
        int duckShots = v.outcome().shots(SITTING_DUCK).fired();
        BattleOutcome.TurnTimes t = v.turnTimes();
        return String.format(Locale.ROOT, "%-4s %-34s share %5.1f %%  rank %d  firsts %2d/%-2d  skipped %d  problems %d  "
                        + "own turns %5d, max %4.2f ms, over ½ %d  (%.1f s)%s",
                v.passed() ? "OK" : "FAIL", v.label(), 100 * v.share(), v.rank(), v.firsts(), v.rounds(),
                v.skippedTurns(), v.problems().size(), t.turns(), t.maxMicros() / 1000.0, t.overHalf(),
                v.outcome().elapsedMillis() / 1000.0, duckShots > 0 ? "  SittingDuck fired " + duckShots : "");
    }
}
