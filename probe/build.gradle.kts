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
    "SandboxProbe", "SpinProbe", "MessageProbe",
)
/** A team of message probes (engine-model G-23). */
val teamName = "MessageTeam"
val teamMembers = List(3) { "$probePackage.MessageProbe" }

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
    description = "Generates the Robocode .properties files of the probe robots and the .team file of their team."
    val outDir = generatedProbeFiles
    val packageDir = probePackage.replace('.', '/')
    val names = probeNames
    val version = botVersion
    val author = botAuthor
    val rcVersion = robocodeVersion
    val team = teamName
    val members = teamMembers.joinToString(",")
    inputs.property("names", names)
    inputs.property("members", members)
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
        dir.resolve("$team.team").writeText(
            """
            team.members=$members
            team.version=$version
            team.author.name=$author
            team.description=Engine probes for team messages, not a competitor
            team.webpage=
            robocode.version=$rcVersion
            """.trimIndent() + "\n"
        )
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
