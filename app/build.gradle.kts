import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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

    val legacyPropertiesFile = file("C:/00_Projects/TomaAI/local.properties")
    if (legacyPropertiesFile.exists()) {
        val legacyProperties = Properties()
        legacyPropertiesFile.inputStream().use { legacyProperties.load(it) }
        legacyProperties.getProperty("OPENAI_API_KEY")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }

    return ""
}

val resolvedOpenAiApiKey = resolveOpenAiApiKey()

android {
    namespace = "com.capstone.toma"

    // 에러 해결을 위해 35에서 36으로 변경합니다.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.capstone.toma"
        minSdk = 24
        // targetSdk도 36으로 맞춰줍니다.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 2. BuildConfig에 변수 추가
        buildConfigField("String", "OPENAI_API_KEY", "\"$resolvedOpenAiApiKey\"")
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

    // OpenAI 통신용
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

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

    // 💡 Vosk 오프라인 음성 인식 라이브러리 추가
    implementation("com.alphacephei:vosk-android:0.3.32")

    implementation("net.java.dev.jna:jna:5.2.0@aar")

}
