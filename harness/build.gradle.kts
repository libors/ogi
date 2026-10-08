import java.net.URI
import java.security.MessageDigest

plugins {
    java
}

val botVersion = providers.gradleProperty("botVersion").get()
val robocodeVersion = providers.gradleProperty("robocodeVersion").get()
val robocodeSetupSha256 = providers.gradleProperty("robocodeSetupSha256").get()
val toolchainJava = providers.gradleProperty("javaVersion").get().toInt()

// Differential tests of the bot's engine model against the recordings; they need `recordProbes` to run first.
val engineTest: SourceSet = sourceSets.create("engineTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    // Compile against the published API; at runtime the official installation's libs are used.
    compileOnly("net.sf.robocode:robocode.api:$robocodeVersion")

    "engineTestImplementation"(project(":bot"))
    "engineTestImplementation"(platform("org.junit:junit-bom:6.1.3"))
    "engineTestImplementation"("org.junit.jupiter:junit-jupiter")
    "engineTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
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

val robocodeHome = rootProject.layout.projectDirectory.dir(".robocode/$robocodeVersion")
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

val deployBots = tasks.register<Copy>("deployBots") {
    group = "robocode"
    description = "Copies the freshly built robot, team and probe jars into the Robocode installation."
    dependsOn(installRobocode, ":bot:robotJar", ":bot:teamJar", ":probe:probeJar")
    val robotsDir = robocodeHome.dir("robots").asFile
    from(project(":bot").layout.buildDirectory.dir("robots"))
    from(project(":probe").layout.buildDirectory.dir("robots"))
    into(robotsDir)
    doFirst {
        robotsDir.listFiles { file -> file.name.startsWith("cz.libors.") }?.forEach { it.delete() }
        robotsDir.resolve("robot.database").delete()
    }
}

/** Runs a harness main class against the Robocode installation, on the configured toolchain. */
fun JavaExec.onRobocode(main: String) {
    dependsOn(deployBots)
    mainClass = main
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainJava) }
    classpath = sourceSets.main.get().runtimeClasspath + fileTree(robocodeHome.dir("libs")) { include("*.jar") }
    workingDir = robocodeHome.asFile
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
