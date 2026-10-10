import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 has built-in Kotlin; only the Compose compiler plugin is applied explicitly.
    alias(libs.plugins.kotlin.compose)
}

/** Resolves a setting from -P, the environment, or local.properties (in that order). */
fun setting(property: String, env: String): String {
    providers.gradleProperty(property).orNull?.let { return it }
    providers.environmentVariable(env).orNull?.let { return it }
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        val props = Properties().apply { file.inputStream().use(::load) }
        props.getProperty(property)?.let { return it }
    }
    return ""
}

android {
    namespace = "com.s0undplus.tv"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.s0undplus.tv"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        // Your own Twitch application's client ID (https://dev.twitch.tv/console). See README.
        buildConfigField("String", "TWITCH_CLIENT_ID", "\"${setting("twitchClientId", "TWITCH_CLIENT_ID")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // Sideload-friendly: optimized (non-debuggable) but signed with the debug key so it installs as-is.
            // Compose is far smoother on TV hardware without the debuggable overhead.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("com.s0undplus:twitch-core") // substituted by the included build in settings.gradle.kts

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)
}
