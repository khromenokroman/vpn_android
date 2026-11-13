import java.io.File

plugins {
    alias(libs.plugins.android.application)
}

// Подпись release из окружения; без переменных собирается неподписанный APK
val pomaKeystorePath = System.getenv("POMAVPN_KEYSTORE")
val pomaKeystorePassword = System.getenv("POMAVPN_KEYSTORE_PASSWORD")
val pomaKeyAlias = System.getenv("POMAVPN_KEY_ALIAS")
val pomaSigningReady = !pomaKeystorePath.isNullOrBlank() &&
    !pomaKeystorePassword.isNullOrBlank() && !pomaKeyAlias.isNullOrBlank() &&
    File(pomaKeystorePath).isFile

android {
    namespace = "com.example.vpn"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.khromenokroman.pomavpn"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf(
                "arm64-v8a",
                "armeabi-v7a",
                "x86",
                "x86_64"
            )
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
            }
        }
    }

    signingConfigs {
        create("release") {
            if (pomaSigningReady) {
                storeFile = file(pomaKeystorePath)
                storePassword = pomaKeystorePassword
                keyAlias = pomaKeyAlias
                keyPassword = pomaKeystorePassword
            }
        }
    }
    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (pomaSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}