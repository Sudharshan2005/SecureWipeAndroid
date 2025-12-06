plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.securedatawiper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.securedatawiper"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // For file operations
    implementation("commons-io:commons-io:2.15.1")

    // For encryption/secure deletion
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // For PDF generation
    implementation("com.itextpdf:itext7-core:7.2.5")
//    implementation("com.itextpdf:bouncycastle-adapter:7.2.5")

    // For file operations in Downloads folder
    implementation("androidx.documentfile:documentfile:1.0.1")
}