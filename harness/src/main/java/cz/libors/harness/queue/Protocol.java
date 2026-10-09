package cz.libors.harness.queue;

import cz.libors.harness.Json;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The line protocol between the queue server, an executor and its slots: one message per line, {@code @ogi } and a
 * JSON object whose {@code type} names it. Messages are plain ASCII ({@link Json#line}), so no console encoding on
 * the way (an ssh session into Windows PowerShell, CP852) can change them. Any other line is log output.
 */
public final class Protocol {

    static final String PREFIX = "@ogi ";

    private Protocol() {
    }

    /** A message: its type and its fields, as parsed from JSON. */
    public record Message(String type, Map<String, Object> fields) {

        public static Message of(String type, Object... keysAndValues) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (int i = 0; i < keysAndValues.length; i += 2) {
                fields.put((String) keysAndValues[i], keysAndValues[i + 1]);
            }
            return new Message(type, fields);
        }

        public String line() {
            Map<String, Object> all = new LinkedHashMap<>();
            all.put("type", type);
            all.putAll(fields);
            return PREFIX + Json.line(all);
        }

        public boolean has(String key) {
            return fields.get(key) != null;
        }

        public String string(String key) {
            Object v = fields.get(key);
            return v == null ? null : v.toString();
        }

        public long longValue(String key) {
            return ((Number) require(key)).longValue();
        }

        public int intValue(String key) {
            return ((Number) require(key)).intValue();
        }

        public double doubleValue(String key) {
            Object v = fields.get(key);
            return v == null ? Double.NaN : ((Number) v).doubleValue();
        }

        public boolean bool(String key) {
            return Boolean.TRUE.equals(fields.get(key));
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> map(String key) {
            return (Map<String, Object>) require(key);
        }

        @SuppressWarnings("unchecked")
        public List<Object> list(String key) {
            Object v = fields.get(key);
            return v == null ? List.of() : (List<Object>) v;
        }

        private Object require(String key) {
            Object v = fields.get(key);
            if (v == null) {
                throw new IllegalArgumentException("message " + type + " has no " + key);
            }
            return v;
        }
    }

    /** The message on this line, if it is one. */
    public static Optional<Message> parse(String line) {
        if (line == null || !line.startsWith(PREFIX)) {
            return Optional.empty();
        }
        Map<String, Object> fields = new LinkedHashMap<>(Json.parseObject(line.substring(PREFIX.length())));
        Object type = fields.remove("type");
        if (!(type instanceof String)) {
            throw new IllegalArgumentException("message without a type: " + line);
        }
        return Optional.of(new Message((String) type, fields));
    }

    /** Sends messages to a stream, one whole line at a time from any thread. */
    public static final class Channel {

        private final PrintStream out;

        public Channel(OutputStream out) {
            this.out = new PrintStream(out, true, StandardCharsets.US_ASCII);
        }

        public synchronized void send(Message message) {
            out.print(message.line());
            out.print('\n');
            out.flush();
        }

        public synchronized boolean failed() {
            return out.checkError();
        }

        public void close() {
            out.close();
        }
    }

    /**
     * The process's real standard output as a channel, after moving {@link System#out} to standard error: then nothing
     * else in the process (the engine, a library) can write to the protocol's stream.
     */
    public static Channel takeStandardOutput() {
        Channel channel = new Channel(new FileOutputStream(FileDescriptor.out));
        System.setOut(System.err);
        return channel;
    }
}
