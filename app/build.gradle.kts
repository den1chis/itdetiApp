import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

fun escapedLocalProperty(name: String): String {
    return localProperties.getProperty(name, "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}

fun buildConfigString(name: String): String = "\"${escapedLocalProperty(name)}\""

android {
    namespace = "com.itdeti.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itdeti.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "ITDETI_EMAIL", buildConfigString("ITDETI_EMAIL"))
        buildConfigField("String", "ITDETI_PASSWORD", buildConfigString("ITDETI_PASSWORD"))

        // Runtime strings are used by NotificationService. R.string.* is an Int resource ID,
        // therefore Kotlin must obtain the actual value through Context.getString().
        resValue("string", "itdeti_email", escapedLocalProperty("ITDETI_EMAIL"))
        resValue("string", "itdeti_password", escapedLocalProperty("ITDETI_PASSWORD"))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
