plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing config, read from keystore.properties (gitignored — never commit it).
// If the file isn't present (e.g. a fresh clone without the keystore), release builds
// simply stay unsigned instead of failing, so `assembleXxxDebug` always still works.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = java.util.Properties()
val hasSigningConfig = keystorePropertiesFile.exists()
if (hasSigningConfig) {
    keystoreProperties.load(java.io.FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.example.vray"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.vray"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Two variants from one codebase:
    //  - "modern": arm64-v8a + armeabi-v7a, minSdk 24 (Android 7+), full polling rate.
    //  - "legacy": armeabi-v7a only (32-bit, older/budget chips), minSdk 21 (Android 5+),
    //              slower background polling to go easier on weak CPUs/battery.
    flavorDimensions += "device"
    productFlavors {
        create("modern") {
            dimension = "device"
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            buildConfigField("boolean", "IS_LEGACY", "false")
        }
        create("legacy") {
            dimension = "device"
            minSdk = 21
            versionNameSuffix = "-legacy"
            ndk { abiFilters += listOf("armeabi-v7a") }
            buildConfigField("boolean", "IS_LEGACY", "true")
        }
    }

    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    // Xray-core Android bindings (Go -> AAR), from 2dust/AndroidLibXrayLite releases.
    implementation(files("libs/libv2ray.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
