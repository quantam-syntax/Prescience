plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.atreides.voiceconsent"
    compileSdk = 37

    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
