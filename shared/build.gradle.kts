plugins {
    // AGP 9 already pins Kotlin on the classpath, so we deliberately do NOT
    // re-declare a version here — Gradle would refuse with
    // "plugin already on the classpath with an unknown version".
    id("org.jetbrains.kotlin.jvm")
}

// Pure-Kotlin contracts shared between the phone (:app) and watch (:wear)
// surfaces. Keep this module Android-free so both can depend on it without
// pulling in transitive AndroidX gunk: the phone app already has a heavy
// graph of compose/datastore/native deps, and the watch APK we ship to the
// user wants to be as small as possible.

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {
    jvmToolchain(17)
}
