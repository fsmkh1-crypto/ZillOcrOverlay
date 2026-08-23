import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.co.zillocr.overlay"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.co.zillocr.overlay"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Keep Stage 1 on compileSdk 36 / AGP 8.13.2. Core 1.18+ moved to API 36.1,
    // and Core 1.19 requires API 37 / AGP 9.1+.
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.12.4")

    // Bundled Japanese OCR model: no first-run model download required.
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
}
