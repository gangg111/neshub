plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/** game.conf value, with local.conf (a private build's own id / key, not in git) on top. */
fun conf(name: String): String? = listOf("../local.conf", "../game.conf").map { file(it) }.filter { it.exists() }
    .firstNotNullOfOrNull { f -> f.readLines().firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.trim() }

android {
    namespace = "com.dklab.nes"
    // 34, not 35: the native Termux aapt2 cannot parse android-35 resources.
    compileSdk = 34

    defaultConfig {
        applicationId = conf("appid") ?: "com.dklab.neshub.open"
        minSdk = 29
        targetSdk = 34
        versionCode = 81
        versionName = "2.14.1"
        // This phone is arm64 only, and the emulator core .so is built for it alone.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // Own release key (Play Protect flags APKs signed with the public debug key).
        // The password lives outside the project, in ~/.android/contraforce-release.pass.
        create("release") {
            // key=<name> in game.conf -> ~/.android/<name>-release.jks (alias <name>),
            // password in ~/.android/<name>-release.pass (outside the project).
            val home = System.getProperty("user.home")
            val key = conf("key") ?: error("game.conf: key= missing")
            val pass = file("$home/.android/$key-release.pass").readText().trim()
            storeFile = file("$home/.android/$key-release.jks")
            storePassword = pass
            keyAlias = key
            keyPassword = pass
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets {
        getByName("main") {
            // libgbcplayer.so is compiled outside Gradle by aarch64-linux-android-clang
            // and dropped here by the native agent.
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            // The .so is produced by a standalone clang, not by the NDK toolchain that
            // AGP would otherwise invoke to strip it. Leave it exactly as built.
            keepDebugSymbols += listOf("**/*.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // No Compose, no data binding: a SurfaceView plus findViewById is smaller and
    // much cheaper per frame at 60 fps.
    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = false
    }
}

// Deliberately EMPTY. Not one third-party artifact: no ads, no analytics, no crash
// reporter, no Firebase, no Play Services, and no AndroidX either - androidx.core
// injects a <permission>/<uses-permission> pair of its own into the merged manifest,
// and this APK is meant to declare zero permissions of any kind. The app needs only
// android.app.Activity, SurfaceView, AudioTrack and JNI, all of them in the platform.
// If a later stage genuinely needs AndroidX, libs.versions.toml already has
// `core-ktx` and `activity-ktx` declared - wire one in here and re-check
// `aapt2 dump permissions` afterwards.
dependencies {
}
