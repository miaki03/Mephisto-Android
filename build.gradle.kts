// AGP 9 起 Kotlin 支持内置，不再需要 `org.jetbrains.kotlin.android` 插件
// （见 https://kotl.in/gradle/agp-built-in-kotlin）。Compose 由 buildFeatures 打开。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
