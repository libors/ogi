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
    dependsOn(installRobocode, ":bot:robotJar", ":bot:teamJar", ":probe:probeJar", baselineJars, checkForeignJars)
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

tasks.register<JavaExec>("compare") {
    group = "verification"
    description = "Paired, sequential comparison of a baseline and a candidate bot version; see docs/harness.md."
    onRobocode("cz.libors.harness.compare.Compare")
    val baseline = providers.gradleProperty("baseline")
    val candidate = providers.gradleProperty("candidate")
    val opponents = providers.gradleProperty("opponents").orElse("harness/opponents/sample-1v1.txt")
    val out = providers.gradleProperty("out").orElse("harness/build/compare")
    val passed = listOf("bounds", "maxPairs", "pairsPerOpponent", "starts", "seed", "rounds", "metric")
        .mapNotNull { key -> providers.gradleProperty(key).orNull?.let { "$key=$it" } }
    val root = rootDir
    argumentProviders.add(CommandLineArgumentProvider {
        val baselineValue = baseline.orNull ?: throw GradleException("Pass -Pbaseline=<git revision or version>")
        listOf(
            robocodeHome.asFile.absolutePath,
            "robot=cz.libors.Ogi",
            "team=cz.libors.OgiTeam",
            "baseline=" + versionOf(baselineValue),
            "candidate=" + (candidate.orNull?.let(::versionOf) ?: botVersion),
            "opponents=" + root.resolve(opponents.get()).absolutePath,
            "out=" + root.resolve(out.get()).absolutePath,
        ) + passed
    })
}

tasks.register<JavaExec>("recordBattles") {
    group = "verification"
    description = "Records comparison battles turn by turn, on the seeds compare uses; see docs/harness.md."
    onRobocode("cz.libors.harness.compare.RecordBattles")
    // Debug mode, as in recordProbes: no robot can skip a turn, so a recording does not depend on machine load.
    jvmArgs("-Ddebug=true")
    val versions = providers.gradleProperty("versions")
    val opponents = providers.gradleProperty("opponents").orElse("harness/opponents/lab-1v1.txt")
    val out = providers.gradleProperty("out").orElse("harness/build/battle-recordings")
    val passed = listOf("only", "pairs", "seed", "rounds", "starts")
        .mapNotNull { key -> providers.gradleProperty(key).orNull?.let { "$key=$it" } }
    val root = rootDir
    argumentProviders.add(CommandLineArgumentProvider {
        val versionList = (versions.orNull ?: throw GradleException("Pass -Pversions=<revision or version>,..."))
            .split(",").map(::versionOf)
        listOf(
            robocodeHome.asFile.absolutePath,
            "robot=cz.libors.Ogi",
            "versions=" + versionList.joinToString(","),
            "opponents=" + root.resolve(opponents.get()).absolutePath,
            "out=" + root.resolve(out.get()).absolutePath,
        ) + passed
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
