plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.echonion.nion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.echonion.nion"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release 签名配置
    signingConfigs {
        create("release") {
            storeFile = file("nion-release.jks")
            storePassword = "nion2024"
            keyAlias = "nion"
            keyPassword = "nion2024"
        }
    }

    flavorDimensions += "character"

    productFlavors {
        // 标准版 —— 通用 Nion，无内置角色预设
        create("standard") {
            dimension = "character"
        }
        // 类脑娘版 —— 内置类脑娘角色卡、头像、表情包
        create("character") {
            dimension = "character"
            applicationIdSuffix = ".character"
            versionNameSuffix = "-character"
        }
    }

    buildTypes {
        release {
            // 开启 R8 代码压缩 + 资源压缩
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 禁用 lint 误报：activity-compose 1.13.0 已包含 Fragment 1.3.0+，
    // 但 lint 无法正确识别传递依赖版本
    lint {
        disable += "InvalidFragmentVersionForActivityResult"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(25)
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.05.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    // OkHttp —— HTTP 客户端，用于 SSE 流式聊天
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WorkManager —— 后台提醒任务调度（渐进式循环、LLM 文案生成）
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Google Play Services Location —— GPS 定位，用于天气功能获取用户位置
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // UniFFI - generated bindings will be included as source
    implementation("net.java.dev.jna:jna:5.16.0@aar")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
