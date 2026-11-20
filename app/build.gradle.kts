import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
}

fun loadKeyValueFile(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    val result = mutableMapOf<String, String>()
    file.forEachLine { rawLine ->
        val line = rawLine.trim()
        if (line.isNotEmpty() && !line.startsWith("#") && line.contains("=")) {
            val idx = line.indexOf('=')
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
            result[key] = value
        }
    }
    return result
}

val dotEnvMap: Map<String, String> = loadKeyValueFile(rootProject.file(".env"))
val localProps: Properties = Properties().apply {
    val lpFile = rootProject.file("local.properties")
    if (lpFile.exists()) {
        lpFile.inputStream().use { load(it) }
    }
}

fun envOrDefault(key: String, default: String): String = System.getenv(key)?.takeIf { it.isNotBlank() }
    ?: dotEnvMap[key]?.takeIf { it.isNotBlank() }
    ?: localProps.getProperty(key)?.takeIf { it.isNotBlank() }
    ?: default

fun escapeBuildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.binarydev.startergame"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.binarydev.startergame"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        val admobAppId = envOrDefault("ADMOB_APP_ID", "ca-app-pub-3940256099942544~3347511713")
        manifestPlaceholders["ADMOB_APP_ID"] = admobAppId

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GAME_ID", escapeBuildConfigString(envOrDefault("GAME_ID", "sample-puzzle-v1")))
        buildConfigField("String", "PRIVACY_POLICY_URL", escapeBuildConfigString(envOrDefault("PRIVACY_POLICY_URL", "https://example.com/privacy")))
        buildConfigField("String", "BACKEND_API_KEY", escapeBuildConfigString(envOrDefault("BACKEND_API_KEY", "")))
        buildConfigField("int", "BACKEND_TIMEOUT_MS", envOrDefault("BACKEND_TIMEOUT_MS", "5000").toIntOrNull()?.toString() ?: "5000")
        buildConfigField("boolean", "USE_SERVER_REWARD_VERIFICATION", envOrDefault("USE_SERVER_REWARD_VERIFICATION", "false").toBooleanStrictOrNull()?.toString() ?: "false")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", escapeBuildConfigString(envOrDefault("GOOGLE_WEB_CLIENT_ID", "demo-web-client-id.apps.googleusercontent.com")))
        buildConfigField("String", "ADMOB_APP_ID", escapeBuildConfigString(admobAppId))
        buildConfigField("String", "ADMOB_REWARDED_AD_UNIT_ID", escapeBuildConfigString(envOrDefault("ADMOB_REWARDED_AD_UNIT_ID", "ca-app-pub-3940256099942544/5224354917")))
        buildConfigField("String", "ADMOB_INTERSTITIAL_AD_UNIT_ID", escapeBuildConfigString(envOrDefault("ADMOB_INTERSTITIAL_AD_UNIT_ID", "ca-app-pub-3940256099942544/1033173712")))
        buildConfigField("String", "ADMOB_BANNER_AD_UNIT_ID", escapeBuildConfigString(envOrDefault("ADMOB_BANNER_AD_UNIT_ID", "ca-app-pub-3940256099942544/6300978111")))
        buildConfigField("String", "BILLING_REMOVE_ADS_SUB_PRODUCT_ID", escapeBuildConfigString(envOrDefault("BILLING_REMOVE_ADS_SUB_PRODUCT_ID", "sub_remove_ads_monthly")))
        buildConfigField("String", "BILLING_REMOVE_ADS_BASE_PLAN_ID", escapeBuildConfigString(envOrDefault("BILLING_REMOVE_ADS_BASE_PLAN_ID", "monthly-autorenew")))
        buildConfigField("String", "FCM_PROJECT_ID", escapeBuildConfigString(envOrDefault("FCM_PROJECT_ID", "")))
        buildConfigField("String", "FCM_APP_ID", escapeBuildConfigString(envOrDefault("FCM_APP_ID", "")))
        buildConfigField("String", "FCM_SENDER_ID", escapeBuildConfigString(envOrDefault("FCM_SENDER_ID", "")))
        buildConfigField("String", "FCM_API_KEY", escapeBuildConfigString(envOrDefault("FCM_API_KEY", "")))
    }

    flavorDimensions += "mode"
    productFlavors {
        create("demo") {
            dimension = "mode"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("String", "STARTER_MODE", "\"demo\"")
            buildConfigField("String", "BACKEND_BASE_URL", escapeBuildConfigString(envOrDefault("DEMO_BACKEND_BASE_URL", "https://demo.local.invalid")))
        }
        create("integrated") {
            dimension = "mode"
            buildConfigField("String", "STARTER_MODE", "\"integrated\"")
            buildConfigField("String", "BACKEND_BASE_URL", escapeBuildConfigString(envOrDefault("BACKEND_BASE_URL", "http://10.0.2.2:8080")))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    implementation(libs.play.services.ads)
    implementation(libs.firebase.messaging)
    implementation(libs.play.billing.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
