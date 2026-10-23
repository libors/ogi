package cz.libors.harness.queue;

import cz.libors.harness.foreign.JarCheck;
import cz.libors.harness.queue.Protocol.Channel;
import cz.libors.harness.queue.Protocol.Message;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The worker side of the queue: runs battles in slots, each a JVM of its own ({@link Slot}) with a Robocode home of
 * its own, and keeps no job state between runs (only the CPU constant it measured). The server starts it (over ssh,
 * or as a local process) and talks to it through standard input and output ({@link Protocol}); everything about jobs
 * stays with the server.
 *
 * <p>{@code serve}: prepares the slots and runs the battles the server sends, at most one per slot. It ends, with its
 * slots, at the end of input, on {@code shutdown}, or when the server has been silent for {@code idleTimeout} seconds
 * (the server pings): a dropped connection must not leave battles running on the worker, and Windows does not end a
 * process when its ssh session ends.
 *
 * <p>{@code run}: runs one harness main class in a slot's environment, in a fresh home; for the remote checks.
 *
 * <p>Every slot JVM gets the options given as {@code jvm=}, Robocode's CPU constant measured on this machine under
 * those options (the median of five measurements, kept in {@code <root>/state}) times {@code cpuFactor}, and a
 * minimal environment, because robots may read environment variables. Foreign jars are copied in only if
 * {@code <foreign>/approved.txt} lists them with their SHA-256 and the jar check does not deny them.
 *
 * <p>Usage: {@code Executor serve|run bundle=<dir> root=<dir> java=<java executable> [jvm=<option>]...
 * [cpuFactor=1.5] [foreign=<dir>] [slots=n] [idleTimeout=90] [remeasure=true] [home=<dir> [runJvm=<option>]...
 * -- <main class> <argument>...]}
 */
public final class Executor {

    private static final int MEASUREMENTS = 5;

    /** A measurement JVM that has not reported by then is ended: a hung one would hold the executor for good. */

    private static final long MEASURE_TIMEOUT_SECONDS = 300;
    /**
     * Keeps the JVM's own output off standard output, which carries the protocol: a warning (a full code cache, say)
     * or a log line in the middle of a result would make the line unreadable and lose the battle. Given after the
     * slots' own options, since a later {@code -Xlog} would turn output to standard output on again; so the slots and
     * the executor log only the JVM's warnings, to standard error. Not part of the battle cache's key: they change no
     * battle.
     */
    static final List<String> PROTOCOL_JVM = List.of("-Xlog:disable", "-Xlog:all=warning:stderr",
            "-XX:+DisplayVMOutputToStderr");
    private static final long SLOT_START_MILLIS = 180_000;
    private static final int MAX_RESTARTS_PER_SLOT = 10;
    private static final List<String> UNIX_ENVIRONMENT = List.of("PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ");
    /** What a slot's home takes from the installation; the libraries stay there, on the class path. */
    private static final List<String> HOME_ENTRIES = List.of("robots", "battles", "config", "templates",
            "versions.md");

    private final Path bundle;
    private final Path root;
    private final String java;
    private final List<String> jvm;
    private final double cpuFactor;
    private final Path foreign;
    private final boolean windows = System.getProperty("os.name").startsWith("Windows");
    private final Channel server;

    private Executor(Options o, Channel server) {
        this.bundle = Path.of(o.get("bundle")).toAbsolutePath();
        this.root = Path.of(o.get("root")).toAbsolutePath();
        this.java = o.get("java");
        this.jvm = List.copyOf(o.all("jvm"));
        this.cpuFactor = Double.parseDouble(o.get("cpuFactor", "1.5"));
        this.foreign = o.has("foreign") ? Path.of(o.get("foreign")).toAbsolutePath() : null;
        this.server = server;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !List.of("serve", "run").contains(args[0])) {
            System.err.println("Usage: Executor serve|run bundle=<dir> root=<dir> java=<java> ...");
            System.exit(2);
        }
        Options o = Options.parse(args, 1);
        if (args[0].equals("serve")) {
            new Executor(o, Protocol.takeStandardOutput()).serve(o);
        } else {
            System.exit(new Executor(o, null).run(o));
        }
    }

    // ---------------------------------------------------------------------------------------------------- serve

    private sealed interface Event permits ServerLine, ServerEnd, SlotLine, SlotExit {
    }

    private record ServerLine(Message message) implements Event {
    }

    private record ServerEnd() implements Event {
    }

    private record SlotLine(int slot, int generation, Message message) implements Event {
    }

    private record SlotExit(int slot, int generation, int code) implements Event {
    }

    /** A battle from the server waiting for a slot; {@code debug}: it needs a slot in the engine's debug mode. */
    private record Battle(long id, Map<String, Object> spec, long timeoutMillis, boolean debug) {
    }

    private final class SlotProcess {
        final int index;
        final Path home;
        final Path tmp;
        final Path log;
        Process process;
        Channel input;
        int generation;
        boolean ready;
        Battle battle;
        long battleStart;
        int restarts;
        /** It said it ends after its battle's result ({@code leaving}). */
        boolean leaving;
        /** When its JVM was started; one that does not say ready within {@link #SLOT_START_MILLIS} is restarted. */
        long startedAt;
        /**
         * Whether the slot's JVM runs the engine in debug mode ({@code -Ddebug=true}), which the engine reads once per
         * JVM: no turn time limit, so no robot skips a turn (engine-model G-17).
         */
        boolean debug;
        /** The last lines the JVM printed outside the protocol, e.g. why it could not start. */
        final Deque<String> output = new ArrayDeque<>();

        SlotProcess(int index) {
            this.index = index;
            this.home = root.resolve("run").resolve("slot-" + index);
            this.tmp = root.resolve("tmp").resolve("slot-" + index);
            this.log = root.resolve("run").resolve("slot-" + index + ".log");
        }
    }

    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final Deque<Battle> waiting = new ArrayDeque<>();
    private final List<SlotProcess> slots = new ArrayList<>();
    private final List<Path> deployed = new ArrayList<>();
    private volatile long lastServerMessage = System.currentTimeMillis();
    private Path base;
    private List<Path> jars;
    private long cpuConstant;
    /** The idle machine's constant, which the slots compare robots' own turn times with. */
    private long idleConstant;

    private void serve(Options o) throws Exception {
        int count = Integer.parseInt(o.get("slots"));
        long idleTimeout = Long.parseLong(o.get("idleTimeout", "90")) * 1000;
        server.send(Message.of("hello", "pid", ProcessHandle.current().pid(),
                "os", System.getProperty("os.name") + " " + System.getProperty("os.arch")));
        Thread reader = new Thread(() -> readServer(System.in), "server-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            base = robocodeBase();
            List<String> rejected = new ArrayList<>();
            jars = robotJars(rejected);
            rejected.forEach(r -> log("foreign jar left out: " + r));
            Measurement m = cpuConstant(Boolean.parseBoolean(o.get("remeasure", "false")));
            cpuConstant = Math.round(m.idle() * cpuFactor);
            idleConstant = m.idle();
            for (int i = 0; i < count; i++) {
                SlotProcess slot = new SlotProcess(i);
                slots.add(slot);
                start(slot);
            }
            Message first = awaitSlots();
            server.send(Message.of("ready", "slots", count, "cpuConstant", cpuConstant, "cpuConstantIdle", m.idle(),
                    "cpuSamples", m.samples(), "cpuFactor", cpuFactor, "jvm", jvm,
                    "java", first.string("java"), "robocode", first.string("robocode"), "os", first.string("os"),
                    "sandbox", first.bool("sandbox"), "robots", first.list("robots"), "robotJars",
                    first.map("robotJars"), "rejected", rejected));
            // The server pings only once it hears "ready"; a start longer than the timeout is no silence.
            lastServerMessage = System.currentTimeMillis();
            loop(idleTimeout);
        } catch (Exception e) {
            server.send(Message.of("fatal", "message", e.toString()));
            e.printStackTrace();
        } finally {
            stopSlots();
            System.exit(0);
        }
    }

    private void readServer(InputStream in) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                lastServerMessage = System.currentTimeMillis();
                Protocol.parse(line).ifPresent(m -> events.add(new ServerLine(m)));
            }
        } catch (IOException e) {
            // the connection is gone
        }
        events.add(new ServerEnd());
    }

    /** Waits until every slot reports ready; returns the first report. */
    private Message awaitSlots() throws InterruptedException, IOException {
        Message first = null;
        long deadline = System.currentTimeMillis() + SLOT_START_MILLIS;
        while (slots.stream().anyMatch(s -> !s.ready)) {
            Event e = events.poll(1, TimeUnit.SECONDS);
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("slots did not start in time; see " + root.resolve("run"));
            }
            if (e instanceof SlotLine l && l.message().type().equals("ready") && current(l.slot(), l.generation())) {
                slots.get(l.slot()).ready = true;
                first = first == null ? l.message() : first;
            } else if (e instanceof SlotExit x && current(x.slot(), x.generation())) {
                throw new IOException("slot " + x.slot() + " ended with " + x.code() + " while starting: "
                        + output(slots.get(x.slot())));
            } else if (e instanceof ServerEnd) {
                throw new IOException("the server went away");
            } else if (e instanceof ServerLine l && l.message().type().equals("ping")) {
                server.send(Message.of("pong", "busy", 0));
            } else if (e instanceof ServerLine l && l.message().type().equals("shutdown")) {
                throw new IOException("shut down while starting");
            }
        }
        return first;
    }

    private void loop(long idleTimeout) throws InterruptedException, IOException {
        while (true) {
            // Every event already queued first: a result that came in time is not lost to the timeouts below.
            for (Event e = events.poll(1, TimeUnit.SECONDS); e != null; e = events.poll()) {
                if (!handle(e)) {
                    return;
                }
            }
            long now = System.currentTimeMillis();
            for (SlotProcess slot : slots) {
                if (slot.battle != null && now - slot.battleStart > slot.battle.timeoutMillis()) {
                    failCurrent(slot, "timeout after " + slot.battle.timeoutMillis() / 1000 + " s");
                    restart(slot, "timeout");
                } else if (!slot.ready && !slot.leaving && slot.battle == null && slot.process != null
                        && now - slot.startedAt > SLOT_START_MILLIS) {
                    restart(slot, "did not start within " + SLOT_START_MILLIS / 1000 + " s");
                }
            }
            if (now - lastServerMessage > idleTimeout) {
                log("no word from the server for " + idleTimeout / 1000 + " s; stopping");
                return;
            }
        }
    }

    /** One event of the loop; false when the executor should stop. */
    private boolean handle(Event e) throws IOException {
        if (e instanceof ServerEnd) {
            return false;
        }
        if (e instanceof ServerLine l) {
            Message m = l.message();
            switch (m.type()) {
                case "battle" -> {
                    Map<String, Object> spec = m.map("spec");
                    waiting.add(new Battle(m.longValue("id"), spec, m.longValue("timeout") * 1000,
                            BattleSpec.fromMap(spec).debug()));
                    dispatch();
                }
                case "deploy" -> deploy(m.string("file"), m.string("sha"));
                case "ping" -> server.send(Message.of("pong", "busy",
                        slots.stream().filter(s -> s.battle != null).count(), "waiting", waiting.size()));
                case "shutdown" -> {
                    return false;
                }
                default -> log("unknown message " + m.type());
            }
        } else if (e instanceof SlotLine l && current(l.slot(), l.generation())) {
            slotMessage(slots.get(l.slot()), l.message());
        } else if (e instanceof SlotExit x && current(x.slot(), x.generation())) {
            SlotProcess slot = slots.get(x.slot());
            String reason = "slot " + slot.index + " ended with " + x.code() + ": " + output(slot);
            failCurrent(slot, reason);
            restart(slot, reason);
        }
        return true;
    }

    private boolean current(int slot, int generation) {
        return slot < slots.size() && slots.get(slot).generation == generation;
    }

    private void slotMessage(SlotProcess slot, Message m) {
        switch (m.type()) {
            case "ready" -> {
                slot.ready = true;
                dispatch();
            }
            case "result" -> {
                if (slot.battle != null && slot.battle.id() == m.longValue("id")) {
                    server.send(Message.of("result", "id", slot.battle.id(), "slot", slot.index,
                            "outcome", m.map("outcome")));
                    slot.battle = null;
                }
                dispatch();
            }
            case "error" -> {
                failCurrent(slot, m.string("message"));
                dispatch();
            }
            case "leaving" -> {
                // Its battle's result follows, then the slot ends (Slot, exit code 4); no new battle meanwhile.
                slot.ready = false;
                slot.leaving = true;
            }
            default -> log("slot " + slot.index + ": unknown message " + m.type());
        }
    }

    private void failCurrent(SlotProcess slot, String reason) {
        if (slot.battle != null) {
            server.send(Message.of("failed", "id", slot.battle.id(), "slot", slot.index, "reason", reason));
            slot.battle = null;
        }
    }

    /**
     * Gives waiting battles, in order, to idle slots in the debug mode they need. When only slots in the other mode
     * are idle, one of them restarts in the needed mode; a job's battles all need the same mode, so each slot
     * switches once when such a job starts.
     */
    private void dispatch() {
        while (!waiting.isEmpty()) {
            Battle b = waiting.peek();
            SlotProcess slot = idle(b.debug());
            if (slot == null) {
                SlotProcess other = idle(!b.debug());
                if (other != null) {
                    other.debug = b.debug();
                    try {
                        switchMode(other);
                    } catch (IOException e) {
                        log("slot " + other.index + " could not switch its debug mode: " + e);
                    }
                }
                return;
            }
            waiting.poll();
            slot.battle = b;
            slot.battleStart = System.currentTimeMillis();
            slot.input.send(Message.of("battle", "id", b.id(), "spec", b.spec()));
            server.send(Message.of("started", "id", b.id(), "slot", slot.index));
        }
    }

    private SlotProcess idle(boolean debug) {
        for (SlotProcess slot : slots) {
            if (slot.ready && slot.battle == null && slot.debug == debug) {
                return slot;
            }
        }
        return null;
    }

    /** Restarts an idle slot in its new debug mode; not a failure, so it does not count as a restart. */
    private void switchMode(SlotProcess slot) throws IOException {
        slot.ready = false;
        slot.process.destroyForcibly();
        try {
            slot.process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        start(slot);
    }

    private void deploy(String file, String sha) {
        try {
            if (file.contains("/") || file.contains("\\") || !file.endsWith(".jar")) {
                throw new IOException("not a jar name: " + file);
            }
            Path jar = root.resolve("jars").resolve(file);
            String actual = sha256(jar);
            if (!actual.equals(sha)) {
                throw new IOException(file + " has SHA-256 " + actual + ", not " + sha);
            }
            for (SlotProcess slot : slots) {
                // The engine rescans its robots directory before every battle (BattleRunner.repository). A jar that
                // is there already (from the bundle) stays: Windows does not let us replace a jar the engine has open.
                // A new one is copied under another name first and then moved, so a scan never reads half a jar.
                Path target = slot.home.resolve("robots").resolve(file);
                if (!Files.exists(target)) {
                    Path part = target.resolveSibling(file + ".part");
                    Files.copy(jar, part, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
                } else if (!sha256(target).equals(sha)) {
                    throw new IOException(file + " is in slot " + slot.index + " with other content");
                }
            }
            // Only now: a slot started later copies every deployed jar, and one that failed here must not be among them.
            if (!deployed.contains(jar)) {
                deployed.add(jar);
            }
            server.send(Message.of("deployed", "file", file));
        } catch (IOException | RuntimeException e) {
            server.send(Message.of("deployed", "file", file, "error", e.toString()));
        }
    }

    private void restart(SlotProcess slot, String reason) throws IOException {
        slot.ready = false;
        // Windows keeps a running process's files open: its home can be replaced only once it is gone.
        slot.process.destroyForcibly();
        try {
            slot.process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // A slot that left on its own after a battle (a robot's threads outlived it) is healthy: not counted. One
        // that keeps failing ends the executor, so that the server sees it and starts a fresh one later, instead of
        // a slot that quietly plays no more.
        if (!slot.leaving && ++slot.restarts > MAX_RESTARTS_PER_SLOT) {
            throw new IOException("slot " + slot.index + " restarted more than " + MAX_RESTARTS_PER_SLOT
                    + " times; the last: " + reason);
        }
        server.send(Message.of("restart", "slot", slot.index, "reason", reason));
        start(slot);
    }

    private void start(SlotProcess slot) throws IOException {
        List<Path> all = new ArrayList<>(jars);
        all.addAll(deployed);
        prepareHome(slot.home, all, cpuConstant);
        Files.createDirectories(slot.tmp);
        int generation = ++slot.generation;
        slot.leaving = false;
        slot.startedAt = System.currentTimeMillis();
        List<String> command = slotCommand(slot.tmp, slot.debug ? List.of("-Ddebug=true") : List.of(),
                Slot.class.getName(), List.of(slot.home.toString()));
        ProcessBuilder pb = new ProcessBuilder(command).directory(slot.home.toFile())
                .redirectError(Redirect.appendTo(slot.log.toFile()));
        minimalEnvironment(pb, slot.tmp);
        Process p = pb.start();
        slot.process = p;
        slot.input = new Channel(p.getOutputStream());
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),
                    StandardCharsets.US_ASCII))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Optional<Message> m;
                    try {
                        m = Protocol.parse(line);
                    } catch (RuntimeException e) {
                        // A line the slot did not write whole (e.g. a JVM warning inside it): keep reading.
                        m = Optional.empty();
                    }
                    if (m.isPresent()) {
                        events.add(new SlotLine(slot.index, generation, m.get()));
                    } else {
                        synchronized (slot.output) {
                            slot.output.add(line);
                            if (slot.output.size() > 5) {
                                slot.output.poll();
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                // the slot is gone; its exit is reported below
            }
            try {
                events.add(new SlotExit(slot.index, generation, p.waitFor()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "slot-" + slot.index);
        t.setDaemon(true);
        t.start();
    }

    private void stopSlots() {
        for (SlotProcess slot : slots) {
            if (slot.process != null && slot.process.isAlive()) {
                try {
                    slot.input.send(Message.of("quit"));
                    slot.input.close();
                } catch (RuntimeException e) {
                    // already gone
                }
            }
        }
        long deadline = System.currentTimeMillis() + 3000;
        for (SlotProcess slot : slots) {
            if (slot.process == null) {
                continue;
            }
            try {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0 || !slot.process.waitFor(left, TimeUnit.MILLISECONDS)) {
                    slot.process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                slot.process.destroyForcibly();
            }
        }
    }

    private void log(String text) {
        if (server != null) {
            server.send(Message.of("log", "text", text));
        } else {
            System.err.println(text);
        }
    }

    // ------------------------------------------------------------------------------------------------------ run

    private int run(Options o) throws IOException, InterruptedException {
        if (o.rest().isEmpty()) {
            throw new IllegalArgumentException("run needs -- <main class> <argument>...");
        }
        base = robocodeBase();
        List<String> rejected = new ArrayList<>();
        jars = robotJars(rejected);
        rejected.forEach(r -> System.err.println("foreign jar left out: " + r));
        Measurement m = cpuConstant(Boolean.parseBoolean(o.get("remeasure", "false")));
        long constant = Math.round(m.idle() * cpuFactor);
        idleConstant = m.idle();
        System.err.printf(Locale.ROOT, "CPU constant %d ns (measured %d x %.2f; samples %s)%n", constant, m.idle(),
                cpuFactor, m.samples());
        Path home = Path.of(o.get("home")).toAbsolutePath();
        Path tmp = root.resolve("tmp").resolve(home.getFileName().toString());
        prepareHome(home, jars, constant);
        Files.createDirectories(tmp);
        List<String> rest = o.rest();
        List<String> command = slotCommand(tmp, o.all("runJvm"), rest.get(0), rest.subList(1, rest.size()));
        ProcessBuilder pb = new ProcessBuilder(command).directory(home.toFile())
                .redirectOutput(Redirect.INHERIT).redirectError(Redirect.INHERIT);
        minimalEnvironment(pb, tmp);
        Process process = pb.start();
        // Remote keeps the session's input open while it waits. Its end means the task on the other machine is
        // gone (interrupted, say): Windows does not end a process when its ssh session drops, so the run ends here,
        // or it would compete with the queue's slots for the cores.
        Thread session = new Thread(() -> {
            try {
                while (System.in.read() >= 0) {
                    // nothing comes; only the end matters
                }
            } catch (IOException e) {
                // the session is gone
            }
            process.destroyForcibly();
        }, "session");
        session.setDaemon(true);
        session.start();
        return process.waitFor();
    }

    // ------------------------------------------------------------------------------------------------- slot JVM

    private List<String> slotCommand(Path tmp, List<String> extra, String main, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(java);
        if (jvm.stream().noneMatch(s -> s.startsWith("-Xmx"))) {
            command.add("-Xmx1g");
        }
        command.addAll(jvm);
        command.addAll(extra);
        // No -XX:+IgnoreUnrecognizedVMOptions: a mistyped option must stop the slot, not be measured as if it held.
        command.addAll(PROTOCOL_JVM);
        command.addAll(List.of(
                "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED",
                "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                "-Duser.home=" + tmp,
                "-Djava.io.tmpdir=" + tmp,
                "-D" + cz.libors.harness.BattleRunner.IDLE_CONSTANT_PROPERTY + "=" + idleConstant,
                "-cp", bundle.resolve("lib").resolve("harness.jar") + File.pathSeparator
                        + base.resolve("libs") + File.separator + "*",
                main));
        command.addAll(args);
        return command;
    }

    /**
     * Robots may read environment variables, so the JVM sees only what it needs: on Windows the system directory and
     * a temporary directory of its own, elsewhere what the Gradle tasks pass (harness/build.gradle.kts).
     */
    private void minimalEnvironment(ProcessBuilder pb, Path tmp) {
        Map<String, String> env = pb.environment();
        Map<String, String> mine = System.getenv();
        env.clear();
        if (windows) {
            String systemRoot = mine.getOrDefault("SystemRoot", "C:\\Windows");
            env.put("SystemRoot", systemRoot);
            env.put("TEMP", tmp.toString());
            env.put("TMP", tmp.toString());
        } else {
            for (String name : UNIX_ENVIRONMENT) {
                if (mine.containsKey(name)) {
                    env.put(name, mine.get(name));
                }
            }
        }
    }

    // --------------------------------------------------------------------------------------- Robocode and jars

    /** The Robocode installation unpacked from the bundle's setup jar, once per setup jar. */
    private Path robocodeBase() throws IOException {
        Path setup;
        try (Stream<Path> files = Files.list(bundle.resolve("worker"))) {
            setup = files.filter(p -> p.getFileName().toString().matches("robocode-.*-setup\\.jar")).findFirst()
                    .orElseThrow(() -> new IOException("no Robocode setup jar in " + bundle.resolve("worker")));
        }
        Path dir = root.resolve("robocode").resolve(sha256(setup).substring(0, 12));
        if (Files.exists(dir.resolve(".complete"))) {
            return dir;
        }
        deleteRecursively(dir);
        Files.createDirectories(dir);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(setup))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                // The installer's own classes, as installRobocode leaves them out.
                if (name.startsWith("META-INF/") || name.startsWith("net/")) {
                    continue;
                }
                Path target = dir.resolve(name).normalize();
                if (!target.startsWith(dir)) {
                    throw new IOException("entry outside the installation: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.writeString(dir.resolve(".complete"), Instant.now() + "\n");
        return dir;
    }

    /** Our jars from the bundle, then the approved foreign jars; {@code rejected} gets the ones left out. */
    private List<Path> robotJars(List<String> rejected) throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(bundle.resolve("robots"))) {
            files.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().forEach(result::add);
        }
        if (foreign == null) {
            return result;
        }
        Path list = foreign.resolve("approved.txt");
        if (!Files.exists(list)) {
            return result;
        }
        for (String raw : approvedLines(Files.readAllBytes(list))) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\s+");
            Path jar = foreign.resolve(fields[0]);
            if (fields.length < 2 || fields[0].contains("/") || fields[0].contains("\\") || !Files.exists(jar)) {
                rejected.add(fields[0] + ": missing or malformed entry");
            } else {
                try {
                    if (!sha256(jar).equalsIgnoreCase(fields[1])) {
                        rejected.add(fields[0] + ": SHA-256 differs from approved.txt");
                    } else if (JarCheck.check(jar).verdict() == JarCheck.Verdict.DENIED) {
                        rejected.add(fields[0] + ": denied by the jar check");
                    } else {
                        result.add(jar);
                    }
                } catch (IOException | RuntimeException e) {
                    // One jar that cannot be read must not stop the worker: it is left out like a denied one.
                    rejected.add(fields[0] + ": jar check failed: " + e);
                }
            }
        }
        return result;
    }

    /**
     * The lines of {@code approved.txt}, which an editor on Windows may have saved as UTF-16 (PowerShell 5.1's
     * {@code >} and {@code Out-File}), with a byte order mark, or in the ANSI code page: the jar names and hashes are
     * ASCII in all of them, and a note's other characters do not matter.
     */
    static List<String> approvedLines(byte[] bytes) {
        java.nio.charset.Charset charset = StandardCharsets.UTF_8;
        int skip = 0;
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xFF && (bytes[1] & 0xff) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xFE && (bytes[1] & 0xff) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            skip = 2;
        } else if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB
                && (bytes[2] & 0xff) == 0xBF) {
            skip = 3;
        }
        // new String replaces what does not decode instead of throwing, as readAllLines would.
        return new String(bytes, skip, bytes.length - skip, charset).lines().toList();
    }

    /**
     * A fresh Robocode home: the installation's robots and configuration, the given jars and, if given, the CPU
     * constant. The libraries stay in the installation; the class path points there.
     */
    private void prepareHome(Path home, List<Path> robotJars, Long constant) throws IOException {
        deleteRecursively(home);
        Files.createDirectories(home);
        for (String name : HOME_ENTRIES) {
            Path entry = base.resolve(name);
            if (Files.isDirectory(entry)) {
                copyRecursively(entry, home.resolve(name));
            } else if (Files.exists(entry)) {
                Files.copy(entry, home.resolve(name));
            }
        }
        Files.deleteIfExists(home.resolve("robots").resolve("robot.database"));
        Path robots = home.resolve("robots");
        Files.createDirectories(robots);
        for (Path jar : robotJars) {
            Files.copy(jar, robots.resolve(jar.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.createDirectories(home.resolve("config"));
        Path config = home.resolve("config").resolve("robocode.properties");
        Files.deleteIfExists(config);
        if (constant != null) {
            // The engine reads the constant only from this file, never from a system property.
            Files.writeString(config, "robocode.cpu.constant=" + constant + "\n");
        }
    }

    // --------------------------------------------------------------------------------------------- CPU constant

    private record Measurement(long idle, List<Long> samples) {
    }

    /**
     * The CPU constant of an idle machine under the slots' JVM options: the median of five engine measurements, each
     * in a fresh JVM, kept in {@code <root>/state/cpu-constant.properties} per Java, JVM options and Robocode.
     */
    private Measurement cpuConstant(boolean remeasure) throws IOException {
        String key = sha256(java + "|" + String.join(" ", jvm) + "|" + base.getFileName()).substring(0, 12);
        Path file = root.resolve("state").resolve("cpu-constant.properties");
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
        }
        if (!remeasure && p.containsKey(key + ".idle")) {
            List<Long> samples = Arrays.stream(p.getProperty(key + ".samples", "").split(","))
                    .filter(s -> !s.isBlank()).map(Long::parseLong).toList();
            return new Measurement(Long.parseLong(p.getProperty(key + ".idle")), samples);
        }
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < MEASUREMENTS; i++) {
            status("measuring the CPU constant " + (i + 1) + "/" + MEASUREMENTS);
            samples.add(measureOnce());
        }
        List<Long> sorted = samples.stream().sorted().toList();
        long median = sorted.get(sorted.size() / 2);
        p.setProperty(key + ".idle", Long.toString(median));
        p.setProperty(key + ".samples", String.join(",", samples.stream().map(String::valueOf).toList()));
        p.setProperty(key + ".java", java);
        p.setProperty(key + ".jvm", String.join(" ", jvm));
        p.setProperty(key + ".measured", Instant.now().toString());
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "Robocode CPU constants measured on this machine, in ns (queue.Executor)");
        }
        return new Measurement(median, samples);
    }

    private long measureOnce() throws IOException {
        Path home = root.resolve("run").resolve("measure");
        Path tmp = root.resolve("tmp").resolve("measure");
        prepareHome(home, List.of(), null);
        Files.createDirectories(tmp);
        List<String> command = slotCommand(tmp, List.of(), Slot.class.getName(), List.of(home.toString()));
        ProcessBuilder pb = new ProcessBuilder(command).directory(home.toFile())
                .redirectError(Redirect.appendTo(root.resolve("run").resolve("measure.log").toFile()));
        minimalEnvironment(pb, tmp);
        Process process = pb.start();
        Thread watchdog = new Thread(() -> {
            try {
                if (!process.waitFor(MEASURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                // the measurement ended first
            }
        }, "measure-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        List<String> output = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.US_ASCII))) {
            Channel in = new Channel(process.getOutputStream());
            String line;
            while ((line = r.readLine()) != null) {
                Optional<Message> m = Protocol.parse(line);
                if (m.isEmpty()) {
                    output.add(line);
                    continue;
                }
                if (m.get().type().equals("ready")) {
                    in.send(Message.of("measure"));
                } else if (m.get().type().equals("measured")) {
                    in.send(Message.of("quit"));
                    long value = m.get().longValue("cpuConstant");
                    if (value <= 0) {
                        break;
                    }
                    process.waitFor(10, TimeUnit.SECONDS);
                    return value;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            process.destroyForcibly();
            watchdog.interrupt();
        }
        throw new IOException("the CPU constant measurement failed (or gave no value within "
                + MEASURE_TIMEOUT_SECONDS + " s): " + String.join(" | ", output) + " "
                + tail(root.resolve("run").resolve("measure.log")));
    }

    private void status(String text) {
        if (server != null) {
            server.send(Message.of("status", "text", text));
        } else {
            System.err.println(text);
        }
    }

    // ---------------------------------------------------------------------------------------------------- files

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What a slot printed last, on standard output and standard error. */
    private static String output(SlotProcess slot) {
        synchronized (slot.output) {
            return String.join(" | ", slot.output) + (slot.output.isEmpty() ? "" : " | ") + tail(slot.log);
        }
    }

    /** The last lines of a log file, on one line. */
    private static String tail(Path log) {
        try {
            if (!Files.exists(log)) {
                return "(no log)";
            }
            List<String> lines = Files.readAllLines(log, StandardCharsets.ISO_8859_1);
            return String.join(" | ", lines.subList(Math.max(0, lines.size() - 5), lines.size()));
        } catch (IOException e) {
            return "(log unreadable: " + e + ")";
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static void copyRecursively(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** For the server's log: what an executor reported, by key. */
    static Map<String, Object> describe(Message ready) {
        Map<String, Object> m = new LinkedHashMap<>(ready.fields());
        m.remove("robots");
        return m;
    }
}
