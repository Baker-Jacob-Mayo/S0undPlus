// Top-level build: declares plugins once so submodules can apply them without versions.
// AGP 9 ships built-in Kotlin, so there is intentionally no `kotlin-android` plugin here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
