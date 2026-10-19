package cz.libors.harness;

import robocode.control.events.BattleAdaptor;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Part of {@code recordProbes}, in a JVM of its own without the engine's debug mode ({@link ProbeRecordings}): in
 * debug mode no robot skips a turn (engine-model G-17). The team {@code SkipTeam} ({@code SkipProbe}, which computes
 * past the turn limit every tenth turn, and a {@code MessageProbe}, which broadcasts every turn) against six
 * {@code sample.SittingDuck}s. When the engine wakes a robot after skipped turns, it clears every event waiting for it
 * but the critical ones (engine-model G-50), so:
 * <ul>
 *   <li>the death of a robot in a skipped turn or in the turn the probe resumes in never reaches it, and every other
 *       death reaches it in its turn;</li>
 *   <li>it scans nothing in the turn it resumes in, though its radar spins on;</li>
 *   <li>it gets one {@code StatusEvent} in every turn it acts in;</li>
 *   <li>some of its bullets end without an end event, as their {@code Bullet} objects show;</li>
 *   <li>every team message reaches it once, those sent while it skipped in the turn it resumes in.</li>
 * </ul>
 *
 * <p>Usage: {@code SkippedTurnCheck <robocodeHome> <probeVersion>}. Prints {@code RESULT\tOK|FAIL\t<detail>}.
 */
public final class SkippedTurnCheck {

    private static final String PROBE = "cz.libors.probe.SkipProbe";
    private static final String SENDER = "cz.libors.probe.MessageProbe";
    private static final int DUCKS = 6;
    private static final int ROUNDS = 5;
    private static final Pattern SKIPPED = Pattern.compile("^SYSTEM: .+ skipped turn (\\d+)");

    /** One line the probe printed: what reached it in a turn it acted in. */
    record Line(int round, long time, int others, int statuses, Set<Long> skipped, List<String> deaths,
                List<String> scans, List<String> messages, int fired, int ended, int endedSilently) {

        static Line parse(String text) {
            String[] f = text.split("\t", -1);
            String[] bullets = f[9].split(",");
            Set<Long> skipped = new TreeSet<>();
            list(f[5]).forEach(s -> skipped.add(Long.parseLong(s)));
            return new Line(Integer.parseInt(f[1]), Long.parseLong(f[2]), Integer.parseInt(f[3]),
                    Integer.parseInt(f[4]), skipped, list(f[6]), list(f[7]), list(f[8]),
                    Integer.parseInt(bullets[0]), Integer.parseInt(bullets[1]), Integer.parseInt(bullets[2]));
        }

        /** The turn the probe acted in after skipped turns. */
        boolean resumes() {
            return !skipped.isEmpty();
        }

        private static List<String> list(String field) {
            return field.isEmpty() ? List.of() : Arrays.asList(field.split("\\|"));
        }
    }

    /** Per round: when each robot died, the probe's output and the sender's skipped turns. */
    private static final class Watcher extends BattleAdaptor {

        final Map<Integer, Map<String, Integer>> deaths = new HashMap<>();
        final Map<Integer, Set<Long>> senderSkipped = new HashMap<>();
        final StringBuilder probeOutput = new StringBuilder();

        @Override
        public void onTurnEnded(TurnEndedEvent event) {
            ITurnSnapshot snapshot = event.getTurnSnapshot();
            int round = snapshot.getRound();
            for (IRobotSnapshot r : snapshot.getRobots()) {
                if (r.getState() == RobotState.DEAD) {
                    deaths.computeIfAbsent(round, k -> new LinkedHashMap<>()).putIfAbsent(r.getName(),
                            snapshot.getTurn());
                }
                String output = r.getOutputStreamSnapshot();
                if (output == null || output.isEmpty()) {
                    continue;
                }
                if (r.getName().startsWith(PROBE)) {
                    // Printed text is read out per turn; a line may be split between two turns.
                    probeOutput.append(output);
                } else if (r.getName().startsWith(SENDER)) {
                    for (String line : output.split("\\R")) {
                        Matcher m = SKIPPED.matcher(line);
                        if (m.find()) {
                            senderSkipped.computeIfAbsent(round, k -> new HashSet<>()).add(Long.parseLong(m.group(1)));
                        }
                    }
                }
            }
        }
    }

    private SkippedTurnCheck() {
    }

    public static void main(String[] args) {
        String version = args[1];
        List<String> robots = new ArrayList<>();
        robots.add("cz.libors.probe.SkipTeam " + version);
        for (int i = 0; i < DUCKS; i++) {
            robots.add("sample.SittingDuck");
        }
        Watcher watcher = new Watcher();
        BattleOutcome outcome;
        try (BattleRunner runner = new BattleRunner(Path.of(args[0]))) {
            outcome = runner.run(new BattleSetup("skipped-turns", 800, 600, ROUNDS, robots, 42L), watcher);
        }
        String result = verdict(watcher, outcome);
        System.out.println("RESULT\t" + result);
        // The engine leaves non-daemon threads behind.
        System.exit(0);
    }

    private static String verdict(Watcher watcher, BattleOutcome outcome) {
        Map<Integer, TreeMap<Long, Line>> lines = new TreeMap<>();
        for (String text : watcher.probeOutput.toString().split("\\R")) {
            if (text.startsWith("skipprobe\t")) {
                Line line = Line.parse(text);
                lines.computeIfAbsent(line.round(), k -> new TreeMap<>()).put(line.time(), line);
            }
        }
        int resumed = 0;
        int lost = 0;
        int received = 0;
        int unseen = 0;
        List<String> wrong = new ArrayList<>();
        int resumeScans = 0;
        int otherTurns = 0;
        int otherTurnsScanning = 0;
        int badStatus = 0;
        int fired = 0;
        int endedSilently = 0;
        int messages = 0;
        int gaps = 0;
        int repeated = 0;
        int lateMessages = 0;
        for (Map.Entry<Integer, TreeMap<Long, Line>> round : lines.entrySet()) {
            TreeMap<Long, Line> turns = round.getValue();
            for (Line line : turns.values()) {
                badStatus += line.statuses() == 1 ? 0 : 1;
                if (line.resumes()) {
                    resumed++;
                    resumeScans += line.scans().size();
                    for (String m : line.messages()) {
                        lateMessages += Long.parseLong(m.substring(m.lastIndexOf('@') + 1)) < line.time() - 1 ? 1 : 0;
                    }
                } else {
                    otherTurns++;
                    otherTurnsScanning += line.scans().isEmpty() ? 0 : 1;
                }
            }
            Line last = turns.lastEntry().getValue();
            fired += last.fired();
            endedSilently += last.endedSilently();

            for (Map.Entry<String, Integer> death : watcher.deaths.getOrDefault(round.getKey(), Map.of()).entrySet()) {
                if (death.getKey().startsWith(PROBE)) {
                    continue;
                }
                long turn = death.getValue();
                Map.Entry<Long, Line> next = turns.ceilingEntry(turn);
                if (next == null) {
                    // The probe did not act again in the round: the last enemy's death ends it.
                    unseen++;
                    continue;
                }
                Line at = next.getValue();
                boolean inSkip = at.resumes() && (at.skipped().contains(turn) || at.time() == turn);
                boolean told = turns.values().stream().anyMatch(l -> l.deaths().contains(death.getKey()));
                if (inSkip && !told) {
                    lost++;
                } else if (!inSkip && at.deaths().contains(death.getKey())) {
                    received++;
                } else {
                    wrong.add("round " + round.getKey() + ": " + death.getKey() + " died in turn " + turn
                            + (inSkip ? ", in a skipped turn or the resume turn " : ", outside a skip; ")
                            + "the probe acted in turn " + at.time() + " (skipped " + at.skipped() + ") and was "
                            + (told ? "told" : "not told"));
                }
            }

            Map<String, List<Long>> sent = new HashMap<>();
            for (Line line : turns.values()) {
                for (String m : line.messages()) {
                    int at = m.lastIndexOf('@');
                    sent.computeIfAbsent(m.substring(0, at), k -> new ArrayList<>())
                            .add(Long.parseLong(m.substring(at + 1)));
                    messages++;
                }
            }
            Set<Long> senderSkipped = watcher.senderSkipped.getOrDefault(round.getKey(), Set.of());
            for (List<Long> times : sent.values()) {
                TreeSet<Long> distinct = new TreeSet<>(times);
                repeated += times.size() - distinct.size();
                for (long t = distinct.first(); t < distinct.last(); t++) {
                    gaps += distinct.contains(t) || senderSkipped.contains(t) ? 0 : 1;
                }
            }
        }
        boolean passed = resumed >= 10 && wrong.isEmpty() && lost > 0 && received > 0 && resumeScans == 0
                && otherTurnsScanning > otherTurns / 4 && badStatus == 0 && endedSilently > 0 && gaps == 0
                && repeated == 0 && lateMessages > 0 && outcome.problems().isEmpty();
        String detail = String.format(Locale.ROOT, "%d resumes after skipped turns; deaths lost %d, received %d, "
                        + "wrong %d%s, after the probe's last turn %d; scans in resume turns %d, other turns with "
                        + "scans %d of %d; turns without exactly one status event %d; bullets ended without an end "
                        + "event %d of %d; messages %d, lost %d, twice %d, delivered after a skip %d; problems %s",
                resumed, lost, received, wrong.size(), wrong.isEmpty() ? "" : " " + wrong.subList(0,
                        Math.min(3, wrong.size())), unseen, resumeScans, otherTurnsScanning, otherTurns, badStatus,
                endedSilently, fired, messages, gaps, repeated, lateMessages, outcome.problems());
        return (passed ? "OK" : "FAIL") + "\t" + detail;
    }
}
