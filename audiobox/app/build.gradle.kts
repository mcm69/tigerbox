// audiobox: offline playlist player for the repurposed tigerbox TOUCH PLUS.
// Android 5.1 (API 22) box with 512 MB RAM: plain Views, no AndroidX, no Kotlin
// runtime, so the APK and its memory footprint stay small. See ../../APP-SPEC.md.
plugins {
    id("com.android.application")
}

android {
    namespace = "dev.audiobox"
    compileSdk = 36
    buildToolsVersion = "36.1.0" // the one installed; avoids an automatic download

    defaultConfig {
        applicationId = "dev.audiobox"
        minSdk = 22
        //noinspection ExpiredTargetSdkVersion - the box runs API 22; targeting it
        // keeps install-time permissions and the old storage model.
        targetSdk = 22
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Targeting API 22 is deliberate (see above).
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub in local unit tests; use the real one there.
    testImplementation("org.json:json:20250517")
}
