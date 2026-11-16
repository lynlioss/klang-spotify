plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose"); id("org.jetbrains.kotlin.plugin.serialization") }
if (System.getenv("PLUGIN_REQUIRE_RELEASE_SIGNING").toBoolean() && System.getenv("PLUGIN_KEYSTORE_FILE").isNullOrBlank()) {
    throw GradleException("Release signing is required. Configure PLUGIN_KEYSTORE_FILE and PLUGIN_KEYSTORE_PASSWORD.")
}
android {
    namespace = "io.github.lynlioss.klang.spotify"
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "io.github.lynlioss.klang.spotify"
        minSdk = 36
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs { create("release") {
        storeFile = file(System.getenv("PLUGIN_KEYSTORE_FILE") ?: "../release.jks")
        storePassword = System.getenv("PLUGIN_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("PLUGIN_KEY_ALIAS") ?: "klang-spotify"
        keyPassword = System.getenv("PLUGIN_KEYSTORE_PASSWORD")
    } }
    buildTypes { release {
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        signingConfig = signingConfigs.getByName(if (System.getenv("PLUGIN_KEYSTORE_FILE") != null) "release" else "debug")
    } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs.useLegacyPackaging = true; jniLibs.keepDebugSymbols += "**/*.so" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":spotify-core"))
    implementation("io.ktor:ktor-client-core:3.3.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    implementation("com.google.crypto.tink:tink-android:1.18.0")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.material:material-icons-core")
    implementation("com.materialkolor:material-kolor:4.1.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.1")
}
