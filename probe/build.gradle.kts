plugins {
    java
}

val botVersion = providers.gradleProperty("botVersion").get()
val botAuthor = providers.gradleProperty("botAuthor").get()
val robocodeVersion = providers.gradleProperty("robocodeVersion").get()
val toolchainJava = providers.gradleProperty("javaVersion").get().toInt()

val probePackage = "cz.libors.probe"
val probeNames = listOf(
    "RandomProbe", "PassiveProbe", "WatchingProbe", "MathRandomProbe", "UtilsRandomProbe", "UtilsDrawProbe", "DataProbe",
    "SandboxProbe", "SpinProbe", "MessageProbe", "SkipProbe", "FireAssistProbe",
)

/** A probe team: its members, the first is the leader, and what it is for. */
data class ProbeTeam(val name: String, val members: List<String>, val description: String)

val probeTeams = listOf(
    ProbeTeam("MessageTeam", List(3) { "$probePackage.MessageProbe" },
        "Engine probes for team messages, not a competitor"),
    ProbeTeam("SkipTeam", listOf("$probePackage.SkipProbe", "$probePackage.MessageProbe"),
        "Engine probes for skipped turns, not a competitor"),
)

dependencies {
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

val generatedProbeFiles = layout.buildDirectory.dir("generated/probe")

val generateProbeFiles = tasks.register("generateProbeFiles") {
    description = "Generates the Robocode .properties files of the probe robots and the .team files of their teams."
    val outDir = generatedProbeFiles
    val packageDir = probePackage.replace('.', '/')
    val names = probeNames
    val version = botVersion
    val author = botAuthor
    val rcVersion = robocodeVersion
    val teams = probeTeams.map { Triple(it.name, it.members.joinToString(","), it.description) }
    inputs.property("names", names)
    inputs.property("teams", teams.map { it.toList() })
    inputs.property("version", version)
    inputs.property("author", author)
    inputs.property("robocodeVersion", rcVersion)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().dir(packageDir).asFile
        dir.mkdirs()
        for (name in names) {
            dir.resolve("$name.properties").writeText(
                """
                robot.classname=$probePackage.$name
                robot.version=$version
                robot.author.name=$author
                robot.description=Engine probe for recordings, not a competitor
                robot.webpage=
                robocode.version=$rcVersion
                robot.java.source.included=false
                robot.include.source=false
                """.trimIndent() + "\n"
            )
        }
        for ((team, members, about) in teams) {
            dir.resolve("$team.team").writeText(
                """
                team.members=$members
                team.version=$version
                team.author.name=$author
                team.description=$about
                team.webpage=
                robocode.version=$rcVersion
                """.trimIndent() + "\n"
            )
        }
    }
}

val probeJar = tasks.register<Jar>("probeJar") {
    group = "build"
    description = "Packages the probe robots for engine recordings. They are deployed locally only, never to a rumble."
    archiveFileName = "${probePackage}_$botVersion.jar"
    destinationDirectory = layout.buildDirectory.dir("robots")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(sourceSets.main.map { it.output })
    from(generateProbeFiles)
    manifest {
        attributes("robots" to probeNames.joinToString(",") { "$probePackage.$it" })
    }
}

tasks.assemble {
    dependsOn(probeJar)
}
