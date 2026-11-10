plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nowel.agendavip"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.nowel.agendavip"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.1.0"

        // Ejecuta comprobaciones locales en el emulador sin añadir bibliotecas de pruebas.
        testInstrumentationRunner = "com.nowel.agendavip.verificacion.VerificacionAgenda"

        // La primera voz local se prepara para el POCO de 64 bits y el emulador de 64 bits.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    // Android Studio construirá el puente de Whisper cuando Nowel compile.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// El lector está incluido en la app y reconoce el QR con la cámara del teléfono.
// No envía la imagen a un servicio externo ni necesita una aplicación de escaneo aparte.
dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
