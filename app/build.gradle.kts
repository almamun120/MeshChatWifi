plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.meshchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.meshchat"
        minSdk = 26
        targetSdk = 35
        // CI passes -PversionCode=<run number> -PversionName=<tag without v>; local builds use the defaults.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0"
    }

    // ---- Release signing. NEVER commit the keystore or passwords.
    // Values come from environment variables (GitHub Actions secrets) or ~/.gradle/gradle.properties:
    //   MESHCHAT_KEYSTORE_FILE, MESHCHAT_KEYSTORE_PASSWORD, MESHCHAT_KEY_ALIAS, MESHCHAT_KEY_PASSWORD
    fun signingValue(name: String): String? = System.getenv(name) ?: (project.findProperty(name) as String?)
    val ksFile = signingValue("MESHCHAT_KEYSTORE_FILE")?.let { file(it) }
    val hasSigning = ksFile != null && ksFile.exists() &&
        signingValue("MESHCHAT_KEYSTORE_PASSWORD") != null &&
        signingValue("MESHCHAT_KEY_ALIAS") != null &&
        signingValue("MESHCHAT_KEY_PASSWORD") != null

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = ksFile
                storePassword = signingValue("MESHCHAT_KEYSTORE_PASSWORD")
                keyAlias = signingValue("MESHCHAT_KEY_ALIAS")
                keyPassword = signingValue("MESHCHAT_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without keystore info the release APK is simply left unsigned (local builds);
            // the CI release workflow refuses to publish unless it is signed.
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false   // lint runs as its own CI step and uploads a report
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WebRTC for audio / video calls. Runs with no STUN/TURN server: both phones are in one Wi-Fi Direct group.
    // Verify this version exists on Maven Central before building (adds roughly 10-15 MB per ABI to the APK).
    implementation("io.getstream:stream-webrtc-android:1.3.7")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
