plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val uploadStore = System.getenv("CLOCKROVE_UPLOAD_KEYSTORE")
val uploadStorePassword = System.getenv("CLOCKROVE_UPLOAD_STORE_PASSWORD")
val uploadAlias = System.getenv("CLOCKROVE_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = System.getenv("CLOCKROVE_UPLOAD_KEY_PASSWORD")
val signingReady = listOf(uploadStore, uploadStorePassword, uploadAlias, uploadKeyPassword)
    .all { !it.isNullOrBlank() }

gradle.taskGraph.whenReady {
    if (allTasks.any { it.name == "bundleRelease" || it.name == "assembleRelease" }) {
        check(signingReady) { "Release signing is not configured; supply CLOCKROVE_UPLOAD_* environment variables" }
    }
}

android {
    namespace = "io.github.centerionware.clockrove"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "io.github.centerionware.clockrove"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 24
        targetSdk = 36
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    signingConfigs {
        if (signingReady) {
            create("upload") {
                storeFile = file(uploadStore!!)
                storePassword = uploadStorePassword
                keyAlias = uploadAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (signingReady) signingConfig = signingConfigs.getByName("upload")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

// AGP 9 host-test packaging consumes Flutter's copied assets. Declare the producer
// explicitly so Gradle does not package stale/missing assets or reject the graph.
tasks.configureEach {
    val variant = Regex("^package(.+)UnitTestForUnitTest$").matchEntire(name)?.groupValues?.get(1)
    if (variant != null) dependsOn("copyFlutterAssets$variant")
}
