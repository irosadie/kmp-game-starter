plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

// Lint & format (padanan Biome/ESLint di dunia JS):
//   ./gradlew ktlintFormat   -> auto-format (seperti `biome format --write`)
//   ./gradlew ktlintCheck    -> cek style
//   ./gradlew detekt         -> static analysis (seperti ESLint)
//   ./gradlew lint-all       -> ktlintCheck + detekt
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline = file("detekt-baseline.xml")
        source.setFrom(
            files(
                "src/main/kotlin",
                "src/commonMain/kotlin",
                "src/test/kotlin",
                "src/commonTest/kotlin"
            )
        )
    }

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.1")
        android.set(true)
        ignoreFailures.set(false)
    }
}

tasks.register("lint-all") {
    group = "verification"
    description = "Menjalankan ktlintCheck dan detekt pada semua modul"
    dependsOn(subprojects.map { "${it.path}:ktlintCheck" }, subprojects.map { "${it.path}:detekt" })
}
