plugins {
    id("com.android.application")
}

android {
    namespace = "com.sanepo.unitcheck"
    compileSdk {
        version = release(37)
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.sanepo.unitcheck"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
}
