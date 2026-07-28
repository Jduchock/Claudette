import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Secrets are read from local.properties (git-ignored, never committed) and baked into
// BuildConfig at build time — so you set keys once in Android Studio, not on the phone.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun secret(key: String): String = (localProps.getProperty(key) ?: "").replace("\"", "")

android {
    namespace = "com.duchock.claudette"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.duchock.claudette"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.2"
        vectorDrawables { useSupportLibrary = true }

        // Baked from local.properties at build time (blank if not set there).
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${secret("ANTHROPIC_API_KEY")}\"")
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"${secret("ELEVENLABS_API_KEY")}\"")
        buildConfigField("String", "ELEVENLABS_VOICE_ID", "\"${secret("ELEVENLABS_VOICE_ID")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Networking (Anthropic + ElevenLabs REST)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Secure key storage -- Anthropic / ElevenLabs keys, ref threat S1
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // openWakeWord on-device inference (Phase 1b)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
}
