package cz.libors.harness;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON for records, collections, maps, strings and numbers; NaN and infinities become null.
 *
 * <p>{@link #write} indents for files people read; {@link #line} writes one line of plain ASCII (other characters
 * as {@code \\uXXXX}), which survives any console encoding, for the queue's protocol. {@link #parse} reads either
 * back into maps, lists, strings, numbers (long if integral, else double), booleans and nulls.
 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0, true);
        return sb.append('\n').toString();
    }

    /** The value on one line of ASCII, without a line end. */
    public static String line(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0, false);
        return sb.toString();
    }

    /** Parses a JSON value; objects keep their key order. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.space();
        Object value = p.value();
        p.space();
        if (p.pos != text.length()) {
            throw p.error("trailing characters");
        }
        return value;
    }

    /** Parses a JSON object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("not a JSON object: " + text);
        }
        return (Map<String, Object>) value;
    }

    private static void write(StringBuilder sb, Object value, int indent, boolean pretty) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            string(sb, s, !pretty);
        } else if (value instanceof Double d && !Double.isFinite(d)) {
            sb.append("null");
        } else if (value instanceof Float f && !Float.isFinite(f)) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            object(sb, map.entrySet().iterator(), indent, pretty);
        } else if (value instanceof Collection<?> list) {
            array(sb, list, indent, pretty);
        } else if (value.getClass().isRecord()) {
            record(sb, value, indent, pretty);
        } else {
            string(sb, value.toString(), !pretty);
        }
    }

    private static void record(StringBuilder sb, Object value, int indent, boolean pretty) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (RecordComponent c : value.getClass().getRecordComponents()) {
            try {
                fields.put(c.getName(), c.getAccessor().invoke(value));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        object(sb, fields.entrySet().iterator(), indent, pretty);
    }

    private static void object(StringBuilder sb, Iterator<? extends Map.Entry<?, ?>> entries, int indent,
                               boolean pretty) {
        sb.append('{');
        boolean first = true;
        while (entries.hasNext()) {
            Map.Entry<?, ?> e = entries.next();
            separate(sb, first, indent + 1, pretty);
            string(sb, String.valueOf(e.getKey()), !pretty);
            sb.append(pretty ? ": " : ":");
            write(sb, e.getValue(), indent + 1, pretty);
            first = false;
        }
        close(sb, '}', first, indent, pretty);
    }

    private static void array(StringBuilder sb, Collection<?> list, int indent, boolean pretty) {
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            separate(sb, first, indent + 1, pretty);
            write(sb, item, indent + 1, pretty);
            first = false;
        }
        close(sb, ']', first, indent, pretty);
    }

    private static void separate(StringBuilder sb, boolean first, int indent, boolean pretty) {
        if (pretty) {
            sb.append(first ? "\n" : ",\n").append("  ".repeat(indent));
        } else if (!first) {
            sb.append(',');
        }
    }

    private static void close(StringBuilder sb, char bracket, boolean empty, int indent, boolean pretty) {
        if (pretty && !empty) {
            sb.append('\n').append("  ".repeat(indent));
        }
        sb.append(bracket);
    }

    private static void string(StringBuilder sb, String s, boolean ascii) {
        sb.append('"');
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20 || ascii && ch > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Object value() {
            if (pos >= text.length()) {
                throw error("unexpected end");
            }
            char ch = text.charAt(pos);
            return switch (ch) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            space();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                space();
                if (peek() != '"') {
                    throw error("expected a key");
                }
                String key = string();
                space();
                expect(':');
                space();
                map.put(key, value());
                space();
                if (peek() == ',') {
                    pos++;
                } else {
                    expect('}');
                    return map;
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++;
            space();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                space();
                list.add(value());
                space();
                if (peek() == ',') {
                    pos++;
                } else {
                    expect(']');
                    return list;
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw error("unterminated string");
                }
                char ch = text.charAt(pos++);
                if (ch == '"') {
                    return sb.toString();
                }
                if (ch != '\\') {
                    sb.append(ch);
                    continue;
                }
                if (pos >= text.length()) {
                    throw error("unterminated escape");
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw error("short \\u escape");
                        }
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("bad escape \\" + esc);
                }
            }
        }

        private Object number() {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String s = text.substring(start, pos);
            if (s.isEmpty()) {
                throw error("unexpected character");
            }
            if (s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
                try {
                    return Long.parseLong(s);
                } catch (NumberFormatException e) {
                    // too large for a long
                }
            }
            return Double.parseDouble(s);
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) {
                throw error("unexpected literal");
            }
            pos += word.length();
            return value;
        }

        private char peek() {
            if (pos >= text.length()) {
                throw error("unexpected end");
            }
            return text.charAt(pos);
        }

        private void expect(char ch) {
            if (peek() != ch) {
                throw error("expected '" + ch + "'");
            }
            pos++;
        }

        void space() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        IllegalArgumentException error(String problem) {
            return new IllegalArgumentException("JSON: " + problem + " at " + pos + " in "
                    + (text.length() > 200 ? text.substring(0, 200) + "…" : text));
        }
    }
}
