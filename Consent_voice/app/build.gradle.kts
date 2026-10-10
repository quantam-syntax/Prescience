plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.atreides.consentvoice"
    ndkVersion = "27.2.12479018"
    compileSdk {
        version = release(37)
    }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.atreides.consentvoice"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += "-DQAIRT_SDK_ROOT=${rootProject.projectDir}/.tools/qairt/2.50.40.260831"
            }
        }
    }

    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    androidResources { noCompress += listOf("onnx", "dlc") }
    packaging { jniLibs.useLegacyPackaging = true }
}

dependencies {
    implementation(project(":voice-consent"))
    implementation(project(":face-guidance"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
