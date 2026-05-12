import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun gitShortSha(): String {
    return try {
        val out = ByteArrayOutputStream()
        exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            standardOutput = out
            isIgnoreExitValue = true
        }
        val sha = out.toString().trim()
        sha.ifEmpty { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
}

// Monotonic versionCode based on commit count. CI overrides via -PversionCode/-PversionName
// so the value baked into the APK matches the rolling GitHub Release the in-app updater
// reads. Local dev builds fall back to commit count + "dev-<sha>" name.
fun gitCommitCount(): Int {
    return try {
        val out = ByteArrayOutputStream()
        exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            standardOutput = out
            isIgnoreExitValue = true
        }
        out.toString().trim().toIntOrNull() ?: 1
    } catch (_: Exception) {
        1
    }
}

val agorVersionCode: Int = (project.findProperty("versionCode") as? String)?.toIntOrNull()
    ?: gitCommitCount()
val agorVersionName: String = (project.findProperty("versionName") as? String)
    ?: "dev-${gitShortSha()}"
val skipWhisperBundle: Boolean = System.getenv("SKIP_WHISPER")?.let {
    it.equals("1") || it.equals("true", ignoreCase = true)
} ?: false
val androidRootDir = rootProject.layout.projectDirectory
val whisperCppDir = layout.projectDirectory.dir("src/main/cpp/whisper.cpp")
val syncWhisperScript = androidRootDir.file("scripts/sync-whisper.sh")

val syncWhisperCpp = tasks.register("syncWhisperCpp") {
    group = "agor"
    description = "Vendors whisper.cpp native code for on-device transcription unless SKIP_WHISPER=1."
    outputs.dir(whisperCppDir)
    onlyIf {
        !skipWhisperBundle && !whisperCppDir.asFile.resolve("CMakeLists.txt").exists()
    }
    doLast {
        exec {
            commandLine("bash", syncWhisperScript.asFile.absolutePath)
        }
    }
}

android {
    namespace = "live.agor.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "live.agor.app"
        minSdk = 28
        targetSdk = 35
        versionCode = agorVersionCode
        versionName = agorVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GIT_SHA", "\"${gitShortSha()}\"")
        buildConfigField("String", "VERSION_NAME", "\"$agorVersionName\"")
        buildConfigField("int", "VERSION_CODE_FIELD", "$agorVersionCode")
        // Source of truth for in-app updates. CI keeps the rolling tag pointing at the
        // newest debug build; the updater fetches releases/tags/<this-tag> on launch.
        buildConfigField(
            "String",
            "UPDATE_RELEASE_URL",
            "\"https://api.github.com/repos/Code-Fixxers/agor/releases/tags/android-latest\"",
        )
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://github.com/Code-Fixxers/agor/releases/download/android-latest/agor-android-latest.json\"",
        )
        buildConfigField(
            "String",
            "UPDATE_APK_URL",
            "\"https://github.com/Code-Fixxers/agor/releases/download/android-latest/agor-android-debug.apk\"",
        )

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DAGOR_SKIP_WHISPER=${if (skipWhisperBundle) "ON" else "OFF"}",
                )
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Stable debug signing certificate.
    //
    // Without this, every CI run generates a fresh ~/.android/debug.keystore,
    // so every APK is signed with a different cert and the device refuses
    // `adb install -r` with INSTALL_FAILED_UPDATE_INCOMPATIBLE — the user has
    // to uninstall first. Committing a project-local keystore (debug-only,
    // not sensitive) makes builds reproducibly signature-stable.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

if (!skipWhisperBundle) {
    tasks.named("preBuild").configure {
        dependsOn(syncWhisperCpp)
    }
    tasks.configureEach {
        if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
            dependsOn(syncWhisperCpp)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.onnxruntime.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    implementation(libs.socket.io)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)

    implementation(libs.highlights)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
