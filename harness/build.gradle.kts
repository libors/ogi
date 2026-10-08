import java.net.URI
import java.security.MessageDigest

plugins {
    java
}

val botVersion = providers.gradleProperty("botVersion").get()
val robocodeVersion = providers.gradleProperty("robocodeVersion").get()
val robocodeSetupSha256 = providers.gradleProperty("robocodeSetupSha256").get()
val toolchainJava = providers.gradleProperty("javaVersion").get().toInt()

dependencies {
    // Compile against the published API; at runtime the official installation's libs are used.
    compileOnly("net.sf.robocode:robocode.api:$robocodeVersion")
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
    description = "Copies the freshly built robot and team jars into the Robocode installation."
    dependsOn(installRobocode, ":bot:robotJar", ":bot:teamJar")
    val robotsDir = robocodeHome.dir("robots").asFile
    from(project(":bot").layout.buildDirectory.dir("robots"))
    into(robotsDir)
    doFirst {
        robotsDir.listFiles { file -> file.name.startsWith("cz.libors.") }?.forEach { it.delete() }
        robotsDir.resolve("robot.database").delete()
    }
}

val skeletonCheck = tasks.register<JavaExec>("skeletonCheck") {
    group = "verification"
    description = "Runs headless battles against the sample bots and fails on crashes, skipped turns or losses."
    dependsOn(deployBots)
    mainClass = "cz.libors.harness.SkeletonCheck"
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
    args(
        robocodeHome.asFile.absolutePath,
        "cz.libors.Ogi $botVersion",
        "cz.libors.OgiTeam $botVersion",
        layout.buildDirectory.file("skeleton-check.json").get().asFile.absolutePath,
    )
}
