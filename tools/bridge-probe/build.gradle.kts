plugins {
    id("com.android.application")
}

// Bench only: a stand-in for "another app" that drives the USB bridge's API 6 access path. Debug-signed with the
// repo's committed debug key and never released; the gate doesn't build it (./gradlew :bridge-probe:assembleDebug).
android {
    namespace = "com.enigy.bridgeprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.enigy.bridgeprobe"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystores/debug.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":bridge-api"))
}
