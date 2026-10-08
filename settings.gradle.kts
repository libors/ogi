plugins {
    // Downloads a matching JDK when no installed one satisfies a toolchain request.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ogi"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("bot", "harness")
