import java.util.Properties
import java.io.File
import java.security.MessageDigest
import java.net.URL
import java.net.HttpURLConnection

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val firebaseLocalProperties = Properties().apply {
    val configFile = rootProject.file("local.properties")
    if (configFile.exists()) configFile.inputStream().use { load(it) }
}
fun firebaseConfigValue(name: String): String =
    (System.getenv(name) ?: firebaseLocalProperties.getProperty(name) ?: "")
        .replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "in.shraddha.ai"
    compileSdk = 35
    defaultConfig {
        applicationId = "in.shraddha.ai"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 22
        versionName = "0.19.2"
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebaseConfigValue("FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${firebaseConfigValue("FIREBASE_APP_ID")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebaseConfigValue("FIREBASE_PROJECT_ID")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${firebaseConfigValue("FIREBASE_SENDER_ID")}\"")
        buildConfigField("String", "FIREBASE_STORAGE_BUCKET", "\"${firebaseConfigValue("FIREBASE_STORAGE_BUCKET")}\"")
    }
    buildFeatures { buildConfig = true; compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += "onnx" }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.guava:listenablefuture:1.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}


data class BiometricModelAsset(val fileName: String, val url: String, val sha256: String)
val biometricModelAssets = listOf(
    BiometricModelAsset("sface.onnx", "https://huggingface.co/opencv/opencv_zoo/resolve/39a1451/models/face_recognition_sface/face_recognition_sface_2021dec.onnx", "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"),
    BiometricModelAsset("ecapa-any-length.onnx", "https://huggingface.co/kenrunio/transcriber-speaker-onnx/resolve/4467ef1/ecapa-any-length.onnx", "700b63064ee98981186f59f247d28e56f7f6ab265d8b50ebb450db5a8ce21dc7")
)
fun biometricSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
val downloadBiometricModels by tasks.registering {
    val outputDir = layout.projectDirectory.dir("src/main/assets/biometric-models")
    outputs.files(biometricModelAssets.map { outputDir.file(it.fileName) })
    outputs.upToDateWhen { false }
    doLast {
        val directory = outputDir.asFile
        directory.mkdirs()
        biometricModelAssets.forEach { model ->
            val target = File(directory, model.fileName)
            if (target.isFile && biometricSha256(target) == model.sha256) {
                logger.lifecycle("Verified cached biometric model: ${model.fileName}")
            } else {
                target.delete()
                val temporary = File(directory, model.fileName + ".part")
                logger.lifecycle("Downloading ${model.fileName}; large model download may take several minutes")
                var downloaded = false
                var lastError: Exception? = null
                for (attempt in 1..3) {
                    try {
                        temporary.delete()
                        val connection = URL(model.url).openConnection() as HttpURLConnection
                        try {
                            connection.connectTimeout = 30_000
                            connection.readTimeout = 180_000
                            connection.instanceFollowRedirects = true
                            connection.setRequestProperty("User-Agent", "ShraddhaAI-Build/0.19.2")
                            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                                "HTTP ${connection.responseCode} while downloading ${model.fileName}"
                            }
                            connection.inputStream.use { input ->
                                temporary.outputStream().buffered().use { output -> input.copyTo(output, 1024 * 1024) }
                            }
                        } finally {
                            connection.disconnect()
                        }
                        check(temporary.isFile && temporary.length() > 1024L * 1024L) {
                            "Downloaded model is empty or unexpectedly small: ${model.fileName}"
                        }
                        val actual = biometricSha256(temporary)
                        check(actual == model.sha256) { "Model SHA-256 mismatch for ${model.fileName}: $actual" }
                        if (target.exists()) check(target.delete()) { "Could not replace invalid cached model: ${model.fileName}" }
                        check(temporary.renameTo(target)) { "Could not install ${model.fileName}" }
                        downloaded = true
                        lastError = null
                        break
                    } catch (error: Exception) {
                        lastError = error
                        temporary.delete()
                        logger.warn("Model download attempt $attempt/3 failed for ${model.fileName}: ${error.message}")
                        if (attempt < 3) Thread.sleep(2_000L * attempt)
                    }
                }
                check(downloaded) { "Could not download and verify ${model.fileName} after 3 attempts: ${lastError?.message}" }
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(downloadBiometricModels) }
