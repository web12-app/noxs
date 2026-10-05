/*
 * Noxs — Debian-optimized Linux userspace environment for Android.
 * Original implementation. Termux was used only as an architectural reference.
 *
 * Module graph:
 *   :app                — Android application (UI, services, native PTY)
 *   :terminal-emulator  — clean-room VT/xterm terminal engine (pure Kotlin)
 *   :terminal-view      — Android terminal rendering/input View
 *   :noxs-shared        — shared security core, models and utilities
 */
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle auto-provision the JDK 17 toolchain required by the JVM
    // modules (kotlin jvmToolchain(17)) when the host JDK differs.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}


dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "noxs"

include(":app")
include(":terminal-emulator")
include(":terminal-view")
include(":noxs-shared")
