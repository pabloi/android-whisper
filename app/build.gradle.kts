plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.pabloi.whisper"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.pabloi.whisper"
        // 29 = Android 10; keeps MediaCodec + scoped-storage APIs simple.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // S24 Ultra is arm64-only; don't waste APK size on other ABIs.
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Debug keystore is auto-generated; release config is user-supplied
        // via ~/.gradle/gradle.properties (see README).
        create("release") {
            val storeFileProp = (project.findProperty("WHISPR_KEYSTORE") as String?)
            if (!storeFileProp.isNullOrBlank()) {
                storeFile = file(storeFileProp)
                storePassword = project.findProperty("WHISPR_KEYSTORE_PASSWORD") as String?
                keyAlias = project.findProperty("WHISPR_KEY_ALIAS") as String?
                keyPassword = project.findProperty("WHISPR_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if ((project.findProperty("WHISPR_KEYSTORE") as String?)?.isNotBlank() == true) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*"
        )
        // QNN's fastrpc DSP loader (`libcdsprpc.so`) reads the per-DSP `*Skel.so`
        // files via real filesystem paths — it cannot dlopen a library that
        // lives mmap'd inside an APK. Force extraction at install time so the
        // skel files land in `/data/app/<pkg>/lib/arm64/`.
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.onnxruntime.qnn)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
