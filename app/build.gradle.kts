import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── Подпись APK ───────────────────────────────────────────────────────────────
// Локально: файл keystore.properties рядом с settings.gradle.kts (см. keystore.properties.example).
// GitHub Actions: секреты ANDROID_KEYSTORE_PATH / _PASSWORD / ANDROID_KEY_ALIAS / _PASSWORD (см. README).
// Если ключа нет — APK подписывается отладочным ключом, чтобы сборка всё равно дала устанавливаемый файл.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String, env: String): String? =
    keystoreProps.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(env)?.trim()?.takeIf { it.isNotEmpty() }

val storePath = secret("storeFile", "ANDROID_KEYSTORE_PATH")
val storePass = secret("storePassword", "ANDROID_KEYSTORE_PASSWORD")
val keyAliasValue = secret("keyAlias", "ANDROID_KEY_ALIAS")
val keyPass = secret("keyPassword", "ANDROID_KEY_PASSWORD")
val hasKeystore = storePath != null && storePass != null && keyAliasValue != null && keyPass != null &&
    rootProject.file(storePath).exists()

android {
    namespace = "app.hlz"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.hlz"
        minSdk = 30
        targetSdk = 34
        versionCode = 1        // при каждом обновлении увеличивай на 1
        versionName = "1.0"    // и меняй номер версии (он должен совпадать с тегом релиза на GitHub: v1.0, v1.1 ...)
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(storePath!!)
                storePassword = storePass
                keyAlias = keyAliasValue
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // без своего ключа подписываем отладочным, иначе GitHub выдаст APK, который нельзя установить
            signingConfig = if (hasKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }

    // Проверки lint не должны ломать сборку APK (на GitHub они всё равно показываются отчётом).
    // Хочешь строгий режим — поставь checkReleaseBuilds = true и abortOnError = true.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
