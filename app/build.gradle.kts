import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/*
 * Assinatura:
 *  - Se existir um keystore.properties na raiz (ou as variaveis de ambiente
 *    KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD), o release
 *    e assinado com ele.
 *  - Caso contrario, o release e assinado com a chave de debug, para que o APK
 *    continue instalavel via sideload (Downloader / adb) sem nenhum segredo.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun secret(key: String, envName: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(envName)

val releaseStoreFile = secret("storeFile", "KEYSTORE_FILE")
val hasReleaseKey = releaseStoreFile != null && rootProject.file(releaseStoreFile).exists()

android {
    namespace = "com.exemplo.fotostv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.exemplo.fotostv"
        minSdk = 22
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = secret("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "KEY_ALIAS")
                keyPassword = secret("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.exifinterface)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
