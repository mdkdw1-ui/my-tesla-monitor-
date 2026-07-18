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
        // 🌟 불필요한 viewBinding 플래그를 완전히 제거하여 정리
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
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
    
    // 🌟 [핵심 수정] 구글맵 확장팩 내부에서 꼬여서 올라오는 viewbinding 의존성을 완벽하게 도려냄(exclude)
    implementation("com.google.maps.android:maps-compose:4.3.0") {
        exclude(group = "androidx.databinding", module = "viewbinding")
    }
    implementation("com.google.android.gms:play-services-maps:18.2.0")
}
