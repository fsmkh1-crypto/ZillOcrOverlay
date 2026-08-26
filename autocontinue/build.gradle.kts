plugins {
    id("com.android.application")
}

android {
    namespace = "com.fsmkh1.chatgptautocontinue"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fsmkh1.chatgptautocontinue"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "2.1.0"
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
}
