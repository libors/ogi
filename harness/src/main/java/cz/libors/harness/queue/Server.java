package cz.libors.harness.queue;

import cz.libors.harness.compare.CompareJob;
import cz.libors.harness.compare.MeleeJob;
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
 * [slotJvm=queue|rumble] [jvm=<option>]... localJava=<java> [localSecurityManager=true]}.
 */
public final class Server {

    private static final long TICK_MILLIS = 500;
    private static final long SCAN_MILLIS = 2000;
    private static final long PING_MILLIS = 20_000;
    private static final long SILENCE_MILLIS = 120_000;
    private static final long STARTUP_MILLIS = 600_000;
    private static final long IDLE_STOP_MILLIS = 300_000;
    private static final long RATE_WINDOW_MILLIS = 120_000;
    /**
     * How long after a battle's own timeout the server gives up on its result. The executor ends a battle at its
     * timeout and reports it; a result that never comes (a protocol line the JVM's own output broke) would otherwise
     * hold its slot and its job for as long as the connection lives. Longer than a slot's restart may take (a battle
     * sent to a slot that restarts waits for it), so that a battle under way is not given up.
     */
    private static final long RESULT_MARGIN_MILLIS = 300_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path stateDir;
    private final BattleCache battleCache;
    /** Every battle the workers played to its end, over all jobs on disk (the dashboard's totals). */
    private final BattleTotals battleTotals;
    /** The statistics page's numbers, with what it has read of the jobs that ended. */
    private final Statistics statistics = new Statistics();
    private final Path jobsDir;
    private final Path jarsDir;
    private final Path localBundle;
    private final List<Link> links = new ArrayList<>();
    private final Map<String, QueueJob> jobs = new LinkedHashMap<>();
    /** Job directories that cannot be opened, and why: shown on the dashboard, which cannot list them as jobs. */
    private final Map<String, String> unreadable = new LinkedHashMap<>();
    /** Jobs active when last looked at, to log each one's end once ({@link #announceEnds}). */
    private final Set<String> activeSeen = new HashSet<>();
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
        long lastErrorAt;
        /** What the executor reported before it ended, if it failed; the reason it ended then names it. */
        String fatal;
        /** Why the server dropped the connection, if it did; the reason it ended then names it. */
        String dropped;
        /** When the worker last brought a battle back. */
        long lastOkAt;

        Link(WorkerConfig config) {
            this.config = config;
        }

        String name() {
            return config.name();
        }

        void error(String reason) {
            lastError = reason;
            lastErrorAt = System.currentTimeMillis();
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
        battleTotals = BattleTotals.read(jobsDir);
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

    /**
     * What decides a battle on a worker, for the battle cache. Not the CPU constant: the executor measures it once
     * per worker and JVM options and keeps it (Executor, {@code cpu-constant.properties}); a new measurement does
     * not change the cache's keys (harness.md, "Battle cache").
     */
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
                        WorkerConfig.baseJvm(o.get("slotJvm", "queue")), o.all("jvm"), cpuFactor)));
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
            Error fatal = null;
            synchronized (this) {
                try {
                    tick();
                } catch (IOException | RuntimeException e) {
                    log("tick failed: " + e);
                    e.printStackTrace();
                } catch (Error e) {
                    fatal = e;
                    log("server stopped: " + e);
                    e.printStackTrace();
                }
            }
            if (fatal != null) {
                // The JVM ends, dashboard included, rather than serve a queue that no longer runs. Outside the lock:
                // the shutdown hook takes it.
                System.exit(1);
            }
            Thread.sleep(TICK_MILLIS);
        }
    }

    private void tick() throws IOException {
        announceEnds();
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
                    giveUpOverdue(link, now);
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
            if (jobs.containsKey(id) || unreadable.containsKey(id)
                    || !Files.exists(dir.resolve("status.properties"))) {
                continue;
            }
            if (!id.matches("[\\w-]+")) {
                // The dashboard puts ids into page code; Submit makes them of letters, digits and dashes only.
                unreadable.put(id, "not a job id");
                log("job " + id + " ignored: not a job id");
                continue;
            }
            try {
                QueueJob job = QueueJob.open(dir, slotSettings());
                if (job instanceof CompareJob c) {
                    c.environment(this::environment);
                    c.cache(battleCache);
                } else if (job instanceof MeleeJob m) {
                    m.environment(this::environment);
                    m.cache(battleCache);
                }
                jobs.put(id, job);
                if (job.state().active()) {
                    log("job " + id + " " + job.state().name().toLowerCase(Locale.ROOT) + ": " + job.title());
                }
            } catch (IOException | RuntimeException e) {
                unreadable.put(id, e.toString());
                log("job " + id + " cannot be read: " + e);
            }
        }
    }

    /**
     * The server's settings that decide battles and are no option of a job: per worker, the slots' JVM options and
     * the CPU factor. A comparison's log is keyed by them, so a server started otherwise (as a rumble client, say)
     * does not resume it.
     */
    private Map<String, String> slotSettings() {
        Map<String, String> m = new TreeMap<>();
        for (Link link : links) {
            m.put("slots " + link.name(), "jvm " + String.join(" ", link.config.jvm()) + ", cpuFactor "
                    + link.config.cpuFactor());
        }
        return m;
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

    /**
     * The next battle for a worker: from the first job in queue order that has one. While battles of one debug mode
     * are under way on the worker, jobs of the other mode wait: a slot switches modes by starting a new JVM, and
     * mixing the modes made the slots switch back and forth.
     */
    private Work nextWork(Link link) throws IOException {
        Boolean mode = runningMode(link);
        try {
            for (QueueJob job : activeJobs()) {
                if (!eligible(link, job) || (mode != null && job.debug() != mode)) {
                    continue;
                }
                List<String> missing = job.opponents().stream().filter(o -> !link.robots.contains(o)).toList();
                if (!missing.isEmpty()) {
                    if (links.stream().noneMatch(o -> o != link && eligible(o, job))) {
                        fail(job, "robots missing on " + link.name() + ": " + missing);
                    }
                    continue;
                }
                if (!link.deployed.containsAll(job.jars().keySet())) {
                    deploy(link, job);
                    continue;
                }
                boolean queued = job.state() == State.QUEUED;
                List<Work> works;
                try {
                    works = job.next(link.name());
                } catch (IOException | RuntimeException e) {
                    // One job's trouble ends that job, not the dispatch of all the others.
                    log("job " + job.id() + ": handing out work failed: " + e);
                    fail(job, "handing out work failed: " + e);
                    continue;
                }
                if (queued && job.state() != State.QUEUED) {
                    // It started itself on battles from the cache.
                    log("job " + job.id() + " started on " + link.name());
                }
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
        } finally {
            announceEnds();
        }
    }

    /** The debug mode of the battles under way on a worker, or null if it plays none. */
    private static Boolean runningMode(Link link) {
        for (InFlight f : link.inFlight.values()) {
            return f.work.spec().debug();
        }
        Work w = link.pending.peek();
        return w == null ? null : w.spec().debug();
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
                    // A connection that went away meanwhile copies them again after it is back.
                    if (link.generation == generation) {
                        fail(job, "copying its jars to " + link.name() + " failed: " + e);
                    }
                }
            }
        }, "deploy-" + link.name());
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------------------------------- the executors

    private void connect(Link link) {
        int generation = ++link.generation;
        link.fatal = null;
        link.dropped = null;
        link.state("connecting", link.config.local() ? "starting the executor" : "syncing the bundle");
        Thread t = new Thread(() -> {
            try {
                if (!link.config.local()) {
                    int copied = Ssh.sync(link.name(), localBundle, link.config.bundle());
                    log(link.name() + ": bundle synced, " + copied + " file(s) copied");
                }
                List<String> command = new ArrayList<>();
                List<String> java = new ArrayList<>(Executor.PROTOCOL_JVM);
                java.addAll(List.of("-cp", link.config.bundle() + "/lib/harness.jar", Executor.class.getName()));
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
        String readerFailure = null;
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
        } catch (RuntimeException | Error e) {
            // Not only the connection's end: without the code below the link would stay ready, and the battles in
            // flight on it would never come back (a LinkageError, e.g., when the classes the server runs from were
            // rebuilt meanwhile).
            readerFailure = "the server's reader failed: " + e;
            log(link.name() + ": " + readerFailure);
            e.printStackTrace();
            p.destroyForcibly();
        }
        int exit;
        try {
            exit = p.waitFor();
        } catch (InterruptedException e) {
            exit = -1;
        }
        synchronized (this) {
            if (link.generation == generation) {
                String reason = readerFailure != null ? readerFailure
                        : link.fatal != null ? "the executor failed: " + link.fatal
                        : link.dropped != null ? link.dropped : "the executor ended (exit " + exit + ")";
                link.fatal = null;
                link.dropped = null;
                disconnected(link, reason);
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
                    link.lastOkAt = now;
                    Map<String, Object> outcome = m.map("outcome");
                    QueueJob job = f.work.job();
                    Optional<String> refused = Optional.empty();
                    try {
                        refused = job.completed(f.work, outcome, link.name());
                    } catch (IOException | RuntimeException e) {
                        // The battle would be lost to a job that runs on: it fails instead, and is seen.
                        log("job " + job.id() + ": counting a battle failed: " + e);
                        fail(job, "counting a battle failed: " + e);
                    }
                    if (refused.isPresent()) {
                        // Played, but not the battle asked for: logged as refused, out of the totals.
                        String reason = refused.get();
                        log(link.name() + ": battle refused by job " + job.id() + ": " + reason);
                        guarded(link, "logging a battle", () -> record(link, f, "refused: " + reason, -1));
                    } else {
                        link.battles++;
                        link.completions.add(now);
                        while (!link.completions.isEmpty()
                                && link.completions.peek() < now - RATE_WINDOW_MILLIS) {
                            link.completions.poll();
                        }
                        guarded(link, "logging a battle", () -> record(link, f, "ok" + turnTimes(outcome),
                                ((Number) outcome.get("elapsedMillis")).longValue()));
                    }
                    announceEnds();
                }
                dispatch(link);
            }
            case "failed" -> {
                InFlight f = link.inFlight.remove(m.longValue("id"));
                if (f != null) {
                    battleFailed(link, f, m.string("reason"));
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
                link.error(m.string("message"));
                link.fatal = m.string("message");
                log(link.name() + ": executor failed: " + m.string("message"));
            }
            default -> log(link.name() + ": unknown message " + m.type());
        }
    }

    /** A battle the worker reported failed, or whose result the server gave up on: its job may retry it. */
    private void battleFailed(Link link, InFlight f, String reason) {
        link.failures++;
        link.error(reason);
        log(link.name() + ": battle failed: " + reason);
        QueueJob job = f.work.job();
        try {
            job.failed(f.work, reason, link.name());
        } catch (IOException | RuntimeException e) {
            log("job " + job.id() + ": counting a failed battle failed: " + e);
            fail(job, "counting a failed battle failed: " + e);
        }
        guarded(link, "logging a battle", () -> record(link, f, "failed: " + reason, -1));
        announceEnds();
    }

    /**
     * Gives up on the battles whose result is overdue: {@link #RESULT_MARGIN_MILLIS} past their timeout, counted from
     * when the slot started them (or from when they were sent, if no slot said so). A late result is then ignored.
     */
    private void giveUpOverdue(Link link, long now) {
        for (InFlight f : List.copyOf(link.inFlight.values())) {
            long from = f.startedAt > 0 ? f.startedAt : f.sentAt;
            if (now - from > f.work.timeoutSeconds() * 1000 + RESULT_MARGIN_MILLIS) {
                link.inFlight.remove(f.id);
                battleFailed(link, f, "no result " + (now - from) / 1000 + " s after it started (timeout "
                        + f.work.timeoutSeconds() + " s)");
            }
        }
    }

    /** Ends the connection now; the reader then reports it. */
    private void drop(Link link, String reason) {
        log(link.name() + ": dropping the connection: " + reason);
        link.error(reason);
        link.dropped = reason;
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
        // One by one: a battle whose job cannot take it back must not keep the others on the lost worker.
        for (InFlight f : link.inFlight.values()) {
            touched.add(f.work.job());
            guarded(link, "returning a battle", () -> f.work.job().lost(f.work));
            guarded(link, "logging a lost battle", () -> record(link, f, "lost: " + reason, -1));
        }
        for (Work w : link.pending) {
            touched.add(w.job());
            guarded(link, "returning a battle", () -> w.job().lost(w));
        }
        for (QueueJob job : jobs.values()) {
            guarded(link, "telling job " + job.id(), () -> job.workerLost(link.name()));
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

    private interface Step {
        void run() throws IOException;
    }

    private void guarded(Link link, String what, Step step) {
        try {
            step.run();
        } catch (IOException | RuntimeException e) {
            log(link.name() + ": " + what + " failed: " + e);
        }
    }

    private void backoff(Link link, String reason) {
        link.connectFailures++;
        long delay = Math.min(300_000, 10_000L << Math.min(5, link.connectFailures - 1));
        link.backoffUntil = System.currentTimeMillis() + delay;
        link.error(reason);
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

    /**
     * Logs the end of every job that was active when last seen. A job can end in its own battle's handling, when
     * it hands out work (all its battles came from the cache), or in another job's (it waited for that battle).
     */
    private void announceEnds() {
        for (QueueJob job : jobs.values()) {
            if (job.state().active()) {
                activeSeen.add(job.id());
            } else if (activeSeen.remove(job.id())) {
                log("job " + job.id() + " " + job.state().name().toLowerCase(Locale.ROOT) + ": " + job.reason());
            }
        }
    }

    /** Our robots' own turn times in an outcome, for the dispatch log: the longest, and how many went over. */
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
        String finished = TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()));
        String line = String.join("\t", finished, link.name(), Integer.toString(f.slot),
                f.work.key().replace('\t', ' '), String.format(Locale.ROOT, "%.1f", waited / 1000.0),
                battleMillis < 0 ? "" : String.format(Locale.ROOT, "%.1f", battleMillis / 1000.0),
                outcome.replace('\t', ' ').replace('\n', ' '));
        Files.writeString(file, header + line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        if (outcome.startsWith("ok")) {
            battleTotals.add(f.work.job().dir(), link.name(), Math.max(0, battleMillis) / 1000.0, finished);
        }
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
            w.put("battles", battleTotals.battles(link.name()));
            w.put("battlesThisRun", link.battles);
            double rate = link.completions.size() < 2 ? Double.NaN : (link.completions.size() - 1) * 1000.0
                    / Math.max(1, link.completions.peekLast() - link.completions.peekFirst());
            w.put("battlesPerSecond", Double.isFinite(rate) && now - link.completions.peekLast() < 30_000 ? rate
                    : null);
            w.put("failures", link.failures);
            w.put("restarts", link.restarts);
            w.put("lastError", link.lastError);
            w.put("lastErrorAt", link.lastErrorAt);
            w.put("lastOkAt", link.lastOkAt);
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
        m.put("totals", battleTotals.toMap());
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
        List<Object> broken = new ArrayList<>();
        unreadable.forEach((id, reason) -> broken.add(Map.of("id", id, "reason", reason)));
        m.put("unreadable", broken);
        return m;
    }

    /**
     * For the statistics page. The jobs are taken under the lock; their files (sets, summaries) are read outside it,
     * so that the first call, which reads them all, does not hold up the queue.
     */
    Map<String, Object> statistics() {
        List<Statistics.Job> list;
        synchronized (this) {
            list = jobs.values().stream().map(Statistics.Job::of).toList();
        }
        return statistics.toMap(list, battleTotals);
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
                if (!job.state().active()) {
                    return "already ended";
                }
                job.cancel();
                log("job " + id + " cancelled");
                return "cancelled";
            }
            case "up", "down" -> {
                // Among the jobs of its format, which the dashboard's tab shows: a neighbour on another tab would
                // take the move without a visible change.
                List<QueueJob> active = activeJobs().stream().filter(j -> j.format().equals(job.format())).toList();
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
