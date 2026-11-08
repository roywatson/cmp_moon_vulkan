plugins {
    // Declared once here so every module shares one classloader for these plugins.
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.spotless)
}

// Kotlin style is ktlint's, configured by .editorconfig. Check with `./gradlew spotlessCheck`,
// fix with `./gradlew spotlessApply`. C++ style is clang-format's, configured by .clang-format.
spotless {
    kotlin {
        target("**/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint().setEditorConfigPath(rootProject.file(".editorconfig"))
            // Spotless passes its own default code style, which would override .editorconfig.
            .editorConfigOverride(mapOf("ktlint_code_style" to "intellij_idea"))
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint().setEditorConfigPath(rootProject.file(".editorconfig"))
            // Spotless passes its own default code style, which would override .editorconfig.
            .editorConfigOverride(mapOf("ktlint_code_style" to "intellij_idea"))
    }
}
