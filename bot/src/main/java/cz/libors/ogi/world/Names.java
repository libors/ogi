package cz.libors.ogi.world;

/** Robot names as the engine gives them. */
public final class Names {

    private Names() {
    }

    /**
     * The name without the engine's copy number: when one robot class and version takes part more than once (the
     * members of a team are copies of one robot, or of a few), the engine names them {@code "a.Bot 1.0 (1)"},
     * {@code "a.Bot 1.0 (2)"} and so on. Copies run the same code, so what we learn about how one aims or moves holds
     * for the others.
     */
    public static String base(String name) {
        int i = copyStart(name);
        return i < 0 ? name : name.substring(0, i - 1);
    }

    /** The engine's copy number in the name, 0 if it has none: a team's members are numbered from 1. */
    public static int number(String name) {
        int i = copyStart(name);
        return i < 0 ? 0 : Integer.parseInt(name.substring(i + 1, name.length() - 1));
    }

    /** The index of the {@code '('} of the name's copy number, -1 if it has none. */
    private static int copyStart(String name) {
        int end = name.length() - 1;
        if (end < 3 || name.charAt(end) != ')') {
            return -1;
        }
        int i = end - 1;
        while (i >= 0 && Character.isDigit(name.charAt(i))) {
            i--;
        }
        if (i == end - 1 || i < 1 || name.charAt(i) != '(' || name.charAt(i - 1) != ' ') {
            return -1;
        }
        return i;
    }
}
