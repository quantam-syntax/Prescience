plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

val voiceProject = projectDir.resolve("../../../Consent_voice")
val voiceApp = voiceProject.resolve("app")
val qairtSdkRoot = voiceProject.resolve(".tools/qairt/2.50.40.260831")
val hasQairtSdk = qairtSdkRoot.resolve("include/QAIRT/QairtCpp/QairtApi.hpp").isFile

android {
    namespace = "com.atreides.consentvoice"
    compileSdk { version = release(37) }
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        if (hasQairtSdk) externalNativeBuild {
            cmake { arguments += "-DQAIRT_SDK_ROOT=$qairtSdkRoot" }
        }
    }
    if (hasQairtSdk) {
        ndkVersion = "27.2.12479018"
        externalNativeBuild { cmake { path = voiceApp.resolve("src/main/cpp/CMakeLists.txt") } }
    }
    buildFeatures { compose = true }
    androidResources { noCompress += listOf("onnx", "dlc") }
    packaging { jniLibs.useLegacyPackaging = true }
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addStaticSourceDirectory(voiceApp.resolve("src/main/java").path)
        variant.sources.res?.addStaticSourceDirectory(voiceApp.resolve("src/main/res").path)
        variant.sources.assets?.addStaticSourceDirectory(voiceApp.resolve("src/main/assets").path)
        variant.sources.jniLibs?.addStaticSourceDirectory(voiceApp.resolve("src/main/jniLibs").path)
    }
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
}
