plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.canasnn.tvremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.canasnn.tvremote"
        minSdk = 24
        targetSdk = 36
        versionCode = 21
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        debug {
            // -PappIdSuffix=.dev installs a second copy next to another build.
            (project.findProperty("appIdSuffix") as String?)?.let { applicationIdSuffix = it }
        }
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
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }



    packaging {
        resources {
            excludes.add("/META-INF/{AL2.0,LGPL2.1}")
            excludes.add("META-INF/DEPENDENCIES")
            excludes.add("META-INF/LICENSE")
            excludes.add("META-INF/LICENSE.txt")
            excludes.add("META-INF/NOTICE")
            excludes.add("META-INF/NOTICE.txt")
            excludes.add("META-INF/*.kotlin_module")
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose BOM + UI
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation Compose
    implementation(libs.androidx.navigation.compose)

    // ViewModel + Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coil (image loading for Compose)
    implementation(libs.coil.compose)

    // Timber
    implementation(libs.timber)

    // Splash Screen
    implementation(libs.androidx.core.splashscreen)

    // ConnectSDK (for Chromecast/DLNA discovery)
    implementation(project(mapOf("path" to ":ConnectSDK")))

    // Shared phone <-> TV receiver protocol
    implementation(project(":protocol"))
    implementation(project(":castcore"))
    implementation(libs.androidx.webkit)

    // Downloads: HLS/DASH to MP4 (Media3 Transformer)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    // Phone (in-app) player
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    // rtsp:// streams in the phone player
    implementation(libs.androidx.media3.exoplayer.rtsp)

    // Protobuf (Android TV pairing protocol)
    implementation(libs.protobuf.java)

    // BouncyCastle (SSL/TLS for pairing)
    implementation(libs.bouncycastle)

    // WebSocket
    implementation(libs.java.websocket)

    // RxJava (required by ConnectSDK)
    implementation(libs.rxandroid)
    implementation(libs.rxjava)

    // OkHttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Retrofit + Gson
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.gson)

    // Media (for notification / cast)
    implementation(libs.androidx.media)

    // NanoHTTPD (local media server)
    implementation(libs.nanohttpd)
    implementation(libs.nanohttpd.webserver)

    // Ktor (optional HTTP client)
    implementation(libs.ktor.client.core)

    // SDP (screen-size utilities, used by ConnectSDK)
    implementation(libs.sdp.android)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.foundation)


    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
