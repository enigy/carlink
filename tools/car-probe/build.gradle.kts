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
        versionCode = 2
        versionName = "0.2"
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
        // No META-INF/version-control-info.textproto in either: AGP writes the checkout's git state into a
        // non-debuggable build, so a clone (its commit) and a worktree (NO_VALID_GIT_FOUND) built different APKs
        // (the Mac, 10 Nov). The app's debuggable debug build has none.
        debug {
            isDebuggable = false
            vcsInfo {
                include = false
            }
        }
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            vcsInfo {
                include = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // No dependency-info block in the APK. AGP adds one to a non-debuggable build, encrypted afresh each time, so two
    // clean builds of the probe never matched byte for byte (the Mac, 10 Nov). The app's debuggable debug build has none.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // The car service's stubs, compile-time only (as :app).
    useLibrary("android.car")
}
