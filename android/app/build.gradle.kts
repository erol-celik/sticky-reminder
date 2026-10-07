import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Kalıcı imza anahtarı bilgisi git'e girmeyen android/keystore.properties dosyasındadır.
// Google'daki Android OAuth istemcisi bu anahtarın SHA-1 değerine bağlıdır; anahtar değişirse giriş kırılır.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.sticky.reminder"
    // Güncel Compose kütüphaneleri compileSdk 37 ister; targetSdk ayrı tutulur.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sticky.reminder"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("sticky") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Debug ve release aynı anahtarla imzalanır; böylece ikisi de aynı OAuth istemcisiyle çalışır.
        val signing = signingConfigs.findByName("sticky") ?: signingConfigs.getByName("debug")
        debug {
            signingConfig = signing
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signing
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // Drive senkronu: Google yetkilendirme ve "internet gelince çalış" kuyruğu.
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    // Ana ekran widget'ı.
    implementation("androidx.glance:glance-appwidget:1.2.0")

    testImplementation("junit:junit:4.13.2")
    // Android'in birim testlerdeki org.json'ı boş taslaktır; gerçek sürüm test sınıfı yoluna girer.
    testImplementation("org.json:json:20260814")
}
