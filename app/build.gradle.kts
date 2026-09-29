plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.offlinetranslator"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.offlinetranslator"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndkVersion = "29.0.13113456"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir("$buildDir/generated/assets")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")

    // Pure-Java SentencePiece: no Rust, no JNI, no JNA.
    implementation("io.github.eix128:sentencepiece4j:1.0.2")

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

/*
 * Vosk Android requires a uuid file inside the model directory.
 *
 * The downloaded Vosk model ZIP does not necessarily contain this file.
 * Generate it automatically into the build assets so the original
 * downloaded model remains untouched.
 */
tasks.register("generateVoskModelUuid") {
    val generatedModelDir =
        file("$buildDir/generated/assets/vosk-model-small-hi-0.22")

    val uuidFile =
        file("$generatedModelDir/uuid")

    doLast {
        generatedModelDir.mkdirs()

        if (!uuidFile.exists()) {
            uuidFile.writeText(
                "linguabridge-vosk-hi-small-0.22"
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn("generateVoskModelUuid")
}