plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.deivid22srk.chatfloat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deivid22srk.chatfloat"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Supabase config via BuildConfig
        buildConfigField("String", "SUPABASE_URL", "\"https://dbvmkochemjmeyookgsu.supabase.co\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRidm1rb2NoZW1qbWV5b29rZ3N1Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzk1OTA3MjIsImV4cCI6MjA5NTE2NjcyMn0.oAYv4hqQfnltl5sDmSTRwlkBfBeapCfxj7xaXyDqt78\"")
    }

    // Force-downgrade androidx.browser so we don't need compileSdk 36
    // (Supabase Auth pulls in browser:1.10.0 which requires API 36)
    configurations.all {
        resolutionStrategy {
            force("androidx.browser:browser:1.8.0")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore/chat-release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "chatfloat123"
            keyAlias = System.getenv("KEY_ALIAS") ?: "chat-key"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "chatfloat123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // debug default
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "META-INF/*.kotlin_module"
        }
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
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.core)
    implementation(libs.ktor.cio)
    debugImplementation(libs.androidx.ui.tooling)
}
