package cz.libors.harness.queue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code key=value} arguments; a key may repeat, and {@code --} ends them (the rest is returned as is). */
final class Options {

    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private final List<String> rest = new ArrayList<>();

    static Options parse(String[] args, int from) {
        Options o = new Options();
        for (int i = from; i < args.length; i++) {
            if (args[i].equals("--")) {
                for (int j = i + 1; j < args.length; j++) {
                    o.rest.add(args[j]);
                }
                break;
            }
            int eq = args[i].indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("not key=value: " + args[i]);
            }
            o.values.computeIfAbsent(args[i].substring(0, eq), k -> new ArrayList<>()).add(args[i].substring(eq + 1));
        }
        return o;
    }

    String get(String key) {
        List<String> list = values.get(key);
        if (list == null) {
            throw new IllegalArgumentException("missing " + key + "=");
        }
        return list.get(list.size() - 1);
    }

    String get(String key, String fallback) {
        List<String> list = values.get(key);
        return list == null ? fallback : list.get(list.size() - 1);
    }

    List<String> all(String key) {
        return values.getOrDefault(key, List.of());
    }

    boolean has(String key) {
        return values.containsKey(key);
    }

    /** The arguments after {@code --}. */
    List<String> rest() {
        return rest;
    }
}
