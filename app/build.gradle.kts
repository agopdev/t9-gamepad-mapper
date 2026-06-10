plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.t9mapper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.t9mapper"
        minSdk = 29          // Android 10 mínimo (uinput estable desde API 29)
        targetSdk = 34       // Android 14, compatible con Android 12
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NDK: nombre de la librería nativa
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c11"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }

        ndk {
            // ARM64 para DuoQin F22 Pro; añadir armeabi-v7a si necesitas 32-bit
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // El helper ejecutable se empaqueta junto a las .so
    // Android lo copia en nativeLibraryDir al instalar
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
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
        }
        jniLibs {
            // No comprimir el helper para que pueda extraerse directamente del APK
            keepDebugSymbols += "**/libuinput_helper.so"
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)

    // Compose BOM — versiones sincronizadas
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)

    // Navegación
    implementation(libs.androidx.navigation.compose)

    // Room — base de datos de perfiles
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore — configuración y preferencias
    implementation(libs.androidx.datastore)

    // Corrutinas
    implementation(libs.kotlinx.coroutines.android)

    // Serialización JSON (para exportar/importar perfiles)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation("androidx.compose.ui:ui-tooling")
}
