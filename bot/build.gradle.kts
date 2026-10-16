import java.util.zip.ZipFile

plugins {
    java
}

val botVersion = providers.gradleProperty("botVersion").get()
val botAuthor = providers.gradleProperty("botAuthor").get()
val robocodeVersion = providers.gradleProperty("robocodeVersion").get()
val toolchainJava = providers.gradleProperty("javaVersion").get().toInt()

val robotPackage = "cz.libors"
val robotName = "Ogi"
val teamName = "OgiTeam"
val teamSize = 5
// Ogi with the gun log for the offline gun bench; local and harness use only, never in a rumble jar.
val gunLogName = "OgiGunLog"

dependencies {
    compileOnly("net.sf.robocode:robocode.api:$robocodeVersion")

    testImplementation("net.sf.robocode:robocode.api:$robocodeVersion")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(toolchainJava)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Rumble clients run Java 17 or newer (Saguaro 1.0 and Nullstride 2.3.3 ship Java 17 bytecode).
    options.release = 17
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
}

val generatedRobotFiles = layout.buildDirectory.dir("generated/robot")

val generateRobotFiles = tasks.register("generateRobotFiles") {
    description = "Generates the Robocode .properties and .team files."
    val outDir = generatedRobotFiles
    val packageDir = robotPackage.replace('.', '/')
    val robotClass = "$robotPackage.$robotName"
    val version = botVersion
    val author = botAuthor
    val rcVersion = robocodeVersion
    val members = List(teamSize) { robotClass }.joinToString(",")
    inputs.property("version", version)
    inputs.property("author", author)
    inputs.property("robocodeVersion", rcVersion)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().dir(packageDir).asFile
        // Files of robots no longer generated would otherwise reach the jars.
        dir.deleteRecursively()
        dir.mkdirs()
        dir.resolve("$robotName.properties").writeText(
            """
            robot.classname=$robotClass
            robot.version=$version
            robot.author.name=$author
            robot.description=Team-first bot (walking skeleton)
            robot.webpage=
            robocode.version=$rcVersion
            robot.java.source.included=false
            robot.include.source=false
            """.trimIndent() + "\n"
        )
        dir.resolve("$gunLogName.properties").writeText(
            """
            robot.classname=$robotPackage.$gunLogName
            robot.version=$version
            robot.author.name=$author
            robot.description=Ogi with the gun log for the offline gun bench; not for a rumble
            robot.webpage=
            robocode.version=$rcVersion
            robot.java.source.included=false
            robot.include.source=false
            """.trimIndent() + "\n"
        )
        dir.resolve("$teamName.team").writeText(
            """
            team.members=$members
            team.version=$version
            team.author.name=$author
            team.description=Team-first bot (walking skeleton)
            team.webpage=
            robocode.version=$rcVersion
            """.trimIndent() + "\n"
        )
    }
}

fun Jar.robocodeJar(fileName: String, robotsAttribute: String) {
    group = "build"
    archiveFileName = fileName
    destinationDirectory = layout.buildDirectory.dir("robots")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(sourceSets.main.map { it.output })
    manifest {
        attributes("robots" to robotsAttribute)
    }
}

val gunLogClasses = "${robotPackage.replace('.', '/')}/$gunLogName*"

/** The gun-log robot must never reach a rumble: a jar that holds it fails the build. */
fun requireNoGunLog(jar: File) {
    ZipFile(jar).use { zip ->
        val leaked = zip.entries().asSequence().map { it.name }.filter { it.contains(gunLogName) }.toList()
        check(leaked.isEmpty()) { "${jar.name} contains the gun-log robot: $leaked" }
    }
}

val robotJar = tasks.register<Jar>("robotJar") {
    description = "Packages the robot jar entered in the 1v1 and melee rumbles."
    robocodeJar("$robotPackage.${robotName}_$botVersion.jar", "$robotPackage.$robotName")
    exclude(gunLogClasses)
    from(generateRobotFiles) {
        include("**/$robotName.properties")
    }
    doLast { requireNoGunLog(archiveFile.get().asFile) }
}

val teamJar = tasks.register<Jar>("teamJar") {
    description = "Packages the team jar entered in the team rumble."
    robocodeJar(
        "$robotPackage.${teamName}_$botVersion.jar",
        List(teamSize) { "$robotPackage.$robotName" }.joinToString(","),
    )
    exclude(gunLogClasses)
    from(generateRobotFiles) {
        exclude("**/$gunLogName.properties")
    }
    doLast { requireNoGunLog(archiveFile.get().asFile) }
}

val gunLogJar = tasks.register<Jar>("gunLogJar") {
    description = "Packages Ogi with the gun log for the offline gun bench; never for a rumble."
    robocodeJar("$robotPackage.${gunLogName}_$botVersion.jar", "$robotPackage.$gunLogName")
    from(generateRobotFiles) {
        include("**/$gunLogName.properties")
    }
}

tasks.assemble {
    dependsOn(robotJar, teamJar, gunLogJar)
}
