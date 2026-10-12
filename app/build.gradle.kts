import java.net.URI
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Arquitecturas para las que se compila. arm64-v8a cubre casi todos los móviles
// actuales; añade "armeabi-v7a" si quieres soportar móviles de 32 bits.
val abis = listOf("arm64-v8a")

android {
    namespace = "com.emuneo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.emuneo.app"
        minSdk = 26 // Android 8.0, necesario para AAudio
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += abis }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Firmado con la clave de depuración para poder instalarlo sin más pasos.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    packaging {
        // El core se abre con dlopen desde nativeLibraryDir, así que debe extraerse al instalar.
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Descarga el core FinalBurn Neo compilado por libretro (si aún no está) y lo
// deja en src/main/jniLibs/<abi>/libfbneo_libretro_android.so para empaquetarlo en el APK.
val downloadCore by tasks.registering {
    val jniLibs = layout.projectDirectory.dir("src/main/jniLibs").asFile
    doLast {
        abis.forEach { abi ->
            val target = File(jniLibs, "$abi/libfbneo_libretro_android.so")
            if (target.exists()) return@forEach
            target.parentFile.mkdirs()
            val url = "https://buildbot.libretro.com/nightly/android/latest/$abi/fbneo_libretro_android.so.zip"
            logger.lifecycle("Descargando el core FinalBurn Neo para $abi…")
            ZipInputStream(URI(url).toURL().openStream()).use { zip ->
                generateSequence { zip.nextEntry }.first { it.name.endsWith(".so") }
                target.outputStream().use { zip.copyTo(it) }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadCore) }
