plugins {
    id("com.android.application")
}

// Truck research only, never released: "Carlink Probe" logs what the head unit offers for the LIVI-inspired work (vehicle
// data, the car's GPS, video while parked, volume, the display, the cameras). Its own package, so it installs from a
// USB stick next to Play's Carlink. Not debuggable in either build type (the Android builder assembles debug only):
// signed with the repo's committed debug key, which no bridge or app pin trusts. Not in the gate.
android {
    namespace = "com.enigy.carprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.enigy.carprobe"
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

    buildTypes {
        debug {
            isDebuggable = false
        }
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The car service's stubs, compile-time only (as :app).
    useLibrary("android.car")
}
