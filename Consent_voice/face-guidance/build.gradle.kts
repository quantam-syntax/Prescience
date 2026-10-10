plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.atreides.faceguidance"
    compileSdk { version = release(37) }
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 26 }
}

dependencies {
    api(libs.androidx.camera.core)
    implementation(libs.google.mlkit.face.detection)
}
