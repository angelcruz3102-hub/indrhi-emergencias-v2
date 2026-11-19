plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.indrhi.emergencias"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.indrhi.emergencias"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3.1"
    }

    signingConfigs {
        create("indrhiFixed") {
            val ksFile = file("keystore.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "Indrhi2026!"
                keyAlias = System.getenv("KEY_ALIAS") ?: "indrhi_key"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "Indrhi2026!"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (file("keystore.jks").exists()) {
                signingConfig = signingConfigs.getByName("indrhiFixed")
            }
        }
        release {
            isMinifyEnabled = false
            if (file("keystore.jks").exists()) {
                signingConfig = signingConfigs.getByName("indrhiFixed")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging")
}
