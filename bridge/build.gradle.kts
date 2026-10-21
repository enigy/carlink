import java.util.Properties

plugins {
    id("com.android.application")
}

// Same signing credentials as :app (keystore.properties, never committed).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().also { props ->
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { props.load(it) }
}

// SHA-256 fingerprints of display-app signing certificates the bridge accepts, comma-separated
// (colons optional). -Pcarlink3.callerCerts=... (or the CARLINK3_CALLER_CERTS repo variable in CI)
// replaces the release list; left empty, the committed defaults below apply. Fingerprints are public.
val releaseCallerCerts =
    listOf(
        // Play app signing key (Play Console › Test and release › App integrity), what Play installs.
        "C3:FC:31:9F:65:9D:C5:C0:38:0F:06:B4:48:93:49:C1:1C:27:AF:44:27:40:9D:B3:61:13:5C:64:EB:E5:61:D5",
        // Upload key (CN=Carlink Native): CI release builds, the sideloaded release APK.
        "07:36:E0:0E:B0:B1:69:2F:EF:B7:0F:80:3B:68:9D:76:BA:07:84:BA:39:EB:B3:26:DF:C5:F8:18:E6:6D:6F:A1",
    ).joinToString(",")

// keystores/debug.p12 (CN=Carlink3 Debug). Its private key is in this public repo, so only a DEBUG
// bridge accepts it: a release bridge pinning it would let anyone sign a com.enigy.carlink3 it trusts.
val debugCallerCert = "72:70:70:9B:4C:F5:CD:39:C6:AE:87:76:81:1A:00:22:FD:17:57:83:00:4C:CD:CB:4F:FD:7A:88:73:37:AB:4A"
val callerCerts = (findProperty("carlink3.callerCerts") as String?).orEmpty().trim().ifEmpty { releaseCallerCerts }

android {
    namespace = "com.enigy.carlink2.bridge"
    compileSdk = 36

    defaultConfig {
        // The AAOS fixed USB handler package — see BridgeContract. Sideload only, never Play.
        applicationId = "android.car.usb.handler"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.4"

        buildConfigField("String", "CALLER_CERTS", "\"$callerCerts\"")
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
        debug {
            // Debug bridges also accept debug builds of Carlink3 (see debugCallerCert).
            buildConfigField("String", "CALLER_CERTS", "\"$callerCerts,$debugCallerCert\"")
        }
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
}

dependencies {
    implementation(project(":bridge-api"))
}
