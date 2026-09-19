plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

// Release 签名：从项目根目录 keystore.properties 读取（缺失时跳过签名，产出 unsigned）
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.photoria.backrooms"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.photoria.backrooms"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        // 1.0 起一直没变过：真机上根本分辨不出装的是哪个构建。
        // 之后每个 UI 批次往后 +1 位，顶栏会直接显示，装完一眼可验
        versionName = "1.3-focus"
        // 仅打包主流 ARM ABI，剔除 x86/x86_64（模拟器用）以减小 APK 体积。
        // CameraX 原生库 libimage_processing_util_jni.so 由此从 4 份降至 2 份。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // material-icons-extended 含数千图标，但 R8 (isMinifyEnabled=true) 会 tree-shake
    // 未引用的图标类，实际 Release APK 仅保留本项目用到的 4 个图标。
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.8.2")

    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // SmokeMain 无设备自证：纯 Kotlin，不依赖 Android 框架
    testImplementation(kotlin("stdlib"))
}

// SmokeMain 运行任务（纯 Kotlin，无 Android 依赖）
tasks.register<JavaExec>("runSmokeMain") {
    group = "verification"
    description = "Run SmokeMain pure-Kotlin self-test (no device required)"

    // 必须先编译：干净构建时 class 目录还不存在
    dependsOn("compileDebugKotlin", "compileDebugUnitTestKotlin")

    // 硬编码 class 目录：规避 Android plugin sourceSet API 差异；
    // configuration 用 named() 惰性引用，避免配置期解析（Gradle 性能警告）
    val mainClasses = file("build/tmp/kotlin-classes/debug")
    val testClasses = file("build/tmp/kotlin-classes/debugUnitTest")
    classpath = files(mainClasses, testClasses, configurations.named("debugRuntimeClasspath"))
    mainClass.set("com.photoria.backrooms.SmokeMain")

    if (project.hasProperty("smokeArgs")) {
        args = project.property("smokeArgs").toString().split(" ")
    }

    standardOutput = System.out
    errorOutput = System.err
}

// 把无设备自证纳入校验门：check（lint/test 所属）失败即构建失败
tasks.named("check") { dependsOn("runSmokeMain") }
