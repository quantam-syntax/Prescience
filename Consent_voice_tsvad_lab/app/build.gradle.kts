plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.atreides.consentvoice.tsvadlab"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.atreides.consentvoice.tsvadlab"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-lab"
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
