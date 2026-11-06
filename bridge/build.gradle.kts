import java.util.Properties

plugins {
    id("com.android.application")
}

// Same signing credentials as :app (keystore.properties, never committed).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().also { props ->
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { props.load(it) }
}

android {
    namespace = "com.enigy.carlink2.bridge"
    compileSdk = 36

    defaultConfig {
        // The AAOS fixed USB handler package — see BridgeContract. Sideload only, never Play.
        applicationId = "android.car.usb.handler"
        minSdk = 29
        targetSdk = 36
        versionCode = 8
        versionName = "1.0.7"
    }

    signingConfigs {
        // One committed debug key (keystores/debug.p12, password "android"; not a secret), the same as :app's.
        getByName("debug") {
            storeFile = rootProject.file("keystores/debug.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // Small app; keeping it unshrunk keeps the AIDL stub and provider names obvious.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":bridge-api"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
