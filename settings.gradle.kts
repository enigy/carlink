pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "carlink3"
include(":app")

// Carlink2's USB bridge (package android.car.usb.handler): holds the adapter's USB permission
// silently on every attach, relays the raw OCBM bulk stream to :app over a socket, and hosts the
// cluster icon ContentProvider the Templates Host reads. Sideloaded; :app stays Play-shippable.
include(":bridge")
include(":bridge-api")

// Not carried over from OCBM's host/CarlinkAndroid: :projection (Raspberry Pi target),
// :car-system-stubs (system APIs a third-party app cannot hold), :usbhandler (OCBM's untested
// proof of the permission grant — superseded by :bridge). See NOTICE.md.
