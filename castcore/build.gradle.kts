plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

// Pure Kotlin cast logic (IPTV, subtitles, web video detection) shared by the
// phone app. No Android dependencies, so everything here is unit tested on the JVM.
dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
}
