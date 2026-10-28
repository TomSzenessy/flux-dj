plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release identity comes from the tag (v1.2.3) or FLUX_VERSION; a local build stays 1.0.0.
val versionTag = System.getenv("GITHUB_REF_NAME") ?: System.getenv("FLUX_VERSION") ?: ""
val versionMatch = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""").find(versionTag.trim())
val vMajor = versionMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
val vMinor = versionMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
val vPatch = versionMatch?.groupValues?.get(3)?.toIntOrNull() ?: 0
val vCode = vMajor * 10_000 + vMinor * 100 + vPatch

// A stable signing identity is supplied by the release environment; without it the release
// build stays unsigned (and CI will not publish it) instead of shipping a per-run debug key.
val releaseStore = System.getenv("FLUX_KEYSTORE")
val releaseStorePassword = System.getenv("FLUX_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("FLUX_KEY_ALIAS")
val releaseKeyPassword = System.getenv("FLUX_KEY_PASSWORD")
val hasReleaseSigning = !releaseStore.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "app.flux.dj"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.flux.dj"
        minSdk = 26
        targetSdk = 35
        versionCode = vCode
        versionName = "$vMajor.$vMinor.$vPatch"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
