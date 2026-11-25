plugins {
    id("com.android.application")
}

android {
    namespace = "app.pausebreak"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.pauseamoment"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // The signing key comes from GitHub secrets, so every build can update the
    // installed app without wiping its settings.
    val ks = System.getenv("PAUSE_KEYSTORE")
    signingConfigs {
        if (ks != null && file(ks).exists()) {
            create("pause") {
                storeFile = file(ks)
                storePassword = System.getenv("PAUSE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PAUSE_KEY_ALIAS") ?: "pause"
                keyPassword = System.getenv("PAUSE_KEY_PASSWORD") ?: System.getenv("PAUSE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("pause") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
