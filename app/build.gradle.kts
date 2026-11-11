plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ua.org.electros.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.org.electros.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 243
        versionName = "2.4.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseKeystorePath = System.getenv("ELECTROS_KEYSTORE")?.takeIf { it.isNotBlank() }
    val releaseStorePassword = System.getenv("ELECTROS_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("ELECTROS_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("ELECTROS_KEY_PASSWORD")
    val hasReleaseSigning = releaseKeystorePath != null &&
        file(releaseKeystorePath).exists() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
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
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Avoid AGP 8.7.3 K2 FIR UAST analyzer internal crash on unit-test sources under Kotlin 2.0
        checkTestSources = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    ksp(libs.androidx.room.compiler)

    // WorkManager & Background Service
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)

    // Network & HTML Parsing
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

