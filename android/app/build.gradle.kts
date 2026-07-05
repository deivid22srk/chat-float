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
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Telegram Bot API config via BuildConfig
        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"7594927232:AAFh5T_zZvPtqvoGpyGVO-Kd8uGVDKdp3LE\"")
        buildConfigField("String", "TELEGRAM_GROUP_ID", "\"-1004384994615\"")
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
    implementation(libs.ktor.core)
    implementation(libs.ktor.cio)
    debugImplementation(libs.androidx.ui.tooling)
}
