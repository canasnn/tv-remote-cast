plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ruterika.receiver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ruterika.receiver"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    // Release signing: the key lives outside the repo; its path and passwords come from
    // ~/.gradle/gradle.properties (TVREMOTE_*). Without them the release build is unsigned.
    val releaseStore = (project.findProperty("TVREMOTE_STORE_FILE") as String?)?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = project.property("TVREMOTE_STORE_PASSWORD") as String
            keyAlias = project.property("TVREMOTE_KEY_ALIAS") as String
            keyPassword = project.property("TVREMOTE_KEY_PASSWORD") as String
        }
    }
    buildTypes {
        release {
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.java.websocket)

    implementation(libs.receiver.media3.exoplayer)
    implementation(libs.receiver.media3.exoplayer.hls)
    implementation(libs.receiver.media3.exoplayer.dash)
    implementation(libs.receiver.media3.ui)
    // rtsp:// streams
    implementation(libs.receiver.media3.exoplayer.rtsp)
    // FFmpeg audio decoder: AC3, E-AC3, DTS, TrueHD… on TVs without those decoders.
    implementation(libs.jellyfin.media3.ffmpeg)

    testImplementation(libs.junit)
}
