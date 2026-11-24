plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// 可选：固定签名。设置下面 4 个环境变量后，每次构建的 APK 签名一致，
// 可以覆盖安装而不丢数据（CI 里由 GitHub Secrets 提供）。
val signStoreFile: String? = System.getenv("BK_KEYSTORE_FILE")
val signStorePassword: String? = System.getenv("BK_KEYSTORE_PASSWORD")
val signKeyAlias: String? = System.getenv("BK_KEY_ALIAS")
val signKeyPassword: String? = System.getenv("BK_KEY_PASSWORD")
val hasFixedSigning = !signStoreFile.isNullOrBlank() && file(signStoreFile!!).exists()

android {
    namespace = "com.shaowenfu.bookkeeper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shaowenfu.bookkeeper"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m0"
    }

    signingConfigs {
        if (hasFixedSigning) {
            create("fixed") {
                storeFile = file(signStoreFile!!)
                storePassword = signStorePassword
                keyAlias = signKeyAlias
                keyPassword = signKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasFixedSigning) signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (hasFixedSigning) signingConfigs.getByName("fixed")
            else signingConfigs.getByName("debug")
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
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    implementation("androidx.work:work-runtime-ktx:2.10.1")
}
