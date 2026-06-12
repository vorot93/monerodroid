plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sevendeuce.monerodroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sevendeuce.monerodroid"
        minSdk = 29
        targetSdk = 35
        versionCode = 16
        versionName = "1.0.16"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only filter ABIs for release builds - debug allows all for emulator testing
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    lint {
        // Disable NullSafeMutableLiveData check due to Kotlin 2.0 compatibility bug
        // See: https://issuetracker.google.com/issues/XXXXX
        disable += "NullSafeMutableLiveData"
        // Don't fail release builds on lint errors
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(libs.pgpainless.sop)

    // NetCipher for Orbot/Tor integration
    implementation("info.guardianproject.netcipher:netcipher:2.1.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
