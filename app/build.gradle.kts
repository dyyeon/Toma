import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

// 1. local.properties에서 API 키 읽기
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

fun resolveOpenAiApiKey(): String {
    localProperties.getProperty("OPENAI_API_KEY")
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    System.getenv("OPENAI_API_KEY")
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    val sharedPropertyFiles = listOf(
        file("C:/00_Projects/TomaAI/local.properties"),
        file("C:/00_Projects/99_Arch/TomaAI/local.properties"),
        file("C:/00_Projects/Toma_Iparsing/local.properties")
    )

    sharedPropertyFiles.forEach { candidate ->
        if (!candidate.exists()) return@forEach

        val sharedProperties = Properties()
        candidate.inputStream().use { sharedProperties.load(it) }
        sharedProperties.getProperty("OPENAI_API_KEY")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }

    return ""
}

val resolvedOpenAiApiKey = resolveOpenAiApiKey()
val resolvedFoodSafetyApiKey = localProperties.getProperty("FOOD_SAFETY_API_KEY")
    ?: System.getenv("FOOD_SAFETY_API_KEY")
    ?: ""

val resolvedNaverClientId = localProperties.getProperty("NAVER_CLIENT_ID")
    ?: System.getenv("NAVER_CLIENT_ID")
    ?: ""

val resolvedNaverClientSecret = localProperties.getProperty("NAVER_CLIENT_SECRET")
    ?: System.getenv("NAVER_CLIENT_SECRET")
    ?: ""

android {
    namespace = "com.capstone.toma"
    
    // 최신 라이브러리 요구사항에 따라 36으로 설정
    compileSdk = 36

    defaultConfig {
        applicationId = "com.capstone.toma"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "OPENAI_API_KEY", "\"$resolvedOpenAiApiKey\"")
        buildConfigField("String", "FOOD_SAFETY_API_KEY", "\"$resolvedFoodSafetyApiKey\"")
        buildConfigField("String", "NAVER_CLIENT_ID", "\"$resolvedNaverClientId\"")
        buildConfigField("String", "NAVER_CLIENT_SECRET", "\"$resolvedNaverClientSecret\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            buildConfigField("String", "OPENAI_API_KEY", "\"$resolvedOpenAiApiKey\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    // OpenAI 및 네트워크
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.9.0"))
    implementation("com.google.firebase:firebase-storage-ktx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // ONNX Runtime 제거됨 — wakeword 기능이 제거되어 미사용
    // implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-splashscreen:1.0.1")
    ksp(libs.androidx.room.compiler)

    // Vosk 관련 의존성 완전 제거 (오류 방지)
    // implementation("com.alphacephei:vosk-android:0.3.32")

    // JNA 제거됨 — Vosk 제거 이후 미사용 (libjnidispatch.so 원인)
    // implementation("net.java.dev.jna:jna:5.2.0@aar")

    // 16KB 페이지 크기 정렬 버전 강제 지정 (libandroidx.graphics.path.so)
    implementation("androidx.graphics:graphics-path:1.0.1")
}
