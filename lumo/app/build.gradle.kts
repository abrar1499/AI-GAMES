plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase is configured by dropping google-services.json into app/.
// Without it the app still builds and shows a setup screen on launch.
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "com.lumo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lumo.app"
        minSdk = 26
        targetSdk = 35
        // Bump both for every release; the app offers the update when versionCode goes up.
        versionCode = 8
        versionName = "1.7"
        buildConfigField("boolean", "HAS_FIREBASE", hasFirebaseConfig.toString())
        // Where the in-app updater looks for new versions (GitHub Releases).
        buildConfigField("String", "UPDATE_REPO", "\"abrar1499/AI-GAMES\"")
        // Optional TURN relay for calls between strict mobile networks. Set these in
        // gradle.properties or with -P on the command line (see README).
        fun prop(name: String) = (project.findProperty(name) as String?)?.trim().orEmpty()
        buildConfigField("String", "TURN_URLS", "\"${prop("lumo.turnUrls")}\"")
        buildConfigField("String", "TURN_USER", "\"${prop("lumo.turnUser")}\"")
        buildConfigField("String", "TURN_PASS", "\"${prop("lumo.turnPass")}\"")
    }

    // A fixed key, so every build has the same SHA-1/SHA-256 that you register
    // once in Firebase (phone login needs it). Use your own key for Play Store.
    signingConfigs {
        create("lumo") {
            storeFile = file("lumo.keystore")
            storePassword = "lumo1234"
            keyAlias = "lumo"
            keyPassword = "lumo1234"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("lumo")
        }
        debug {
            signingConfig = signingConfigs.getByName("lumo")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/") }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // Keep the gesture model uncompressed so MediaPipe can map it straight from the APK.
    androidResources { noCompress += "task" }
    // Compress native libraries inside the APK: a much smaller download for updates on slow internet.
    packaging { jniLibs.useLegacyPackaging = true }

    // One small APK per phone type, plus a universal one that runs everywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")

    // Google's WebRTC engine (prebuilt, kept up to date by Stream).
    implementation("io.getstream:stream-webrtc-android:1.3.10")

    // Hand gestures to the front camera (thumbs up, OK, wave...), recognized on the phone.
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // Video player for Watch Together.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
