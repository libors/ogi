package cz.libors.harness.queue;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * The queue from a terminal: {@code status} prints what the dashboard shows; {@code cancel}, {@code up} and
 * {@code down} act on a job. Without a running server, {@code status} lists the jobs from their directories.
 *
 * <p>Usage: {@code QueueClient status|cancel|up|down [id=<job>] [port=8765] [state=<dir>]}.
 */
public final class QueueClient {

    private QueueClient() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String command = args.length > 0 ? args[0] : "status";
        Options o = Options.parse(args, 1);
        String base = "http://localhost:" + o.get("port", "8765");
        HttpClient http = HttpClient.newHttpClient();
        try {
            if (command.equals("status")) {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/status.txt"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                System.out.print(r.body());
            } else {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/action?action="
                                + command + "&id=" + o.get("id"))).header("X-Requested-With", "ogi")
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                System.out.println(r.body());
            }
        } catch (ConnectException e) {
            System.out.println("The queue server is not running (start it with ./gradlew queueServer).");
            if (command.equals("status") && o.has("state")) {
                offline(Path.of(o.get("state")));
            }
        }
    }

    private static void offline(Path state) throws IOException {
        Path jobs = state.resolve("jobs");
        if (!Files.isDirectory(jobs)) {
            return;
        }
        List<Path> dirs;
        try (Stream<Path> list = Files.list(jobs)) {
            dirs = list.filter(Files::isDirectory).sorted().toList();
        }
        for (Path dir : dirs) {
            Properties spec = load(dir.resolve("job.properties"));
            Properties status = load(dir.resolve("status.properties"));
            System.out.printf("  %-18s %-9s %s vs %s on %s %s%n", dir.getFileName(), status.getProperty("state"),
                    spec.getProperty("candidate"), spec.getProperty("baseline"), spec.getProperty("opponentSet"),
                    status.getProperty("reason", ""));
        }
    }

    private static Properties load(Path file) throws IOException {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
        }
        return p;
    }
}
