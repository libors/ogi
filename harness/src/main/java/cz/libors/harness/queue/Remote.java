package cz.libors.harness.queue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one harness main class on a Windows worker, in a slot's environment ({@link Executor}'s {@code run}): syncs the
 * bundle, runs the class over ssh with its output shown here, and copies the run's output directory back. For the
 * {@code remote*} Gradle tasks.
 *
 * <p>The class sees its Robocode home at {@code C:/ogi/run/<name>} and writes to {@code C:/ogi/out/<name>}, which
 * comes back to {@code <results>/<name>}.
 *
 * <p>Usage: {@code Remote host=<ssh host> bundle=<local bundle> results=<local dir> name=<run> [clean=true]
 * [cpuFactor=1.5] [slotJvm=queue|rumble] [jvm=<option>]... [runJvm=<option>]... -- <main class> <argument>...}
 */
public final class Remote {

    private Remote() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Options o = Options.parse(args, 0);
        String host = o.get("host");
        String name = o.get("name");
        WorkerConfig worker = WorkerConfig.parse(host + ":1", Path.of("."), Path.of(o.get("bundle")), "java", false,
                WorkerConfig.baseJvm(o.get("slotJvm", "queue")), o.all("jvm"),
                Double.parseDouble(o.get("cpuFactor", "1.5")));
        int copied = Ssh.sync(host, Path.of(o.get("bundle")), worker.bundle());
        System.out.println("bundle synced to " + host + ", " + copied + " file(s) copied");
        String out = worker.root() + "/out/" + name;
        if (Boolean.parseBoolean(o.get("clean", "false"))) {
            Ssh.check(host, "if (Test-Path -LiteralPath " + Ssh.quote(out) + ") { Remove-Item -Recurse -Force "
                    + "-LiteralPath " + Ssh.quote(out) + " }", 120);
        }
        Ssh.check(host, "New-Item -ItemType Directory -Force -Path " + Ssh.quote(out) + " | Out-Null", 60);

        List<String> java = new ArrayList<>(List.of("-cp", worker.bundle() + "/lib/harness.jar",
                Executor.class.getName()));
        java.addAll(worker.executorArguments("run"));
        java.add("home=" + worker.root() + "/run/" + name);
        o.all("runJvm").forEach(v -> java.add("runJvm=" + v));
        java.add("--");
        java.addAll(o.rest());
        List<String> command = new ArrayList<>(List.of("ssh", "-T"));
        command.addAll(Ssh.OPTIONS);
        command.addAll(List.of(host, Ssh.command(worker.java(), java)));
        int exit = new ProcessBuilder(command).inheritIO().start().waitFor();

        Path results = Path.of(o.get("results")).resolve(name);
        Executor.deleteRecursively(results);
        Ssh.download(host, out, results.getParent());
        System.out.println("results in " + results + "; exit " + exit);
        System.exit(exit);
    }
}
