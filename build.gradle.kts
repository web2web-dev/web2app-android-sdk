// web2app SDK (скелет, WEB-434). Тонкая Android-библиотека, MIT. minSdk 24.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
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
    // maven-publish: единственный release-вариант + sources для потребителей (JitPack).
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Единственная core-зависимость: чтение Google Play Install Referrer (Android-ветка identify).
    implementation("com.android.installreferrer:installreferrer:2.2")
    // EncryptedSharedPreferences для guid-персиста (client-held ключ).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // WEB-525: Chrome Custom Tabs — открытие веб-пейвола в openWebPaywall (обратный флоу).
    implementation("androidx.browser:browser:1.7.0")
    // MMP-SDK (AppsFlyer/Adjust) — НЕ зависимость SDK: интегратор передаёт deep_link_value
    // из своего MMP-callback в Web2App.identify(...). См. README (POC-1).
    testImplementation("junit:junit:4.13.2")
}

// JitPack/Maven-публикация. group = com.github.web2web-dev (JitPack-конвенция по GitHub-орг).
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.web2web-dev"
                artifactId = "web2app-android-sdk"
                version = "0.1.0"
            }
        }
    }
}
