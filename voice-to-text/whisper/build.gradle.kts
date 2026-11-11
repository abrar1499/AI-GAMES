plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aigames.voicewhisper"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 23
        ndk {
            // Real phones only; add "x86_64" to run on an emulator.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                // Use a whisper.cpp checkout that is already on disk instead of downloading it:
                //   ./gradlew assembleDebug -PwhisperCppDir=/path/to/whisper.cpp
                (project.findProperty("whisperCppDir") as String?)?.let {
                    arguments += "-DFETCHCONTENT_SOURCE_DIR_WHISPERCPP=$it"
                }
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
