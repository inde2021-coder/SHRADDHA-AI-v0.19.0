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
        // Keep the mirror before Maven Central so dependencies resolve without 429 bursts.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        mavenCentral()
        // sherpa-onnx Android Kotlin/JNI runtime (kept optional model weights are downloaded separately).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ShraddhaAI"
include(":app")
