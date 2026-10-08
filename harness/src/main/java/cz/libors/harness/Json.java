package cz.libors.harness;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/** Minimal JSON writer for records, collections, maps, strings and numbers; NaN and infinities become null. */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.append('\n').toString();
    }

    private static void write(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            string(sb, s);
        } else if (value instanceof Double d && !Double.isFinite(d)) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            object(sb, map.entrySet().iterator(), indent);
        } else if (value instanceof Collection<?> list) {
            array(sb, list, indent);
        } else if (value.getClass().isRecord()) {
            record(sb, value, indent);
        } else {
            string(sb, value.toString());
        }
    }

    private static void record(StringBuilder sb, Object value, int indent) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        for (RecordComponent c : value.getClass().getRecordComponents()) {
            try {
                fields.put(c.getName(), c.getAccessor().invoke(value));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        object(sb, fields.entrySet().iterator(), indent);
    }

    private static void object(StringBuilder sb, Iterator<? extends Map.Entry<?, ?>> entries, int indent) {
        sb.append('{');
        boolean first = true;
        while (entries.hasNext()) {
            Map.Entry<?, ?> e = entries.next();
            sb.append(first ? "\n" : ",\n").append("  ".repeat(indent + 1));
            string(sb, String.valueOf(e.getKey()));
            sb.append(": ");
            write(sb, e.getValue(), indent + 1);
            first = false;
        }
        sb.append(first ? "}" : "\n" + "  ".repeat(indent) + "}");
    }

    private static void array(StringBuilder sb, Collection<?> list, int indent) {
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            sb.append(first ? "\n" : ",\n").append("  ".repeat(indent + 1));
            write(sb, item, indent + 1);
            first = false;
        }
        sb.append(first ? "]" : "\n" + "  ".repeat(indent) + "]");
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
    }
}
