package cz.libors.harness.queue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import cz.libors.harness.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The server's web page and its JSON, on the loopback interface only: {@code /} the dashboard, {@code /api/state}
 * the workers and jobs, {@code /api/job?id=} one job in detail, {@code /api/status.txt} the same for a terminal, and
 * {@code POST /api/action?action=cancel|up|down&id=}.
 */
final class Dashboard {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private Dashboard() {
    }

    /**
     * Serves on both loopback addresses: a browser may try {@code localhost} as ::1 first, and another program (a
     * Docker port, say) may hold the port there. A port taken on either address stops the server.
     */
    static void start(Server server, int port) throws IOException {
        for (InetAddress address : List.of(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1"))) {
            HttpServer http;
            try {
                http = HttpServer.create(new InetSocketAddress(address, port), 0);
            } catch (java.net.BindException e) {
                throw new IOException("port " + port + " on " + address.getHostAddress() + " is taken; pass -Pport=<n>",
                        e);
            } catch (java.net.SocketException e) {
                if (address instanceof java.net.Inet6Address) {
                    server.log("no IPv6 loopback (" + e.getMessage() + "); the dashboard is on 127.0.0.1 only");
                    continue;
                }
                throw e;
            }
            contexts(http, server);
            http.start();
        }
    }

    private static void contexts(HttpServer http, Server server) {
        http.createContext("/", ex -> handle(ex, () -> {
            if (!ex.getRequestURI().getPath().equals("/")) {
                send(ex, 404, "text/plain", "not found");
                return;
            }
            try (InputStream in = Dashboard.class.getResourceAsStream("dashboard.html")) {
                send(ex, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }));
        http.createContext("/api/state", ex -> handle(ex, () ->
                send(ex, 200, "application/json", Json.write(server.state()))));
        http.createContext("/api/job", ex -> handle(ex, () -> {
            Object job = server.job(query(ex).getOrDefault("id", ""));
            if (job == null) {
                send(ex, 404, "text/plain", "no such job");
            } else {
                send(ex, 200, "application/json", Json.write(job));
            }
        }));
        http.createContext("/api/status.txt", ex -> handle(ex, () ->
                send(ex, 200, "text/plain; charset=utf-8", text(server.state()))));
        http.createContext("/api/action", ex -> handle(ex, () -> {
            // A page on another site can post a form to localhost, but cannot add a header without our consent.
            if (!ex.getRequestMethod().equals("POST")
                    || !"ogi".equals(ex.getRequestHeaders().getFirst("X-Requested-With"))) {
                send(ex, 403, "text/plain", "POST with X-Requested-With: ogi");
                return;
            }
            Map<String, String> q = query(ex);
            send(ex, 200, "text/plain", server.act(q.getOrDefault("action", ""), q.getOrDefault("id", "")));
        }));
        http.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "dashboard");
            t.setDaemon(true);
            return t;
        }));
    }

    private interface Body {
        void run() throws IOException;
    }

    private static void handle(HttpExchange ex, Body body) throws IOException {
        try {
            String host = ex.getRequestHeaders().getFirst("Host");
            // Only names of this machine: a page whose own name resolves to 127.0.0.1 must not read the state.
            if (host == null || !host.matches("(localhost|127\\.0\\.0\\.1|\\[::1])(:\\d+)?")) {
                send(ex, 403, "text/plain", "use http://localhost");
                return;
            }
            body.run();
        } catch (IOException | RuntimeException e) {
            send(ex, 500, "text/plain", e.toString());
        } finally {
            ex.close();
        }
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) {
            for (String part : q.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    m.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return m;
    }

    /** The state as text, for {@code ./gradlew queueStatus}. */
    @SuppressWarnings("unchecked")
    static String text(Map<String, Object> state) {
        StringBuilder sb = new StringBuilder();
        long now = ((Number) state.get("now")).longValue();
        sb.append("Workers\n");
        for (Object o : (List<Object>) state.get("workers")) {
            Map<String, Object> w = (Map<String, Object>) o;
            Map<String, Object> info = (Map<String, Object>) w.get("info");
            sb.append(String.format(Locale.ROOT, "  %-10s %-10s %2s/%-2s busy  %6s battles  %s%s%n", w.get("name"),
                    w.get("state"), w.get("busy"), w.get("slots"), w.get("battles"),
                    w.get("battlesPerSecond") == null ? "" : String.format(Locale.ROOT, "%.2f battles/s  ",
                            ((Number) w.get("battlesPerSecond")).doubleValue()),
                    info.isEmpty() ? w.get("detail") : "CPU constant " + info.get("cpuConstant") + " ns  "
                            + w.get("detail")));
            if (!String.valueOf(w.get("lastError")).isEmpty()) {
                sb.append("             last error: ").append(w.get("lastError")).append('\n');
            }
        }
        sb.append("\nJobs\n");
        for (Object o : (List<Object>) state.get("jobs")) {
            Map<String, Object> j = (Map<String, Object>) o;
            Map<String, Object> p = (Map<String, Object>) j.get("progress");
            long submitted = ((Number) j.get("submittedAt")).longValue();
            long started = ((Number) j.get("startedAt")).longValue();
            long finished = ((Number) j.get("finishedAt")).longValue();
            String when = finished > 0 && started == 0 ? "ended " + time(finished) + " without starting"
                    : finished > 0 ? "ran " + duration(finished - started) + ", done " + time(finished)
                    : started > 0 ? "running " + duration(now - started) : "waiting " + duration(now - submitted);
            sb.append(String.format(Locale.ROOT, "  %-18s %-9s %s%n", j.get("id"), j.get("state"), j.get("title")));
            if (!String.valueOf(j.get("note")).isEmpty()) {
                sb.append("                               note: ").append(j.get("note")).append('\n');
            }
            sb.append(String.format(Locale.ROOT, "                               %s; %s pairs", when,
                    number(p.get("pairs"), "%.0f")));
            if (p.get("estimate") != null) {
                sb.append(String.format(Locale.ROOT, ", Δ %s ± %s", number(p.get("estimate"), "%+.2f"),
                        number(p.get("halfWidth"), "%.2f")));
            }
            if (p.get("llr") != null) {
                sb.append(String.format(Locale.ROOT, ", LLR %s in [%s, %s]", number(p.get("llr"), "%+.2f"),
                        number(p.get("lower"), "%.2f"), number(p.get("upper"), "%.2f")));
            }
            if (p.get("etaSeconds") != null && finished == 0) {
                sb.append(", ETA ~").append(duration((long) (((Number) p.get("etaSeconds")).doubleValue() * 1000)));
            }
            sb.append('\n');
            if (!String.valueOf(j.get("reason")).isEmpty()) {
                sb.append("                               ").append(j.get("reason")).append('\n');
            }
        }
        return sb.toString();
    }

    private static String number(Object v, String format) {
        return v instanceof Number n ? String.format(Locale.ROOT, format, n.doubleValue()) : "-";
    }

    static String duration(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return String.format(Locale.ROOT, "%d min %02d s", s / 60, s % 60);
        }
        return String.format(Locale.ROOT, "%d h %02d min", s / 3600, s % 3600 / 60);
    }

    private static String time(long millis) {
        return TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
    }
}
