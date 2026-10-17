import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    java
}

val botVersion = providers.gradleProperty("botVersion").get()
val robocodeVersion = providers.gradleProperty("robocodeVersion").get()
val robocodeSetupSha256 = providers.gradleProperty("robocodeSetupSha256").get()
val toolchainJava = providers.gradleProperty("javaVersion").get().toInt()

// A decompiler for reading foreign robot jars before they run; only `decompileJar` uses it.
val decompiler: Configuration by configurations.creating

// Differential tests of the bot's engine model against the recordings; they need `recordProbes` to run first.
val engineTest: SourceSet = sourceSets.create("engineTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

// The offline gun bench: replays the gun's logs through the bot's own gun model. Like engineTest it needs the bot's
// classes, which must never be on the classpath of a battle JVM (Robocode would take them from there).
val gunBench: SourceSet = sourceSets.create("gunBench") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

// The benches replay the bot's classes: a change that breaks them fails the build, not only the next bench run.
tasks.named("check") { dependsOn(tasks.named(gunBench.classesTaskName)) }

dependencies {
    // Compile against the published API; at runtime the official installation's libs are used.
    compileOnly("net.sf.robocode:robocode.api:$robocodeVersion")

    testImplementation("net.sf.robocode:robocode.api:$robocodeVersion")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "engineTestImplementation"(project(":bot"))
    "engineTestImplementation"(platform("org.junit:junit-bom:6.1.3"))
    "engineTestImplementation"("org.junit.jupiter:junit-jupiter")
    "engineTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")

    "gunBenchImplementation"(project(":bot"))

    decompiler("org.vineflower:vineflower:1.12.0")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(toolchainJava)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
}

val robocodeHome = rootProject.layout.projectDirectory.dir(".robocode/$robocodeVersion")
// Robot and team jars built from git revisions; deployed next to the current ones and kept across reinstalls.
val baselinesDir = rootProject.layout.projectDirectory.dir(".robocode/baselines")
val setupJar = rootProject.layout.projectDirectory.file(".robocode/downloads/robocode-$robocodeVersion-setup.jar")

val downloadRobocode = tasks.register("downloadRobocode") {
    group = "robocode"
    description = "Downloads the official Robocode setup jar and verifies its checksum."
    val jarFile = setupJar.asFile
    val url = "https://github.com/robo-code/robocode/releases/download/v$robocodeVersion/robocode-$robocodeVersion-setup.jar"
    val expectedSha256 = robocodeSetupSha256
    outputs.file(jarFile)
    doLast {
        if (!jarFile.exists()) {
            jarFile.parentFile.mkdirs()
            URI(url).toURL().openStream().use { input -> jarFile.outputStream().use { input.copyTo(it) } }
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(jarFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (actual != expectedSha256) {
            jarFile.delete()
            throw GradleException("Checksum mismatch for $url: $actual")
        }
    }
}

val installRobocode = tasks.register<Copy>("installRobocode") {
    group = "robocode"
    description = "Unpacks the official Robocode installation used by the harness."
    dependsOn(downloadRobocode)
    from(zipTree(setupJar)) {
        exclude("META-INF/**", "net/**")
    }
    into(robocodeHome)
}

/**
 * Builds the robot and team jars of git revisions, with the revision's short hash as the bot version, into
 * [baselinesDir]. A revision whose jars exist is skipped; a value that is not a revision is taken as the version of
 * a jar that is already there.
 */
abstract class BaselineJars : DefaultTask() {
    @get:Input
    abstract val revisions: ListProperty<String>

    @get:Internal
    abstract val repository: DirectoryProperty

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:Internal
    abstract val baselines: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        for (revision in revisions.get()) {
            val hash = Git.shortHash(exec, repository.get().asFile, revision) ?: continue
            val target = baselines.get().asFile
            val jars = listOf("cz.libors.Ogi_$hash.jar", "cz.libors.OgiTeam_$hash.jar").map { target.resolve(it) }
            if (jars.all { it.exists() }) {
                logger.lifecycle("Baseline $hash ($revision): jars exist")
                continue
            }
            logger.lifecycle("Baseline $hash ($revision): building")
            val work = workDir.get().asFile.resolve(hash)
            work.deleteRecursively()
            work.mkdirs()
            val tar = work.resolve("source.tar")
            exec.exec {
                workingDir = repository.get().asFile
                commandLine("git", "archive", "--format=tar", "-o", tar.absolutePath, hash)
            }
            val source = work.resolve("source")
            source.mkdirs()
            exec.exec { commandLine("tar", "-xf", tar.absolutePath, "-C", source.absolutePath) }
            exec.exec {
                workingDir = source
                commandLine("sh", "./gradlew", "--quiet", ":bot:robotJar", ":bot:teamJar", "-PbotVersion=$hash")
            }
            target.mkdirs()
            source.resolve("bot/build/robots").listFiles { f -> f.name.endsWith(".jar") }!!.forEach {
                it.copyTo(target.resolve(it.name), overwrite = true)
            }
        }
    }
}

object Git {
    /** The short hash of a git revision, or null if [revision] is not one. */
    fun shortHash(exec: ExecOperations, repository: File, revision: String): String? {
        val out = ByteArrayOutputStream()
        val result = exec.exec {
            workingDir = repository
            commandLine("git", "rev-parse", "--short=7", "--verify", "--quiet", "$revision^{commit}")
            standardOutput = out
            isIgnoreExitValue = true
        }
        return if (result.exitValue == 0) out.toString().trim() else null
    }
}

/** Gives build scripts an [ExecOperations] outside a task. */
abstract class ExecHolder @Inject constructor(val exec: ExecOperations)

val execHolder = objects.newInstance<ExecHolder>()

/** The bot version for a -Pbaseline or -Pcandidate value: a git revision's short hash, or the value itself. */
fun versionOf(value: String): String = Git.shortHash(execHolder.exec, rootDir, value) ?: value

val baselineJars = tasks.register<BaselineJars>("baselineJars") {
    group = "robocode"
    description = "Builds the jars of the git revisions given as -Pbaseline and -Pcandidate, for comparisons."
    revisions.set(listOfNotNull(providers.gradleProperty("baseline").orNull,
        providers.gradleProperty("candidate").orNull))
    repository.set(rootProject.layout.projectDirectory)
    workDir.set(layout.buildDirectory.dir("baselines"))
    baselines.set(baselinesDir)
}

// Foreign robot jars approved for this machine, kept out of git: `.robocode/foreign/approved.txt` lists one jar per
// line as "<jar> <sha256> <notes>" (docs/harness.md, "Foreign robots").
val foreignDir = rootProject.layout.projectDirectory.dir(".robocode/foreign")

fun approvedForeignJars(): Map<String, String> {
    val list = foreignDir.file("approved.txt").asFile
    if (!list.exists()) {
        return emptyMap()
    }
    return list.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .associate { line -> line.split(Regex("\\s+")).let { it[0] to it[1] } }
}

fun sha256Of(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

// The jar check runs on the approved foreign jars before they are deployed; a denied jar stops the build. A jar
// in our or the sample packages is denied, because the harness trusts robots by package (Sandbox.foreign).
val checkForeignJars = tasks.register<JavaExec>("checkForeignJars") {
    group = "verification"
    description = "Runs the jar check on the foreign jars listed in .robocode/foreign/approved.txt."
    mainClass = "cz.libors.harness.foreign.JarCheck"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
    classpath = sourceSets.main.get().runtimeClasspath
    val jars = approvedForeignJars().keys.map { foreignDir.file(it).asFile }
    val marker = layout.buildDirectory.file("foreign-jars-checked")
    args(jars.map { it.absolutePath })
    inputs.files(jars)
    outputs.file(marker)
    onlyIf { jars.isNotEmpty() }
    doLast {
        marker.get().asFile.writeText(jars.joinToString("\n") { it.name } + "\n")
    }
}

val deployBots = tasks.register<Copy>("deployBots") {
    group = "robocode"
    description = "Copies our robot, team, probe and baseline jars and the approved foreign jars into the installation."
    dependsOn(installRobocode, ":bot:robotJar", ":bot:teamJar", ":bot:gunLogJar", ":probe:probeJar", baselineJars,
        checkForeignJars)
    val robotsDir = robocodeHome.dir("robots").asFile
    val approved = approvedForeignJars()
    val foreign = foreignDir.asFile
    from(project(":bot").layout.buildDirectory.dir("robots"))
    from(project(":probe").layout.buildDirectory.dir("robots"))
    from(baselinesDir)
    if (approved.isNotEmpty()) {
        from(foreignDir) { include(approved.keys) }
    }
    into(robotsDir)
    doFirst {
        for ((jar, expected) in approved) {
            val file = foreign.resolve(jar)
            if (!file.exists()) {
                throw GradleException("Approved foreign jar is missing: $file")
            }
            val actual = sha256Of(file)
            if (actual != expected) {
                throw GradleException("SHA-256 of $file is $actual, but $expected was approved")
            }
        }
        // Only our jars and the approved foreign jars stay; the sample robots are directories, not jars.
        robotsDir.listFiles { file ->
            file.name.startsWith("cz.libors.") || file.name.endsWith(".jar") && file.name !in approved.keys
        }?.forEach { it.delete() }
        robotsDir.resolve("robot.database").delete()
    }
}

// Robots may read environment variables, so the battle JVM gets only these; sandboxCheck verifies it.
val battleEnvironment = listOf("PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ")

/** Runs a harness main class against the Robocode installation, on the configured toolchain. */
fun JavaExec.onRobocode(main: String) {
    dependsOn(deployBots)
    mainClass = main
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
    classpath = sourceSets.main.get().runtimeClasspath + fileTree(robocodeHome.dir("libs")) { include("*.jar") }
    workingDir = robocodeHome.asFile
    setEnvironment(battleEnvironment.mapNotNull { key -> System.getenv(key)?.let { key to it } }.toMap())
    jvmArgs(
        "-Xmx1g",
        "-XX:+IgnoreUnrecognizedVMOptions",
        "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    )
    // Same rule as Robocode's set_java_options.sh: Java 12-23 must allow the Security Manager explicitly,
    // Java 24+ refuses the option.
    if (toolchainJava in 12..23) {
        jvmArgs("-Djava.security.manager=allow")
    }
}

val skeletonCheck = tasks.register<JavaExec>("skeletonCheck") {
    group = "verification"
    description = "Runs headless battles against the sample bots and fails on crashes, skipped turns or losses."
    onRobocode("cz.libors.harness.SkeletonCheck")
    args(
        robocodeHome.asFile.absolutePath,
        "cz.libors.Ogi $botVersion",
        "cz.libors.OgiTeam $botVersion",
        layout.buildDirectory.file("skeleton-check.json").get().asFile.absolutePath,
    )
}

tasks.register<JavaExec>("sandboxCheck") {
    group = "verification"
    description = "Checks that robots run in Robocode's sandbox with a minimal environment, and the jar check."
    onRobocode("cz.libors.harness.SandboxCheck")
    // Debug mode changes no security rule; the engine waits for the dying probe's report (engine-model G-34).
    jvmArgs("-Ddebug=true")
    // A security violation bans the robot in the repository, and the robot database could keep the ban.
    val database = robocodeHome.file("robots/robot.database").asFile
    doFirst {
        database.delete()
    }
    args(
        robocodeHome.asFile.absolutePath,
        botVersion,
        project(":bot").layout.buildDirectory.file("robots/cz.libors.Ogi_$botVersion.jar").get().asFile.absolutePath,
        project(":probe").layout.buildDirectory.file("robots/cz.libors.probe_$botVersion.jar").get().asFile
            .absolutePath,
    )
}

val recordProbes = tasks.register<JavaExec>("recordProbes") {
    group = "verification"
    description = "Records probe battles turn by turn and checks their integrity, coverage and determinism."
    onRobocode("cz.libors.harness.ProbeRecordings")
    // Debug mode: the engine waits for every robot without a time limit, so no robot can skip a turn and the
    // recordings do not depend on machine load. It changes no rule of the game.
    jvmArgs("-Ddebug=true")
    val recordings = layout.buildDirectory.dir("recordings").get().asFile
    doFirst {
        recordings.deleteRecursively()
    }
    args(robocodeHome.asFile.absolutePath, botVersion, recordings.absolutePath)
}

/**
 * Compare's arguments for a run whose Robocode home is [home], which sees a repository path (the opponent set) at
 * [file] of it and writes to [out].
 */
fun compareArguments(home: String, file: (String) -> String, out: () -> String): () -> List<String> {
    val baseline = providers.gradleProperty("baseline")
    val candidate = providers.gradleProperty("candidate")
    val opponents = providers.gradleProperty("opponents").orElse("harness/opponents/sample-1v1.txt")
    val passed = listOf("bounds", "maxPairs", "pairsPerOpponent", "starts", "seed", "rounds", "metric")
        .mapNotNull { key -> providers.gradleProperty(key).orNull?.let { "$key=$it" } }
    return {
        val baselineValue = baseline.orNull ?: throw GradleException("Pass -Pbaseline=<git revision or version>")
        listOf(
            home,
            "robot=cz.libors.Ogi",
            "team=cz.libors.OgiTeam",
            "baseline=" + versionOf(baselineValue),
            "candidate=" + (candidate.orNull?.let(::versionOf) ?: botVersion),
            "opponents=" + file(opponents.get()),
            "out=" + out(),
        ) + passed
    }
}

/**
 * RecordBattles' arguments for [robot], placed like [compareArguments]; with [keys], only the debug properties it
 * gives for the opponent set's file.
 */
fun recordArguments(robot: String, keys: ((String) -> String)?, home: String, file: (String) -> String,
                    out: () -> String):
    () -> List<String> {
    val versions = providers.gradleProperty("versions")
    val opponents = providers.gradleProperty("opponents").orElse("harness/opponents/lab-1v1.txt")
    val passed = listOf("only", "pairs", "draws", "seed", "rounds", "starts")
        .mapNotNull { key -> providers.gradleProperty(key).orNull?.let { "$key=$it" } }
    return {
        val versionList = (versions.orNull ?: throw GradleException("Pass -Pversions=<revision or version>,..."))
            .split(",").map(::versionOf)
        listOf(
            home,
            "robot=$robot",
            "versions=" + versionList.joinToString(","),
            "opponents=" + file(opponents.get()),
            "out=" + out(),
        ) + passed + listOfNotNull(keys?.let { "keys=" + it(file(opponents.get())) })
    }
}

/** The format of an opponent set ("1v1", "melee" or "team"), from its `format` line. */
fun setFormat(opponentsFile: String): String =
    File(opponentsFile).readLines().map { it.trim().split(Regex("\\s+")) }
        .firstOrNull { it.size == 2 && it[0] == "format" }?.get(1) ?: "1v1"

/**
 * The gun's debug properties, or against a melee or team set the melee gun's and every robot's position (melee
 * bench).
 */
fun gunLogKeys(opponentsFile: String): String =
    if (setFormat(opponentsFile) in setOf("melee", "team"))
        "meleescan,meleedeath,meleeaim,meleefire,meleeend,hitbybullet,duelstart,@positions"
    else "gunshot,gunpass,gunshield"

/** A repository path as a local run sees it. */
fun localFile(path: String): String = rootDir.resolve(path).absolutePath

tasks.register<JavaExec>("compare") {
    group = "verification"
    description = "Paired, sequential comparison of a baseline and a candidate bot version; see docs/harness.md."
    onRobocode("cz.libors.harness.compare.Compare")
    val out = providers.gradleProperty("out").orElse("harness/build/compare")
    val arguments = compareArguments(robocodeHome.asFile.absolutePath, ::localFile) { localFile(out.get()) }
    argumentProviders.add(CommandLineArgumentProvider { arguments() })
}

/** Runs RecordBattles for [robot] on comparison seeds; with [keys], only those debug properties are recorded. */
fun JavaExec.recordsBattles(robot: String, defaultOut: String, keys: ((String) -> String)?) {
    onRobocode("cz.libors.harness.compare.RecordBattles")
    // Debug mode, as in recordProbes: no robot can skip a turn, so a recording does not depend on machine load.
    jvmArgs("-Ddebug=true")
    val out = providers.gradleProperty("out").orElse(defaultOut)
    val arguments = recordArguments(robot, keys, robocodeHome.asFile.absolutePath, ::localFile) {
        localFile(out.get())
    }
    argumentProviders.add(CommandLineArgumentProvider { arguments() })
}

tasks.register<JavaExec>("recordBattles") {
    group = "verification"
    description = "Records comparison battles turn by turn, on the seeds compare uses; see docs/harness.md."
    recordsBattles("cz.libors.Ogi", "harness/build/battle-recordings", null)
}

tasks.register<JavaExec>("recordGunLogs") {
    group = "verification"
    description = "Records the gun's log of comparison battles for the gun bench; see docs/harness.md."
    // OgiGunLog plays like Ogi and logs its gun; only the working tree has its jar (bot/build/robots).
    recordsBattles("cz.libors.OgiGunLog", "harness/build/gun-logs", ::gunLogKeys)
}

tasks.register<JavaExec>("gunBench") {
    group = "verification"
    description = "Replays recorded gun logs with given gun parameters and reports hit rates; see docs/harness.md."
    mainClass = "cz.libors.harness.gunbench.GunBench"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
    classpath = gunBench.runtimeClasspath
    jvmArgs("-Xmx6g")
    val logs = providers.gradleProperty("logs").orElse("harness/build/gun-logs/$botVersion")
    val passed = listOf("robot", "selection", "weights", "neighbourScale", "maxNeighbours", "virtualWeight",
        "minTries", "tune", "maxShielded", "splitSeed")
        .mapNotNull { key -> providers.gradleProperty(key).orNull?.let { "$key=$it" } }
    val root = rootDir
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("logs=" + logs.get().split(",").joinToString(",") { root.resolve(it.trim()).absolutePath }) +
            passed.map { if (it.startsWith("selection=")) "selection=" + root.resolve(it.substringAfter('=')).absolutePath else it }
    })
}

tasks.register<JavaExec>("meleeBench") {
    group = "verification"
    description = "Replays recorded melee gun logs with given melee gun parameters and scores the bullets against " +
        "the recorded positions; see docs/harness.md."
    mainClass = "cz.libors.harness.meleebench.MeleeBench"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
    classpath = gunBench.runtimeClasspath
    jvmArgs("-Xmx8g", "-Dmeleebench.debug=" + (providers.gradleProperty("debugDiff").orNull ?: "false"))
    val logs = providers.gradleProperty("logs")
    val passed = listOf("robot", "base", "weights", "neighbours", "wallRange", "neighbourRange", "distanceSoftening",
        "enemyDistancePower", "kernelWidth", "maxAge", "horizon", "maxRecords", "everyRound", "reachingOnly", "tune",
        "splitSeed", "fixed", "powers", "singleEnemy")
        .mapNotNull { key -> providers.gradleProperty(key).orNull?.let { "$key=$it" } }
    val otherLogs = providers.gradleProperty("otherLogs")
    val root = rootDir
    argumentProviders.add(CommandLineArgumentProvider {
        val dirs = { value: String -> value.split(",").joinToString(",") { root.resolve(it.trim()).absolutePath } }
        listOf("logs=" + dirs(logs.orNull ?: throw GradleException("Pass -Plogs=<melee gun logs>,..."))) +
            listOfNotNull(otherLogs.orNull?.let { "otherLogs=" + dirs(it) }) + passed
    })
}

tasks.register<JavaExec>("positionBench") {
    group = "verification"
    description = "Replays the melee movement's logged decisions of recorded team battles with other parameters and " +
        "scores the destinations against the recordings; see docs/harness.md."
    mainClass = "cz.libors.harness.positionbench.PositionBench"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
    classpath = gunBench.runtimeClasspath
    jvmArgs("-Xmx12g")
    val recordings = providers.gradleProperty("recordings")
    val passed = listOf("robot", "base", "variants", "horizon")
        .mapNotNull { key -> providers.gradleProperty(key).orNull?.let { "$key=$it" } }
    val root = rootDir
    argumentProviders.add(CommandLineArgumentProvider {
        val dirs = (recordings.orNull ?: throw GradleException("Pass -Precordings=<dir>,..."))
            .split(",").joinToString(",") { root.resolve(it.trim()).absolutePath }
        listOf("recordings=$dirs") + passed
    })
}

tasks.register<JavaExec>("dangerBench") {
    group = "verification"
    description = "Replays the enemy bullets of full recordings through the surfer's danger model; see docs/harness.md."
    mainClass = "cz.libors.harness.dangerbench.DangerBench"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
    classpath = gunBench.runtimeClasspath
    jvmArgs("-Xmx6g")
    val recordings = providers.gradleProperty("recordings").orElse("harness/build/battle-recordings/$botVersion")
    val passed = listOf("robot", "selection", "preset", "weights", "neighbourScale", "maxNeighbours", "bandwidth",
        "knnShare", "knnRamp", "priorWeight", "simplePrior", "simpleBandwidth", "temper", "decay", "share", "flatPrior",
        "flatWindow", "tune", "bullets")
        .mapNotNull { key -> providers.gradleProperty(key).orNull?.let { "$key=$it" } }
    val root = rootDir
    val paths = listOf("selection=", "bullets=")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("recordings=" + recordings.get().split(",").joinToString(",") { root.resolve(it.trim()).absolutePath }) +
            passed.map { arg ->
                paths.firstOrNull { arg.startsWith(it) }?.let { it + root.resolve(arg.substringAfter('=')).absolutePath }
                    ?: arg
            }
    })
}

tasks.register<JavaExec>("gunTrend") {
    group = "verification"
    description = "Compares the gun's hit rate early and late in battles, per opponent, from gun logs."
    mainClass = "cz.libors.harness.gunbench.GunTrend"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
    classpath = gunBench.runtimeClasspath
    jvmArgs("-Xmx4g")
    val logs = providers.gradleProperty("logs").orElse("harness/build/gun-logs/$botVersion")
    val rounds = providers.gradleProperty("rounds").orElse("10")
    val root = rootDir
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("logs=" + logs.get().split(",").joinToString(",") { root.resolve(it.trim()).absolutePath },
            "rounds=" + rounds.get())
    })
}

tasks.register<JavaExec>("jarCheck") {
    group = "verification"
    description = "Checks foreign robot jars against the allowlist before they run; see docs/harness.md."
    mainClass = "cz.libors.harness.foreign.JarCheck"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
    classpath = sourceSets.main.get().runtimeClasspath
    val jars = providers.gradleProperty("jars")
    val root = rootDir
    argumentProviders.add(CommandLineArgumentProvider {
        (jars.orNull ?: throw GradleException("Pass -Pjars=<jar or directory>,..."))
            .split(",").map { root.resolve(it).absolutePath }
    })
}

tasks.register<JavaExec>("decompileJar") {
    group = "verification"
    description = "Decompiles a robot jar for review into harness/build/decompiled/<jar name>/; nothing in it runs."
    mainClass = "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
    classpath = decompiler
    val jar = providers.gradleProperty("jar")
    val out = layout.buildDirectory.dir("decompiled")
    val root = rootDir
    argumentProviders.add(CommandLineArgumentProvider {
        val file = root.resolve(jar.orNull ?: throw GradleException("Pass -Pjar=<robot jar>"))
        val target = out.get().dir(file.nameWithoutExtension).asFile
        target.deleteRecursively()
        target.mkdirs()
        // Only the decompiled classes: a source file shipped in the jar must not stand in for its class.
        listOf("--skip-extra-files=true", "--silent", file.absolutePath, target.absolutePath)
    })
}

tasks.register<Test>("engineTest") {
    group = "verification"
    description = "Replays the probe recordings through the bot's engine model and requires exact agreement."
    dependsOn(recordProbes)
    testClassesDirs = engineTest.output.classesDirs
    classpath = engineTest.runtimeClasspath
    useJUnitPlatform()
    val recordings = layout.buildDirectory.dir("recordings").get().asFile
    systemProperty("ogi.recordings", recordings.absolutePath)
    inputs.dir(recordings)
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Remote workers (docs/build-and-run.md, "Remote worker"): a bundle built from the working tree is copied to the
// worker and run there. The native Windows worker (default, `-PremoteHost=ogi-win`) runs it through the queue's
// executor (queue.Remote); the WSL container worker (`-PremoteHost=ogi-desktop`) through worker/remote.sh. Results
// come back to harness/build/remote/<name>/.

val harnessJar = tasks.register<Jar>("harnessJar") {
    description = "Packages the harness's main classes for the remote worker."
    archiveFileName = "harness.jar"
    destinationDirectory = layout.buildDirectory.dir("libs")
    from(sourceSets.main.map { it.output })
}

val workerBundle = tasks.register<Sync>("workerBundle") {
    group = "robocode"
    description = "Assembles the remote worker's bundle from the working tree into harness/build/worker-bundle/."
    dependsOn(":bot:robotJar", ":bot:teamJar", ":bot:gunLogJar", ":probe:probeJar", baselineJars, downloadRobocode)
    into(layout.buildDirectory.dir("worker-bundle"))
    from(harnessJar) { into("lib") }
    from(project(":bot").layout.buildDirectory.dir("robots")) { into("robots") }
    from(project(":probe").layout.buildDirectory.dir("robots")) { into("robots") }
    from(baselinesDir) { into("robots") }
    from(layout.projectDirectory.dir("opponents")) { into("harness/opponents") }
    from(rootProject.layout.projectDirectory.dir("worker")) { into("worker") }
    from(setupJar) { into("worker") }
}

val remoteHost = providers.gradleProperty("remoteHost").getOrElse("ogi-win")
val remoteContainer = providers.gradleProperty("remoteKind").getOrElse(
    if (remoteHost == "ogi-desktop") "container" else "windows") == "container"
// The CPU constant on a worker: the median of idle measurements under the worker's JVM options, times this factor
// (build-and-run.md, "Several battles at once"). The queue's workers use the same.
val cpuFactor = providers.gradleProperty("cpuFactor").getOrElse("1.5")

/**
 * Where a remote run finds things: its Robocode home, a repository path (`rumble:<file>` is a file in the worker's
 * directory of approved foreign jars) and its output directory, for a run named `name`.
 */
class RemoteLayout(val home: (String) -> String, val file: (String) -> String, val out: (String) -> String)

// Paths inside the worker's container (worker/remote.sh mounts them).
val containerLayout = RemoteLayout(
    { _ -> "/ogi/robocode-run" },
    { path -> if (path.startsWith("rumble:")) "/ogi/rumble/" + path.removePrefix("rumble:") else "/ogi/bundle/$path" },
    { name -> "/ogi/out/$name" },
)

// Paths on the native Windows worker (build-and-run.md, "Native Windows worker").
val windowsLayout = RemoteLayout(
    { name -> "C:/ogi/run/$name" },
    { path -> if (path.startsWith("rumble:")) "C:/ogi/rumble/" + path.removePrefix("rumble:") else "C:/ogi/bundle/$path" },
    { name -> "C:/ogi/out/$name" },
)

val remoteLayout = if (remoteContainer) containerLayout else windowsLayout

/**
 * Registers a task that runs a harness main class on the remote worker under the name [name]: it writes to
 * [RemoteLayout.out], which comes back to harness/build/remote/<name>. [jvm] are extra JVM options; [clean] empties
 * the run's output first.
 *
 * In the container, `-PremoteCpuset` pins it to some cores, e.g. `0-1` (default: all), and `-PremoteBattleCore`
 * pins the battle's threads to one core (worker/run.sh). On Windows the run gets a slot's JVM options and CPU
 * constant (`-PcpuFactor`, queue.Executor).
 */
fun remoteTask(taskName: String, text: String, name: String, main: String, jvm: String, clean: Boolean,
               arguments: () -> List<String>) {
    val bundle = layout.buildDirectory.dir("worker-bundle").get().asFile
    val results = layout.buildDirectory.dir("remote").get().asFile
    if (remoteContainer) {
        tasks.register<Exec>(taskName) {
            group = "remote"
            description = text
            dependsOn(workerBundle)
            val script = rootDir.resolve("worker/remote.sh")
            executable = "bash"
            environment("OGI_ROBOCODE_VERSION", robocodeVersion)
            environment("OGI_ROBOCODE_SHA256", robocodeSetupSha256)
            environment("OGI_JVM_OPTIONS", jvm)
            environment("OGI_CLEAN", if (clean) "1" else "")
            environment("OGI_CPUSET", providers.gradleProperty("remoteCpuset").getOrElse(""))
            environment("OGI_BATTLE_CORE", providers.gradleProperty("remoteBattleCore").getOrElse(""))
            argumentProviders.add(CommandLineArgumentProvider {
                listOf(script.absolutePath, remoteHost, bundle.absolutePath, results.absolutePath, name, main) +
                    arguments()
            })
        }
    } else {
        tasks.register<JavaExec>(taskName) {
            group = "remote"
            description = text
            dependsOn(workerBundle)
            mainClass = "cz.libors.harness.queue.Remote"
            javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
            classpath = sourceSets.main.get().runtimeClasspath
            argumentProviders.add(CommandLineArgumentProvider {
                listOf("host=$remoteHost", "bundle=${bundle.absolutePath}", "results=${results.absolutePath}",
                    "name=$name", "clean=$clean", "cpuFactor=$cpuFactor") +
                    jvm.split(" ").filter { it.isNotEmpty() }.map { "runJvm=$it" } + listOf("--", main) + arguments()
            })
        }
    }
}

remoteTask("remoteSkeletonCheck", "Runs skeletonCheck on the remote worker.", "skeleton-check",
    "cz.libors.harness.SkeletonCheck", "", true) {
    listOf(remoteLayout.home("skeleton-check"), "cz.libors.Ogi $botVersion", "cz.libors.OgiTeam $botVersion",
        remoteLayout.out("skeleton-check") + "/skeleton-check.json")
}

remoteTask("remoteSandboxCheck", "Runs sandboxCheck on the remote worker, in its slot environment.",
    "sandbox-check", "cz.libors.harness.SandboxCheck", "-Ddebug=true", true) {
    listOf(remoteLayout.home("sandbox-check"), botVersion,
        remoteLayout.file("robots/cz.libors.Ogi_$botVersion.jar"),
        remoteLayout.file("robots/cz.libors.probe_$botVersion.jar"))
}

// Debug mode, as locally.
remoteTask("remoteRecordProbes", "Runs recordProbes on the remote worker; compare its digests with the local ones.",
    "recordings", "cz.libors.harness.ProbeRecordings", "-Ddebug=true", true) {
    listOf(remoteLayout.home("recordings"), botVersion, remoteLayout.out("recordings"))
}

val remoteCompareArguments = compareArguments(remoteLayout.home("compare"), remoteLayout.file) {
    remoteLayout.out("compare")
}
remoteTask("remoteCompare", "Runs compare on the remote worker in one process; the log there resumes like a local one.",
    "compare", "cz.libors.harness.compare.Compare", "", false) { remoteCompareArguments() }

val remoteRecordArguments = recordArguments("cz.libors.Ogi", null, remoteLayout.home("battle-recordings"),
    remoteLayout.file) { remoteLayout.out("battle-recordings") }
remoteTask("remoteRecordBattles", "Runs recordBattles on the remote worker.", "battle-recordings",
    "cz.libors.harness.compare.RecordBattles", "-Ddebug=true", false) { remoteRecordArguments() }

// The opponent set's path is the worker's there: the duel gun's keys only.
val remoteGunLogArguments = recordArguments("cz.libors.OgiGunLog", { _ -> "gunshot,gunpass,gunshield" },
    remoteLayout.home("gun-logs"), remoteLayout.file) { remoteLayout.out("gun-logs") }
remoteTask("remoteRecordGunLogs", "Runs recordGunLogs on the remote worker.", "gun-logs",
    "cz.libors.harness.compare.RecordBattles", "-Ddebug=true", false) { remoteGunLogArguments() }

// The queue (docs/harness.md, "Queue"): a server on this machine keeps jobs and drives the workers; `submit` adds a
// job, whether the server runs or not. State lives in .robocode/queue/, outside the build directory.

// -PqueueState=<dir> keeps a test queue apart from the real one.
val queueDir: File = providers.gradleProperty("queueState").map { rootDir.resolve(it) }
    .getOrElse(rootProject.layout.projectDirectory.dir(".robocode/queue").asFile)
// Jars of working-tree snapshots, named after their content (SnapshotJars).
val snapshotsDir = rootProject.layout.projectDirectory.dir(".robocode/snapshots")
val snapshotVersionFile = layout.buildDirectory.file("snapshot-version.txt")

/**
 * Builds the robot, team and gun-log jars of the working tree as it is now, with a version named after the content
 * of what goes into them (`w` and 7 hex digits of a SHA-256 over bot/ and the build files, tracked or not, ignored
 * files left out), into [snapshots]. The same content gives the same version and is built once. The version is
 * written to [versionFile]. Queued jobs keep their jars apart this way: the working tree's own version (botVersion)
 * names different code after every edit.
 */
abstract class SnapshotJars : DefaultTask() {
    @get:Internal
    abstract val repository: DirectoryProperty

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:Internal
    abstract val snapshots: DirectoryProperty

    @get:Internal
    abstract val versionFile: RegularFileProperty

    @get:Inject
    abstract val exec: ExecOperations

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun build() {
        val repo = repository.get().asFile
        val listing = ByteArrayOutputStream()
        exec.exec {
            workingDir = repo
            commandLine("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard")
            standardOutput = listing
        }
        val files = listing.toString().split('\u0000').filter { it.isNotEmpty() }.distinct().sorted()
            .filter { repo.resolve(it).isFile }
        val digest = MessageDigest.getInstance("SHA-256")
        for (f in files) {
            if (f.startsWith("bot/") || f.startsWith("gradle/") || f in setOf("gradle.properties", "settings.gradle.kts")) {
                digest.update(f.toByteArray())
                digest.update(0)
                digest.update(repo.resolve(f).readBytes())
                digest.update(0)
            }
        }
        val version = "w" + digest.digest().joinToString("") { "%02x".format(it) }.take(7)
        versionFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(version)
        }
        val target = snapshots.get().asFile
        val names = listOf("Ogi", "OgiTeam", "OgiGunLog", "OgiGunLogTeam").map { "cz.libors.${it}_$version.jar" }
        if (names.all { target.resolve(it).exists() }) {
            logger.lifecycle("Working tree snapshot $version: jars exist")
            return
        }
        logger.lifecycle("Working tree snapshot $version: building")
        val work = workDir.get().asFile.resolve(version)
        work.deleteRecursively()
        val source = work.resolve("source")
        for (f in files) {
            repo.resolve(f).copyTo(source.resolve(f))
        }
        exec.exec {
            workingDir = source
            commandLine("sh", "./gradlew", "--quiet", ":bot:robotJar", ":bot:teamJar", ":bot:gunLogJar",
                ":bot:gunLogTeamJar", "-PbotVersion=$version")
        }
        target.mkdirs()
        names.forEach { source.resolve("bot/build/robots/$it").copyTo(target.resolve(it), overwrite = true) }
        work.deleteRecursively()
    }
}

val snapshotJars = tasks.register<SnapshotJars>("snapshotJars") {
    group = "queue"
    description = "Builds the working tree's jars under a version named after their content, for queued jobs."
    repository.set(rootProject.layout.projectDirectory)
    workDir.set(layout.buildDirectory.dir("snapshots"))
    snapshots.set(snapshotsDir)
    versionFile.set(snapshotVersionFile)
}

/** The robot and team jars of a bot version: a baseline, a snapshot, or the working tree's own. */
fun subjectJars(version: String): List<File> {
    val dirs = listOf(baselinesDir.asFile, snapshotsDir.asFile, project(":bot").layout.buildDirectory.dir("robots")
        .get().asFile)
    return listOf("cz.libors.Ogi", "cz.libors.OgiTeam").map { robot ->
        dirs.map { it.resolve("${robot}_$version.jar") }.firstOrNull { it.exists() }
            ?: throw GradleException("No jar of $robot $version in .robocode/baselines, .robocode/snapshots or " +
                "bot/build/robots")
    }
}

tasks.register<JavaExec>("submit") {
    group = "queue"
    description = "Queues a comparison of the working tree (or -Pcandidate) with -Pbaseline; see docs/harness.md."
    val candidateProperty = providers.gradleProperty("candidate")
    dependsOn(baselineJars, installRobocode, ":bot:robotJar", ":bot:teamJar")
    if (!candidateProperty.isPresent) {
        dependsOn(snapshotJars)
    }
    mainClass = "cz.libors.harness.queue.Submit"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
    classpath = sourceSets.main.get().runtimeClasspath + fileTree(robocodeHome.dir("libs")) { include("*.jar") }
    val baseline = providers.gradleProperty("baseline")
    val opponents = providers.gradleProperty("opponents").orElse("harness/opponents/lab-1v1.txt")
    val passed = listOf("bounds", "maxPairs", "pairsPerOpponent", "draws", "starts", "seed", "rounds", "metric", "note",
        "timeout", "reuse").mapNotNull { key -> providers.gradleProperty(key).orNull?.let { "$key=$it" } }
    val versionFile = snapshotVersionFile
    val port = providers.gradleProperty("port").orElse("8765")
    argumentProviders.add(CommandLineArgumentProvider {
        val baselineVersion = versionOf(baseline.orNull ?: throw GradleException("Pass -Pbaseline=<git revision or version>"))
        val candidateVersion = candidateProperty.orNull?.let(::versionOf) ?: versionFile.get().asFile.readText().trim()
        listOf("state=" + queueDir.absolutePath, "type=compare", "robot=cz.libors.Ogi", "team=cz.libors.OgiTeam",
            "baseline=$baselineVersion", "candidate=$candidateVersion", "opponents=" + localFile(opponents.get())) +
            (subjectJars(baselineVersion) + subjectJars(candidateVersion)).map { "jar=" + it.absolutePath } + passed +
            listOf("port=" + port.get())
    })
}

tasks.register<JavaExec>("submitGunLogs") {
    group = "queue"
    description = "Queues the gun's logs (OgiGunLog) of the working tree against -Popponents, or the melee gun's " +
        "against a melee set; see docs/harness.md."
    dependsOn(snapshotJars, installRobocode)
    mainClass = "cz.libors.harness.queue.Submit"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
    classpath = sourceSets.main.get().runtimeClasspath + fileTree(robocodeHome.dir("libs")) { include("*.jar") }
    val opponents = providers.gradleProperty("opponents").orElse("harness/opponents/lab-1v1.txt")
    val passed = listOf("pairs", "draws", "seed", "rounds", "starts", "note", "timeout", "debug")
        .mapNotNull { key -> providers.gradleProperty(key).orNull?.let { "$key=$it" } }
    val versionFile = snapshotVersionFile
    val port = providers.gradleProperty("port").orElse("8765")
    argumentProviders.add(CommandLineArgumentProvider {
        val version = versionFile.get().asFile.readText().trim()
        val keys = gunLogKeys(localFile(opponents.get()))
        // Against a team set the subject is five OgiGunLog as a team.
        val robot = if (setFormat(localFile(opponents.get())) == "team") "cz.libors.OgiGunLogTeam"
            else "cz.libors.OgiGunLog"
        listOf("state=" + queueDir.absolutePath, "type=record", "robot=$robot", "version=$version",
            "keys=$keys", "opponents=" + localFile(opponents.get()),
            "jar=" + snapshotsDir.file("${robot}_$version.jar").asFile.absolutePath,
            "port=" + port.get()) + passed
    })
}

tasks.register<JavaExec>("submitRecordings") {
    group = "queue"
    description = "Queues turn-by-turn recordings of the working tree's Ogi (OgiGunLog with -PgunLog=true), or of " +
        "-Psubject (a robot on the worker), against -Popponents; see docs/harness.md."
    dependsOn(snapshotJars, installRobocode)
    mainClass = "cz.libors.harness.queue.Submit"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
    classpath = sourceSets.main.get().runtimeClasspath + fileTree(robocodeHome.dir("libs")) { include("*.jar") }
    val opponents = providers.gradleProperty("opponents").orElse("harness/opponents/lab-1v1.txt")
    val passed = listOf("pairs", "seed", "rounds", "starts", "keys", "note", "timeout", "debug")
        .mapNotNull { key -> providers.gradleProperty(key).orNull?.let { "$key=$it" } }
    // A foreign robot as Robocode lists it ("kc.serpent.Hydra 0.21"): the worker's approved jar, nothing shipped.
    val subject = providers.gradleProperty("subject")
    // OgiGunLog (five of them against a team set): the full recording keeps its logs too, e.g. the melee movement's
    // decisions for the position bench.
    val gunLog = providers.gradleProperty("gunLog").map { it.toBoolean() }.orElse(false)
    val versionFile = snapshotVersionFile
    val port = providers.gradleProperty("port").orElse("8765")
    argumentProviders.add(CommandLineArgumentProvider {
        val foreign = subject.orNull?.trim()?.split(Regex("\\s+"))
        val robot = if (foreign == null) {
            val version = versionFile.get().asFile.readText().trim()
            // Against a team set the subject is our team.
            val team = rootDir.resolve(opponents.get()).readLines().any { it.trim() == "format team" }
            val name = if (gunLog.get()) (if (team) "cz.libors.OgiGunLogTeam" else "cz.libors.OgiGunLog")
                else if (team) "cz.libors.OgiTeam" else "cz.libors.Ogi"
            listOf("robot=$name", "version=$version",
                "jar=" + snapshotsDir.file("${name}_$version.jar").asFile.absolutePath)
        } else {
            require(foreign.size == 2) { "-Psubject wants a robot and its version, e.g. \"kc.serpent.Hydra 0.21\"" }
            listOf("robot=" + foreign[0], "version=" + foreign[1])
        }
        listOf("state=" + queueDir.absolutePath, "type=record", "opponents=" + localFile(opponents.get()),
            "port=" + port.get()) + robot + passed
    })
}

tasks.register<JavaExec>("queueServer") {
    group = "queue"
    description = "Runs the queue's server and dashboard (http://localhost:8765) until stopped; see docs/harness.md."
    dependsOn(workerBundle, installRobocode)
    mainClass = "cz.libors.harness.queue.Server"
    val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
    javaLauncher = launcher
    classpath = sourceSets.main.get().runtimeClasspath + fileTree(robocodeHome.dir("libs")) { include("*.jar") }
    val bundle = layout.buildDirectory.dir("worker-bundle")
    val workers = providers.gradleProperty("workers").orElse("ogi-win:12")
    val port = providers.gradleProperty("port").orElse("8765")
    val extraJvm = providers.gradleProperty("queueJvm").orElse("")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("state=" + queueDir.absolutePath, "bundle=" + bundle.get().asFile.absolutePath,
            "workers=" + workers.get(), "port=" + port.get(), "cpuFactor=$cpuFactor",
            "localJava=" + launcher.get().executablePath.asFile.absolutePath,
            "localSecurityManager=" + (toolchainJava in 12..23)) +
            extraJvm.get().split(" ").filter { it.isNotEmpty() }.map { "jvm=$it" }
    })
}

/** Runs the queue's terminal client: `status`, or an action on `-Pjob`. */
fun JavaExec.queueClient(command: String) {
    group = "queue"
    mainClass = "cz.libors.harness.queue.QueueClient"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
    classpath = sourceSets.main.get().runtimeClasspath
    val job = providers.gradleProperty("job")
    val port = providers.gradleProperty("port").orElse("8765")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(command, "port=" + port.get(), "state=" + queueDir.absolutePath) +
            (if (command == "status") emptyList() else listOf("id=" + (job.orNull
                ?: throw GradleException("Pass -Pjob=<id>"))))
    })
}

tasks.register<JavaExec>("queueStatus") {
    description = "Prints the queue: workers, waiting and running jobs, history."
    queueClient("status")
}

tasks.register<JavaExec>("queueCancel") {
    description = "Cancels the queued or running job -Pjob=<id>."
    queueClient("cancel")
}
