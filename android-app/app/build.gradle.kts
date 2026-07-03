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
        versionCode = 120
        versionName = "0.1.20"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.hierynomus:smbj:0.13.0")
}
