plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.firefiles.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.firefiles.bridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 101
        versionName = "0.1.1"
    }
}

dependencies {
    implementation("com.hierynomus:smbj:0.13.0")
}
