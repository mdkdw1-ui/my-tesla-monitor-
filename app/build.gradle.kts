plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tesla.coreconsole"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tesla.coreconsole"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // 🌟 [수정] MainActivity의 viewModel() 아키텍처 지원을 위한 필수 디펜던시 추가
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    
    // 🌟 [수정] 백그라운드 스레드 데이터 파싱(Dispatchers.IO)을 위한 코루틴 안드로이드 라이브러리 추가
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Jetpack Compose BOM 및 UI 툴킷 정의
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // 네이티브 비동기 네트워크 통신 킷
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // 🌟 [수정] 의존성 클래스 대시보드 충돌을 방지하기 위해 안정적인 버전(4.3.0)으로 하향 조정
    implementation("com.google.maps.android:maps-compose:4.3.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
}
