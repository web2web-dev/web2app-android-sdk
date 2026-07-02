// web2app SDK (скелет, WEB-434). Тонкая Android-библиотека, MIT. minSdk 24.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.web2app.sdk"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Единственная core-зависимость: чтение Google Play Install Referrer (Android-ветка identify).
    implementation("com.android.installreferrer:installreferrer:2.2")
    // EncryptedSharedPreferences для guid-персиста (client-held ключ).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // MMP-SDK (AppsFlyer/Adjust) — НЕ зависимость SDK: интегратор передаёт deep_link_value
    // из своего MMP-callback в Web2App.identify(...). См. README (POC-1).
    testImplementation("junit:junit:4.13.2")
}
