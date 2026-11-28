pluginManagement {
    repositories {
        google()
        // Google-hosted mirror reduces transient Maven Central throttling in CI.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // sherpa-onnx artifacts are published on JitPack; resolve them before Maven Central.
        maven { url = uri("https://jitpack.io") }
        // Keep the mirror before Maven Central so dependencies resolve without 429 bursts.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        mavenCentral()
    }
}

rootProject.name = "ShraddhaAI"
include(":app")
