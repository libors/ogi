package cz.libors.harness.queue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * ssh and scp to a Windows worker, whose ssh shell is Windows PowerShell 5.1 (build-and-run.md, "Native Windows
 * worker"). There is no rsync on Windows, so a directory is kept in sync by comparing SHA-256 lists and copying what
 * differs with scp.
 */
final class Ssh {

    /** No password prompts; no warnings below errors (OpenSSH 10 warns about the server's key exchange). */
    static final List<String> OPTIONS = List.of("-o", "BatchMode=yes", "-o", "ConnectTimeout=15", "-o",
            "LogLevel=ERROR");

    private Ssh() {
    }

    record Result(int exit, String out, String err) {
    }

    /** A PowerShell string literal. */
    static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** A PowerShell command line that runs a program with these arguments, each passed as it is. */
    static String command(String program, List<String> args) {
        StringBuilder sb = new StringBuilder("& ").append(quote(program));
        for (String a : args) {
            if (a.isEmpty()) {
                throw new IllegalArgumentException("PowerShell drops empty arguments");
            }
            if (a.contains("\"")) {
                // Windows PowerShell 5.1 passes a quote to a native program unescaped, so the argument would split.
                throw new IllegalArgumentException("PowerShell does not pass quotes on: " + a);
            }
            sb.append(' ').append(quote(a));
        }
        return sb.toString();
    }

    /** Runs a PowerShell command on the host and waits for it. */
    static Result run(String host, String command, long timeoutSeconds) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("ssh"));
        cmd.addAll(OPTIONS);
        cmd.add(host);
        cmd.add(command);
        return exec(cmd, timeoutSeconds);
    }

    static Result exec(List<String> cmd, long timeoutSeconds) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectInput(ProcessBuilder.Redirect.from(nullFile())).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread a = drain(p.getInputStream(), out);
        Thread b = drain(p.getErrorStream(), err);
        try {
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("timed out after " + timeoutSeconds + " s: " + String.join(" ", cmd));
            }
            a.join();
            b.join();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted: " + String.join(" ", cmd));
        }
        // PowerShell's console output is CP852 on a Czech Windows; what we parse is ASCII.
        return new Result(p.exitValue(), out.toString(StandardCharsets.ISO_8859_1),
                withoutNoise(err.toString(StandardCharsets.ISO_8859_1)));
    }

    /** Runs a command and fails unless it exits with 0. */
    static String check(String host, String command, long timeoutSeconds) throws IOException {
        Result r = run(host, command, timeoutSeconds);
        if (r.exit() != 0) {
            throw new IOException("ssh " + host + " failed (" + r.exit() + "): " + command + "\n" + r.err() + r.out());
        }
        return r.out();
    }

    /** The OpenSSH client's warning about the server's key exchange, which says nothing about our work. */
    static String withoutNoise(String stderr) {
        StringBuilder sb = new StringBuilder();
        for (String line : stderr.split("\\R")) {
            if (!noise(line) && !line.isBlank()) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    static boolean noise(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        return l.contains("post-quantum") || l.contains("store now, decrypt later") || l.contains("openssh.com/pq");
    }

    /** SHA-256 of every file under a remote directory, by path relative to it with forward slashes. */
    static Map<String, String> remoteHashes(String host, String dir) throws IOException {
        String script = "$r = " + quote(dir) + "; if (Test-Path -LiteralPath $r) { $p = (Resolve-Path -LiteralPath $r)"
                + ".Path.TrimEnd('\\'); Get-ChildItem -LiteralPath $r -Recurse -File | ForEach-Object { "
                + "(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash + ' ' + "
                + "$_.FullName.Substring($p.Length + 1) } }";
        Map<String, String> hashes = new TreeMap<>();
        for (String line : check(host, script, 120).split("\\R")) {
            int space = line.indexOf(' ');
            if (space == 64) {
                hashes.put(line.substring(space + 1).trim().replace('\\', '/'),
                        line.substring(0, space).toLowerCase(Locale.ROOT));
            }
        }
        return hashes;
    }

    static Map<String, String> localHashes(Path dir) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                hashes.put(dir.relativize(p).toString().replace('\\', '/'), Executor.sha256(p));
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return hashes;
    }

    /**
     * Makes the remote directory hold exactly the local directory's files: copies what is missing or different,
     * deletes what the local one does not have. Returns how many files it copied.
     */
    static int sync(String host, Path local, String remote) throws IOException {
        Map<String, String> want = localHashes(local);
        Map<String, String> have = remoteHashes(host, remote);
        List<String> delete = have.keySet().stream().filter(f -> !want.containsKey(f)).toList();
        if (!delete.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String f : delete) {
                sb.append("Remove-Item -Force -LiteralPath ").append(quote(remote + "/" + f)).append("; ");
            }
            check(host, sb.toString(), 120);
        }
        Map<String, List<Path>> byDir = new LinkedHashMap<>();
        want.forEach((f, sha) -> {
            if (!sha.equals(have.get(f))) {
                int slash = f.lastIndexOf('/');
                byDir.computeIfAbsent(slash < 0 ? "" : f.substring(0, slash), k -> new ArrayList<>())
                        .add(local.resolve(f));
            }
        });
        upload(host, byDir, remote);
        return byDir.values().stream().mapToInt(List::size).sum();
    }

    /** Copies files into directories under a remote root, creating the directories first. */
    static void upload(String host, Map<String, List<Path>> byDir, String remote) throws IOException {
        if (byDir.isEmpty()) {
            return;
        }
        StringBuilder mkdir = new StringBuilder("New-Item -ItemType Directory -Force -Path ");
        List<String> dirs = byDir.keySet().stream().map(d -> quote(d.isEmpty() ? remote : remote + "/" + d)).toList();
        mkdir.append(String.join(",", dirs)).append(" | Out-Null");
        check(host, mkdir.toString(), 60);
        for (Map.Entry<String, List<Path>> e : byDir.entrySet()) {
            List<String> cmd = new ArrayList<>(List.of("scp", "-q"));
            cmd.addAll(OPTIONS);
            e.getValue().forEach(p -> cmd.add(p.toString()));
            cmd.add(host + ":" + (e.getKey().isEmpty() ? remote : remote + "/" + e.getKey()) + "/");
            Result r = exec(cmd, 600);
            if (r.exit() != 0) {
                throw new IOException("scp to " + host + " failed: " + r.err());
            }
        }
    }

    /** Copies a remote directory into a local one, as a directory of the same name (for results). */
    static void download(String host, String remote, Path localParent) throws IOException {
        Files.createDirectories(localParent);
        List<String> cmd = new ArrayList<>(List.of("scp", "-q", "-r"));
        cmd.addAll(OPTIONS);
        cmd.add(host + ":" + remote);
        cmd.add(localParent.toString() + "/");
        Result r = exec(cmd, 600);
        if (r.exit() != 0) {
            throw new IOException("scp from " + host + " failed: " + r.err());
        }
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream out) {
        Thread t = new Thread(() -> {
            try {
                in.transferTo(out);
            } catch (IOException e) {
                // the process is gone
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static java.io.File nullFile() {
        return new java.io.File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    }
}
