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

val appVersionName = setting("appVersionName", "APP_VERSION_NAME").ifEmpty { "1.0.0" }
// Android compares versionCode (not versionName) when installing over an existing app, and refuses a lower one.
// GITHUB_RUN_NUMBER is counted per workflow (Build and Release would collide), so derive the code from the clock
// instead: minutes since 2026-10-01 only ever increases, across every workflow and local build, and is far above the
// small codes (1..16) earlier CI builds used. Override with -PappVersionCode if you need a specific one.
val appVersionCode = setting("appVersionCode", "APP_VERSION_CODE").toIntOrNull()
    ?: (System.currentTimeMillis() / 60_000L - 29_850_000L).toInt()
val signingKeystorePath = setting("signingKeystorePath", "SIGNING_KEYSTORE_PATH")
val updateRepo = setting("updateRepo", "UPDATE_REPO").ifEmpty { "Baker-Jacob-Mayo/S0undPlus" }

android {
    namespace = "com.s0undplus.tv"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.s0undplus.tv"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        // Your own Twitch application's client ID (https://dev.twitch.tv/console). See README.
        buildConfigField("String", "TWITCH_CLIENT_ID", "\"${setting("twitchClientId", "TWITCH_CLIENT_ID")}\"")
        // GitHub "owner/repo" whose latest release the in-app updater checks.
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        // True when signed with your own stable key: only then can an update install over the previous build.
        buildConfigField("boolean", "STABLE_SIGNING", "${signingKeystorePath.isNotEmpty()}")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (signingKeystorePath.isNotEmpty()) {
            create("release") {
                storeFile = file(signingKeystorePath)
                storePassword = setting("signingStorePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = setting("signingKeyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = setting("signingKeyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Optimized (non-debuggable) build: Compose is far smoother on TV hardware without debug overhead.
            // With a keystore configured it is signed with YOUR stable key, so new builds update old ones in place.
            // Without one it falls back to the debug key, which is different on every CI run: such APKs can be
            // installed, but not over each other (uninstall first). See README "Signing".
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Lint runs in CI for TV-specific manifest checks; findings are reported without failing the build.
        abortOnError = false
        textReport = true
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
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.tvprovider)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)
}
