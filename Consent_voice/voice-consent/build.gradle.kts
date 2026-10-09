plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.atreides.voiceconsent"
    compileSdk {
        version = release(37)
    }
    buildToolsVersion = "36.0.0"

    defaultConfig { minSdk = 26 }
}

dependencies {
    api(files("libs/sherpa-onnx.aar"))
    implementation(libs.androidx.core.ktx)
}
