package cz.libors.harness.queue;

import cz.libors.harness.compare.CompareJob;
import cz.libors.harness.queue.Protocol.Channel;
import cz.libors.harness.queue.Protocol.Message;
import cz.libors.harness.queue.QueueJob.State;
import cz.libors.harness.queue.QueueJob.Work;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The queue's server: runs on the development machine, keeps the jobs, drives the workers and serves the dashboard.
 *
 * <p>Jobs are directories under {@code <state>/jobs/}, written by {@link Submit} whether the server runs or not, and
 * picked up within seconds. The server starts each worker's {@link Executor} when there is work for it (over ssh for
 * a Windows worker, after syncing the bundle; as a local process for {@code local}), hands out battles while the
 * worker has free slots, and stops the executor after a few idle minutes. When a worker goes away, its battles go
 * back to their jobs. Every state change of a job is on disk, so a restarted server resumes where it stopped.
 *
 * <p>Usage: {@code Server state=<dir> bundle=<dir> workers=<name>:<slots>[,...] [port=8765] [cpuFactor=1.5]
 * [jvm=<option>]... localJava=<java> [localSecurityManager=true]}.
 */
public final class Server {

    private static final long TICK_MILLIS = 500;
    private static final long SCAN_MILLIS = 2000;
    private static final long PING_MILLIS = 20_000;
    private static final long SILENCE_MILLIS = 120_000;
    private static final long STARTUP_MILLIS = 600_000;
    private static final long IDLE_STOP_MILLIS = 300_000;
    private static final long RATE_WINDOW_MILLIS = 120_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path stateDir;
    private final BattleCache battleCache;
    private final Path jobsDir;
    private final Path jarsDir;
    private final Path localBundle;
    private final List<Link> links = new ArrayList<>();
    private final Map<String, QueueJob> jobs = new LinkedHashMap<>();
    private final Set<String> unreadable = new HashSet<>();
    private final long startedAt = System.currentTimeMillis();
    private final PrintStream logFile;
    private long nextId;
    private long lastScan;

    /** A battle sent to a worker and not yet back. */
    private static final class InFlight {
        final long id;
        final Work work;
        final long sentAt;
        long startedAt;
        int slot = -1;

        InFlight(long id, Work work, long sentAt) {
            this.id = id;
            this.work = work;
            this.sentAt = sentAt;
        }
    }

    /** A worker and the server's connection to its executor. */
    final class Link {
        final WorkerConfig config;
        String state = "offline";
        String detail = "";
        long since = System.currentTimeMillis();
        int generation;
        Process process;
        Channel channel;
        long lastMessage;
        long lastPing;
        long idleSince;
        long backoffUntil;
        int connectFailures;
        Map<String, Object> info = Map.of();
        Set<String> robots = Set.of();
        /** The SHA-256 of the jar each robot or team is loaded from, as the worker reported it ("builtin" for none). */
        Map<String, String> robotJars = Map.of();
        final Map<Long, InFlight> inFlight = new LinkedHashMap<>();
        final Deque<Work> pending = new ArrayDeque<>();
        final Set<String> deployed = new HashSet<>();
        final Set<String> deploying = new HashSet<>();
        final Deque<Long> completions = new ArrayDeque<>();
        int battles;
        int failures;
        int restarts;
        String lastError = "";

        Link(WorkerConfig config) {
            this.config = config;
        }

        String name() {
            return config.name();
        }

        void state(String newState, String newDetail) {
            if (!newState.equals(state)) {
                since = System.currentTimeMillis();
            }
            state = newState;
            detail = newDetail;
        }
    }

    private Server(Path stateDir, Path localBundle) throws IOException {
        this.stateDir = stateDir.toAbsolutePath();
        this.jobsDir = this.stateDir.resolve("jobs");
        this.jarsDir = this.stateDir.resolve("jars");
        this.localBundle = localBundle.toAbsolutePath();
        Files.createDirectories(jobsDir);
        Files.createDirectories(jarsDir);
        logFile = new PrintStream(Files.newOutputStream(this.stateDir.resolve("server.log"), StandardOpenOption.CREATE,
                StandardOpenOption.APPEND), true, StandardCharsets.UTF_8);
        battleCache = new BattleCache(this.stateDir.resolve("battle-cache.jsonl"), new BattleCache.Workers() {
            @Override
            public Optional<Map<String, String>> environment(String worker) {
                return link(worker).filter(l -> !l.info.isEmpty()).map(Server::battleEnvironment);
            }

            @Override
            public Optional<String> robotJar(String worker, String robot) {
                return link(worker).map(l -> l.robotJars.get(robot));
            }
        });
    }

    private Optional<Link> link(String worker) {
        return links.stream().filter(l -> l.name().equals(worker)).findFirst();
    }

    /** What decides a battle on a worker, for the battle cache; not the CPU constant, which it measures anew. */
    private static Map<String, String> battleEnvironment(Link link) {
        Map<String, String> m = new TreeMap<>();
        for (String key : List.of("os", "java", "robocode", "jvm", "cpuFactor", "sandbox")) {
            m.put(key, String.valueOf(link.info.get(key)));
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        Options o = Options.parse(args, 0);
        Server server = new Server(Path.of(o.get("state")), Path.of(o.get("bundle")));
        double cpuFactor = Double.parseDouble(o.get("cpuFactor", "1.5"));
        for (String entry : o.get("workers").split(",")) {
            if (!entry.isBlank()) {
                server.links.add(server.new Link(WorkerConfig.parse(entry, server.stateDir, server.localBundle,
                        o.get("localJava", "java"), Boolean.parseBoolean(o.get("localSecurityManager", "false")),
                        o.all("jvm"), cpuFactor)));
            }
        }
        int port = Integer.parseInt(o.get("port", "8765"));
        synchronized (server) {
            server.scanJobs();
        }
        Dashboard.start(server, port);
        server.log("queue server on http://localhost:" + port + "/ ; state " + server.stateDir + "; workers "
                + server.links.stream().map(l -> l.name() + " (" + l.config.slots() + " slots)").toList());
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "shutdown"));
        server.loop();
    }

    // ------------------------------------------------------------------------------------------------ main loop

    private void loop() throws InterruptedException {
        while (true) {
            synchronized (this) {
                try {
                    tick();
                } catch (IOException | RuntimeException e) {
                    log("tick failed: " + e);
                    e.printStackTrace();
                }
            }
            Thread.sleep(TICK_MILLIS);
        }
    }

    private void tick() throws IOException {
        long now = System.currentTimeMillis();
        if (now - lastScan > SCAN_MILLIS) {
            scanJobs();
            lastScan = now;
        }
        for (QueueJob job : activeJobs()) {
            if (links.stream().noneMatch(l -> eligible(l, job))) {
                fail(job, "no configured worker may run foreign robots; workers: "
                        + links.stream().map(Link::name).toList());
            }
        }
        for (Link link : links) {
            boolean wanted = activeJobs().stream().anyMatch(j -> eligible(link, j));
            switch (link.state) {
                case "offline" -> {
                    if (wanted) {
                        connect(link);
                    }
                }
                case "backoff" -> {
                    if (wanted && now >= link.backoffUntil) {
                        connect(link);
                    }
                }
                case "connecting", "starting" -> {
                    if (now - link.since > STARTUP_MILLIS) {
                        drop(link, "did not start within " + STARTUP_MILLIS / 1000 + " s");
                    }
                }
                case "ready" -> {
                    if (now - link.lastMessage > SILENCE_MILLIS) {
                        drop(link, "silent for " + SILENCE_MILLIS / 1000 + " s");
                        continue;
                    }
                    if (now - link.lastPing > PING_MILLIS) {
                        link.channel.send(Message.of("ping"));
                        link.lastPing = now;
                    }
                    dispatch(link);
                    if (!link.inFlight.isEmpty() || !link.pending.isEmpty() || wanted) {
                        link.idleSince = 0;
                    } else if (link.idleSince == 0) {
                        link.idleSince = now;
                    } else if (now - link.idleSince > IDLE_STOP_MILLIS) {
                        log(link.name() + ": idle, stopping its executor");
                        link.state("stopping", "idle");
                        link.channel.send(Message.of("shutdown"));
                    }
                }
                default -> {
                    // stopping: the executor's end of output ends it
                }
            }
        }
    }

    /** Reads job directories that are new since the last scan. */
    private void scanJobs() throws IOException {
        List<Path> dirs;
        try (Stream<Path> list = Files.list(jobsDir)) {
            dirs = list.filter(Files::isDirectory).sorted().toList();
        }
        for (Path dir : dirs) {
            String id = dir.getFileName().toString();
            if (jobs.containsKey(id) || unreadable.contains(id) || !Files.exists(dir.resolve("status.properties"))) {
                continue;
            }
            try {
                QueueJob job = QueueJob.open(dir);
                if (job instanceof CompareJob c) {
                    c.environment(this::environment);
                    c.cache(battleCache);
                }
                jobs.put(id, job);
                if (job.state().active()) {
                    log("job " + id + " " + job.state().name().toLowerCase(Locale.ROOT) + ": " + job.title());
                }
            } catch (IOException | RuntimeException e) {
                unreadable.add(id);
                log("job " + id + " cannot be read: " + e);
            }
        }
    }

    private List<QueueJob> activeJobs() {
        return jobs.values().stream().filter(j -> j.state().active())
                .sorted(Comparator.comparingDouble(QueueJob::order).thenComparing(QueueJob::submittedAt)).toList();
    }

    private static boolean eligible(Link link, QueueJob job) {
        return !job.needsForeign() || link.config.foreign();
    }

    // ------------------------------------------------------------------------------------------------- dispatch

    private void dispatch(Link link) throws IOException {
        while (link.state.equals("ready") && link.inFlight.size() < link.config.slots()) {
            Work work = link.pending.poll();
            if (work != null && !work.job().state().active()) {
                work.job().lost(work);
                continue;
            }
            if (work == null) {
                work = nextWork(link);
            }
            if (work == null) {
                return;
            }
            long id = ++nextId;
            link.inFlight.put(id, new InFlight(id, work, System.currentTimeMillis()));
            link.channel.send(Message.of("battle", "id", id, "spec", work.spec().toMap(),
                    "timeout", work.timeoutSeconds()));
        }
    }

    private Work nextWork(Link link) throws IOException {
        for (QueueJob job : activeJobs()) {
            if (!eligible(link, job)) {
                continue;
            }
            List<String> missing = job.opponents().stream().filter(o -> !link.robots.contains(o)).toList();
            if (!missing.isEmpty()) {
                fail(job, "opponents missing on " + link.name() + ": " + missing);
                continue;
            }
            if (!link.deployed.containsAll(job.jars().keySet())) {
                deploy(link, job);
                continue;
            }
            List<Work> works = job.next(link.name());
            if (works.isEmpty()) {
                continue;
            }
            if (job.state() == State.QUEUED) {
                job.started();
                log("job " + job.id() + " started on " + link.name());
            }
            link.pending.addAll(works.subList(1, works.size()));
            return works.get(0);
        }
        return null;
    }

    /** Copies a job's jars to the worker in the background, then tells the executor to take them. */
    private void deploy(Link link, QueueJob job) {
        List<String> files = job.jars().keySet().stream()
                .filter(f -> !link.deployed.contains(f) && !link.deploying.contains(f)).toList();
        if (files.isEmpty()) {
            return;
        }
        link.deploying.addAll(files);
        int generation = link.generation;
        Map<String, String> shas = new LinkedHashMap<>(job.jars());
        Thread t = new Thread(() -> {
            try {
                if (link.config.local()) {
                    Path target = Path.of(link.config.jarsDir());
                    Files.createDirectories(target);
                    for (String f : files) {
                        Files.copy(jarsDir.resolve(f), target.resolve(f), StandardCopyOption.REPLACE_EXISTING);
                    }
                } else {
                    Map<String, String> remote = Ssh.remoteHashes(link.name(), link.config.jarsDir());
                    List<Path> upload = files.stream().filter(f -> !shas.get(f).equals(remote.get(f)))
                            .map(jarsDir::resolve).toList();
                    if (!upload.isEmpty()) {
                        Ssh.upload(link.name(), Map.of("", upload), link.config.jarsDir());
                    }
                }
                synchronized (this) {
                    if (link.generation == generation && link.channel != null) {
                        files.forEach(f -> link.channel.send(Message.of("deploy", "file", f, "sha", shas.get(f))));
                    }
                }
            } catch (IOException | RuntimeException e) {
                synchronized (this) {
                    link.deploying.removeAll(files);
                    log(link.name() + ": copying jars failed: " + e);
                    fail(job, "copying its jars to " + link.name() + " failed: " + e);
                }
            }
        }, "deploy-" + link.name());
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------------------------------- the executors

    private void connect(Link link) {
        int generation = ++link.generation;
        link.state("connecting", link.config.local() ? "starting the executor" : "syncing the bundle");
        Thread t = new Thread(() -> {
            try {
                if (!link.config.local()) {
                    int copied = Ssh.sync(link.name(), localBundle, link.config.bundle());
                    log(link.name() + ": bundle synced, " + copied + " file(s) copied");
                }
                List<String> command = new ArrayList<>();
                List<String> java = new ArrayList<>(List.of("-cp", link.config.bundle() + "/lib/harness.jar",
                        Executor.class.getName()));
                java.addAll(link.config.executorArguments("serve"));
                if (link.config.local()) {
                    command.add(link.config.java());
                    command.addAll(java);
                } else {
                    command.addAll(List.of("ssh", "-T"));
                    command.addAll(Ssh.OPTIONS);
                    command.addAll(List.of("-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=4",
                            link.name(), Ssh.command(link.config.java(), java)));
                }
                Process p = new ProcessBuilder(command).start();
                synchronized (this) {
                    if (link.generation != generation) {
                        p.destroyForcibly();
                        return;
                    }
                    link.process = p;
                    link.channel = new Channel(p.getOutputStream());
                    link.lastMessage = System.currentTimeMillis();
                    link.state("starting", "waiting for the executor");
                }
                read(link, generation, p);
            } catch (IOException | RuntimeException e) {
                synchronized (this) {
                    if (link.generation == generation) {
                        backoff(link, e.toString());
                    }
                }
            }
        }, "connect-" + link.name());
        t.setDaemon(true);
        t.start();
    }

    private void read(Link link, int generation, Process p) {
        Thread errors = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(),
                    StandardCharsets.ISO_8859_1))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!Ssh.noise(line) && !line.isBlank()) {
                        log(link.name() + " (stderr): " + line);
                    }
                }
            } catch (IOException e) {
                // gone
            }
        }, "stderr-" + link.name());
        errors.setDaemon(true);
        errors.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),
                StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = r.readLine()) != null) {
                Optional<Message> m;
                try {
                    m = Protocol.parse(line);
                } catch (RuntimeException e) {
                    log(link.name() + ": unreadable message: " + line);
                    continue;
                }
                if (m.isEmpty()) {
                    if (!line.isBlank()) {
                        log(link.name() + ": " + line);
                    }
                    continue;
                }
                synchronized (this) {
                    if (link.generation != generation) {
                        return;
                    }
                    try {
                        handle(link, m.get());
                    } catch (IOException | RuntimeException e) {
                        log(link.name() + ": handling " + m.get().type() + " failed: " + e);
                        e.printStackTrace();
                    }
                }
            }
        } catch (IOException e) {
            // the connection is gone
        }
        int exit;
        try {
            exit = p.waitFor();
        } catch (InterruptedException e) {
            exit = -1;
        }
        synchronized (this) {
            if (link.generation == generation) {
                disconnected(link, "the executor ended (exit " + exit + ")");
            }
        }
    }

    private void handle(Link link, Message m) throws IOException {
        long now = System.currentTimeMillis();
        link.lastMessage = now;
        switch (m.type()) {
            case "hello" -> link.state("starting", "executor started (pid " + m.string("pid") + ", " + m.string("os")
                    + ")");
            case "status" -> link.state(link.state, m.string("text"));
            case "ready" -> {
                Map<String, Object> info = new LinkedHashMap<>(m.fields());
                Set<String> names = new HashSet<>();
                for (Object entry : m.list("robots")) {
                    names.addAll(((List<?>) entry).stream().map(String::valueOf).toList());
                }
                info.remove("robots");
                Map<String, String> jarsByRobot = new LinkedHashMap<>();
                Object reported = info.remove("robotJars");
                if (reported instanceof Map<?, ?> jarMap) {
                    jarMap.forEach((robot, sha) -> jarsByRobot.put(String.valueOf(robot), String.valueOf(sha)));
                }
                link.info = info;
                link.robots = names;
                link.robotJars = jarsByRobot;
                link.deployed.clear();
                link.deploying.clear();
                link.connectFailures = 0;
                link.idleSince = 0;
                link.lastPing = now;
                link.state("ready", "");
                log(link.name() + ": ready, " + m.intValue("slots") + " slots, CPU constant "
                        + m.longValue("cpuConstant") + " ns (" + m.longValue("cpuConstantIdle") + " × "
                        + m.doubleValue("cpuFactor") + "), " + m.string("java") + ", sandbox " + m.bool("sandbox")
                        + (m.list("rejected").isEmpty() ? "" : ", foreign jars left out: " + m.list("rejected")));
                dispatch(link);
            }
            case "started" -> {
                InFlight f = link.inFlight.get(m.longValue("id"));
                if (f != null) {
                    f.startedAt = now;
                    f.slot = m.intValue("slot");
                }
            }
            case "result" -> {
                InFlight f = link.inFlight.remove(m.longValue("id"));
                if (f != null) {
                    link.battles++;
                    link.completions.add(now);
                    while (!link.completions.isEmpty() && link.completions.peek() < now - RATE_WINDOW_MILLIS) {
                        link.completions.poll();
                    }
                    Map<String, Object> outcome = m.map("outcome");
                    QueueJob job = f.work.job();
                    boolean active = job.state().active();
                    job.completed(f.work, outcome, link.name());
                    record(link, f, "ok" + turnTimes(outcome), ((Number) outcome.get("elapsedMillis")).longValue());
                    announceEnd(job, active);
                }
                dispatch(link);
            }
            case "failed" -> {
                InFlight f = link.inFlight.remove(m.longValue("id"));
                if (f != null) {
                    link.failures++;
                    link.lastError = m.string("reason");
                    log(link.name() + ": battle failed: " + m.string("reason"));
                    QueueJob job = f.work.job();
                    boolean active = job.state().active();
                    job.failed(f.work, m.string("reason"), link.name());
                    record(link, f, "failed: " + m.string("reason"), -1);
                    announceEnd(job, active);
                }
                dispatch(link);
            }
            case "restart" -> {
                link.restarts++;
                log(link.name() + ": slot " + m.string("slot") + " restarted: " + m.string("reason"));
            }
            case "deployed" -> {
                String file = m.string("file");
                link.deploying.remove(file);
                if (m.has("error")) {
                    log(link.name() + ": deploying " + file + " failed: " + m.string("error"));
                    for (QueueJob job : activeJobs()) {
                        if (job.jars().containsKey(file)) {
                            fail(job, "deploying " + file + " on " + link.name() + " failed: " + m.string("error"));
                        }
                    }
                } else {
                    link.deployed.add(file);
                    dispatch(link);
                }
            }
            case "pong" -> {
                // lastMessage is enough
            }
            case "log" -> log(link.name() + ": " + m.string("text"));
            case "fatal" -> {
                link.lastError = m.string("message");
                log(link.name() + ": executor failed: " + m.string("message"));
            }
            default -> log(link.name() + ": unknown message " + m.type());
        }
    }

    /** Ends the connection now; the reader then reports it. */
    private void drop(Link link, String reason) {
        log(link.name() + ": dropping the connection: " + reason);
        link.lastError = reason;
        if (link.process != null) {
            link.process.destroyForcibly();
        } else {
            disconnected(link, reason);
        }
    }

    private void disconnected(Link link, String reason) {
        boolean stopping = link.state.equals("stopping");
        link.generation++;
        Set<QueueJob> touched = new HashSet<>();
        try {
            for (InFlight f : link.inFlight.values()) {
                f.work.job().lost(f.work);
                record(link, f, "lost: " + reason, -1);
                touched.add(f.work.job());
            }
            for (Work w : link.pending) {
                w.job().lost(w);
                touched.add(w.job());
            }
            for (QueueJob job : jobs.values()) {
                job.workerLost(link.name());
            }
        } catch (IOException e) {
            log(link.name() + ": returning its battles failed: " + e);
        }
        link.inFlight.clear();
        link.pending.clear();
        link.deployed.clear();
        link.deploying.clear();
        if (link.process != null) {
            link.process.destroyForcibly();
        }
        link.process = null;
        link.channel = null;
        if (stopping) {
            link.state("offline", "");
            log(link.name() + ": stopped");
        } else {
            backoff(link, reason);
        }
        if (!touched.isEmpty()) {
            log(link.name() + ": battles of " + touched.stream().map(QueueJob::id).toList() + " went back to the queue");
        }
    }

    private void backoff(Link link, String reason) {
        link.connectFailures++;
        long delay = Math.min(300_000, 10_000L << Math.min(5, link.connectFailures - 1));
        link.backoffUntil = System.currentTimeMillis() + delay;
        link.lastError = reason;
        link.state("backoff", reason + "; retrying in " + delay / 1000 + " s");
        log(link.name() + ": " + reason + "; retrying in " + delay / 1000 + " s");
    }

    private void shutdown() {
        synchronized (this) {
            for (Link link : links) {
                if (link.channel != null) {
                    try {
                        link.channel.send(Message.of("shutdown"));
                        link.channel.close();
                    } catch (RuntimeException e) {
                        // gone
                    }
                }
            }
        }
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            for (Link link : links) {
                if (link.process != null) {
                    link.process.destroyForcibly();
                }
            }
            log("queue server stopped");
        }
    }

    // ---------------------------------------------------------------------------------------------- bookkeeping

    private void fail(QueueJob job, String reason) {
        try {
            job.fail(reason);
            log("job " + job.id() + " failed: " + reason);
        } catch (IOException e) {
            log("job " + job.id() + " could not be failed: " + e);
        }
    }

    private void announceEnd(QueueJob job, boolean wasActive) {
        if (wasActive && !job.state().active()) {
            log("job " + job.id() + " " + job.state().name().toLowerCase(Locale.ROOT) + ": " + job.reason());
        }
    }

    /** Our robots' own turn times in an outcome (P-04), for the dispatch log: the longest, and how many went over. */
    private static String turnTimes(Map<String, Object> outcome) {
        Object reported = outcome.get("turnTimes");
        if (!(reported instanceof Map<?, ?> times) || times.isEmpty()) {
            return "";
        }
        long max = 0;
        long overHalf = 0;
        long over = 0;
        for (Object v : times.values()) {
            List<?> t = (List<?>) v;
            max = Math.max(max, ((Number) t.get(1)).longValue());
            overHalf += ((Number) t.get(2)).longValue();
            over += ((Number) t.get(3)).longValue();
        }
        return String.format(Locale.ROOT, "; own turns max %.2f ms, %d over half the idle constant, %d over it",
                max / 1000.0, overHalf, over);
    }

    /** One line per battle in the job's {@code dispatch.tsv}: who ran it, when, how long, and how it ended. */
    private void record(Link link, InFlight f, String outcome, long battleMillis) throws IOException {
        Path file = f.work.job().dir().resolve("dispatch.tsv");
        long now = System.currentTimeMillis();
        String header = Files.exists(file) ? ""
                : "finished\tworker\tslot\tbattle\twaitedSeconds\tbattleSeconds\toutcome\n";
        long waited = (f.startedAt > 0 ? f.startedAt : now) - f.sentAt;
        String line = String.join("\t", TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(now),
                        ZoneId.systemDefault())), link.name(), Integer.toString(f.slot),
                f.work.key().replace('\t', ' '), String.format(Locale.ROOT, "%.1f", waited / 1000.0),
                battleMillis < 0 ? "" : String.format(Locale.ROOT, "%.1f", battleMillis / 1000.0),
                outcome.replace('\t', ' ').replace('\n', ' '));
        Files.writeString(file, header + line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /** For the summaries: the workers that have run battles in this session. */
    private synchronized Map<String, String> environment() {
        Map<String, String> env = new TreeMap<>();
        for (Link link : links) {
            if (!link.info.isEmpty()) {
                env.put("worker " + link.name(), String.format(Locale.ROOT,
                        "%s, %s, Robocode %s, CPU constant %s ns (idle %s × %s), JVM %s, sandbox %s",
                        link.info.get("java"), link.info.get("os"), link.info.get("robocode"),
                        link.info.get("cpuConstant"), link.info.get("cpuConstantIdle"), link.info.get("cpuFactor"),
                        link.info.get("jvm"), link.info.get("sandbox")));
            }
        }
        return env;
    }

    synchronized void log(String text) {
        String line = TIME.format(LocalDateTime.now()) + "  " + text;
        System.out.println(line);
        logFile.println(line);
    }

    // ---------------------------------------------------------------------------------------- dashboard access

    synchronized Map<String, Object> state() {
        long now = System.currentTimeMillis();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("now", now);
        m.put("startedAt", startedAt);
        m.put("stateDir", stateDir.toString());
        List<Object> workers = new ArrayList<>();
        for (Link link : links) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("name", link.name());
            w.put("local", link.config.local());
            w.put("foreign", link.config.foreign());
            w.put("slots", link.config.slots());
            w.put("state", link.state);
            w.put("detail", link.detail);
            w.put("since", link.since);
            w.put("busy", link.inFlight.size());
            w.put("battles", link.battles);
            double rate = link.completions.size() < 2 ? Double.NaN : (link.completions.size() - 1) * 1000.0
                    / Math.max(1, link.completions.peekLast() - link.completions.peekFirst());
            w.put("battlesPerSecond", Double.isFinite(rate) && now - link.completions.peekLast() < 30_000 ? rate
                    : null);
            w.put("failures", link.failures);
            w.put("restarts", link.restarts);
            w.put("lastError", link.lastError);
            w.put("info", link.info);
            List<Object> running = new ArrayList<>();
            for (InFlight f : link.inFlight.values()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("job", f.work.job().id());
                r.put("battle", f.work.key().replace('\t', ' '));
                r.put("slot", f.slot);
                r.put("seconds", (now - (f.startedAt > 0 ? f.startedAt : f.sentAt)) / 1000.0);
                running.add(r);
            }
            w.put("running", running);
            workers.add(w);
        }
        m.put("workers", workers);
        List<QueueJob> active = activeJobs();
        List<QueueJob> done = jobs.values().stream().filter(j -> !j.state().active())
                .sorted(Comparator.comparingLong(QueueJob::finishedAt).reversed()).toList();
        List<Object> rows = new ArrayList<>();
        for (QueueJob j : active) {
            Map<String, Object> row = j.row();
            row.put("inFlight", j.inFlight());
            rows.add(row);
        }
        done.forEach(j -> rows.add(j.row()));
        m.put("jobs", rows);
        return m;
    }

    synchronized Object job(String id) throws IOException {
        QueueJob job = jobs.get(id);
        if (job == null) {
            return null;
        }
        Map<String, Object> m = job.row();
        m.put("inFlight", job.inFlight());
        m.put("dir", job.dir().toString());
        m.put("detail", job.detail());
        Path dispatch = job.dir().resolve("dispatch.tsv");
        if (Files.exists(dispatch)) {
            List<String> lines = Files.readAllLines(dispatch);
            m.put("dispatch", lines.subList(Math.max(1, lines.size() - 50), lines.size()));
        }
        Path problems = job.dir().resolve("problems.log");
        if (Files.exists(problems)) {
            List<String> lines = Files.readAllLines(problems);
            m.put("problems", lines.subList(Math.max(0, lines.size() - 50), lines.size()));
        }
        return m;
    }

    /** Dashboard actions: {@code cancel}, {@code up}, {@code down}. */
    synchronized String act(String action, String id) throws IOException {
        QueueJob job = jobs.get(id);
        if (job == null) {
            return "no job " + id;
        }
        switch (action) {
            case "cancel" -> {
                job.cancel();
                log("job " + id + " cancelled");
                return "cancelled";
            }
            case "up", "down" -> {
                List<QueueJob> active = activeJobs();
                int i = active.indexOf(job);
                int j = action.equals("up") ? i - 1 : i + 1;
                if (i < 0 || j < 0 || j >= active.size()) {
                    return "no move";
                }
                QueueJob other = active.get(j);
                double a = job.order();
                double b = other.order();
                if (a == b) {
                    b = a + (action.equals("up") ? -1 : 1);
                }
                job.setOrder(b);
                other.setOrder(a);
                log("job " + id + " moved " + action);
                return "moved";
            }
            default -> {
                return "unknown action " + action;
            }
        }
    }
}
