package cz.libors.harness.compare;

import cz.libors.harness.compare.OpponentSet.Opponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The fields of a melee comparison. Draw d of a run seed gives nine opponents of the set, the subject's place among
 * the ten contestants and the battle's seed, the same for both versions, so a draw is a pair.
 *
 * <p>The rumble draws the ten robots of a battle uniformly from all its participants. A draw here fills a quota per
 * group of the set instead: group g gets 9·w_g/W of the nine places on average, w_g being its weight (the rumble's
 * participants it stands for) and W the sum of the weights. Each group gets the whole part of its share, and the
 * places left go by systematic sampling over the fractional parts, so every group's expected count is exact and its
 * count varies by one at most. Within a group the opponents are drawn uniformly without repeats: each appears with
 * probability 9·w_g/(W·n_g), n_g the group's opponents in the set, which is the rumble's 9/W when the set holds the
 * whole group. Participants the set lacks (a jar denied, say) are thus stood in for by the rest of their group, and
 * the groups keep their weights.
 *
 * <p>The quotas remove the noise of how many strong opponents a field happens to hold. The mean over draws of the
 * subject's pairwise shares in a battle estimates the rumble's APS either way, with no weights to apply.
 */
final class MeleeDraws {

    /** Opponents in a battle: the rumble's ten robots less the subject. */
    static final int OPPONENTS = 9;
    /** Mixed into the run seed, so that a melee draw's numbers differ from a duel pair's. */
    private static final long SALT = 0x6d656c6565L;

    /** A group of the set and the rumble's participants it stands for. */
    record Group(String name, double weight, List<String> opponents) {
    }

    /**
     * One draw: the opponents in the battle's order and the index at which the subject joins them.
     *
     * @param seed the battle's seed, the same for both versions
     */
    record Draw(int number, long seed, List<String> opponents, int subjectIndex) {

        /** The ten contestants in the battle's order, with {@code subject} at {@link #subjectIndex}. */
        List<String> robots(String subject) {
            List<String> robots = new ArrayList<>(opponents);
            robots.add(subjectIndex, subject);
            return robots;
        }
    }

    private final List<Group> groups;
    /** The expected number of places per group. */
    private final double[] quotas;

    MeleeDraws(OpponentSet set) {
        Map<String, List<String>> byGroup = new LinkedHashMap<>();
        for (Opponent o : set.opponents()) {
            byGroup.computeIfAbsent(o.group(), g -> new ArrayList<>()).add(o.name());
        }
        double total = 0;
        for (String g : byGroup.keySet()) {
            Double w = set.weights().get(g);
            if (w == null) {
                throw new IllegalArgumentException(set.name() + ": group [" + g + "] has no weight");
            }
            total += w;
        }
        for (Map.Entry<String, Double> w : set.weights().entrySet()) {
            if (!byGroup.containsKey(w.getKey())) {
                throw new IllegalArgumentException(set.name() + ": group [" + w.getKey() + "] has a weight but no "
                        + "opponents");
            }
        }
        groups = new ArrayList<>();
        quotas = new double[byGroup.size()];
        int i = 0;
        for (Map.Entry<String, List<String>> e : byGroup.entrySet()) {
            double weight = set.weights().get(e.getKey());
            groups.add(new Group(e.getKey(), weight, List.copyOf(e.getValue())));
            quotas[i] = OPPONENTS * weight / total;
            if (Math.floor(quotas[i]) + 1 > e.getValue().size()) {
                throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,
                        "%s: group [%s] needs up to %d opponents in a battle, the set has %d", set.name(),
                        e.getKey(), (int) Math.floor(quotas[i]) + 1, e.getValue().size()));
            }
            i++;
        }
    }

    List<Group> groups() {
        return groups;
    }

    /** The expected number of places of group {@code index} in a battle. */
    double quota(int index) {
        return quotas[index];
    }

    /** Draw {@code number} of a run seed: depends on nothing else but the set. */
    Draw draw(long runSeed, int number) {
        long seed = Compare.mix(Compare.mix(runSeed ^ SALT) + number);
        Random random = new Random(seed);
        int[] counts = new int[groups.size()];
        int left = OPPONENTS;
        for (int g = 0; g < counts.length; g++) {
            counts[g] = (int) Math.floor(quotas[g]);
            left -= counts[g];
        }
        // Systematic sampling: points u, u + 1, ... over the fractional parts laid end to end. A part is shorter
        // than 1, so it holds one point at most, with probability equal to its length.
        double u = random.nextDouble();
        double end = 0;
        int placed = 0;
        int lastFractional = -1;
        for (int g = 0; g < counts.length; g++) {
            double fraction = quotas[g] - Math.floor(quotas[g]);
            if (fraction > 0) {
                lastFractional = g;
            }
            end += fraction;
            while (placed < left && u + placed < end) {
                counts[g]++;
                placed++;
            }
        }
        // The fractional parts add up to the places left; rounding can leave the last point just past their end.
        while (placed < left) {
            counts[lastFractional]++;
            placed++;
        }
        List<String> opponents = new ArrayList<>();
        for (int g = 0; g < counts.length; g++) {
            List<String> pool = new ArrayList<>(groups.get(g).opponents());
            for (int k = 0; k < counts[g]; k++) {
                Collections.swap(pool, k, k + random.nextInt(pool.size() - k));
                opponents.add(pool.get(k));
            }
        }
        Collections.shuffle(opponents, random);
        int subjectIndex = random.nextInt(OPPONENTS + 1);
        return new Draw(number, Compare.mix(seed), List.copyOf(opponents), subjectIndex);
    }
}
