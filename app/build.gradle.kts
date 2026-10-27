import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.android.junit5)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// 静态检查配置（方案 9.2：ktlint 与 detekt 零 error）
ktlint {
    version.set(libs.versions.ktlintCli.get())
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude { element -> element.file.path.contains("generated") }
    }
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    ignoreFailures = false
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        sarif.required.set(false)
    }
}

// 签名信息仅从 local.properties 读取，绝不入库
val localProperties: Properties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) {
            file.inputStream().use { load(it) }
        }
    }
val releaseStoreFile: String? = localProperties.getProperty("RELEASE_STORE_FILE")
val releaseStorePassword: String? = localProperties.getProperty("RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String? = localProperties.getProperty("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = localProperties.getProperty("RELEASE_KEY_PASSWORD")

// 把 RELEASE_STORE_FILE 展开成候选路径列表（按优先级排序）。
// 相对路径有两种合理语义，都要保留：
// 1. 相对模块目录 app/：与 local.properties.example 的 ../keystore/release.jks 一致；
// 2. 相对仓库根目录：CI 把 release.jks 落在根目录，local.properties 里写的是裸文件名。
// 必须在顶层先算好：android {} 块里的 file() 以模块目录为基准，
// 旧写法因此把根目录的 release.jks 拼成了 app/release.jks，assembleRelease 静默产出 unsigned 包。
val releaseStoreFileCandidates: List<File> =
    releaseStoreFile?.let { raw ->
        val candidate = File(raw)
        if (candidate.isAbsolute) listOf(candidate) else listOf(File(projectDir, raw), File(rootDir, raw))
    } ?: emptyList()

android {
    namespace = "com.totop.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.totop.app"
        minSdk = 24
        targetSdk = 34
        // 0.3.0 那一轮只写了 Changelog 没动版本号，包体仍自称 0.2.0，
        // 出问题时无法从包反查到对应改动，这里一并补齐。
        // 0.4.0：第六轮真机反馈（视觉直读、一次对话一题、语音输入、启动与页面过渡、框选修缺陷）。
        versionCode = 4
        versionName = "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // 包体控制：首版只打 arm64-v8a（目标机型均为 64 位）。
        // 若 M8 确需 32 位，改此处并在 CHANGELOG 记录。
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (releaseStoreFile != null && releaseStorePassword != null &&
            releaseKeyAlias != null && releaseKeyPassword != null
        ) {
            // 签名文件不存在时立刻失败：旧写法只调用 file(...) 而不校验存在性，
            // assembleRelease 会静默产出 unsigned 包，直到 CI 的产物校验才暴露成另一条报错。
            val storeFileResolved = releaseStoreFileCandidates.firstOrNull { it.exists() }
            if (storeFileResolved == null) {
                throw GradleException(
                    "签名文件不存在。已尝试：" +
                        releaseStoreFileCandidates.joinToString("、") { it.absolutePath } +
                        "。请检查 local.properties 的 RELEASE_STORE_FILE 或 CI Secrets。",
                )
            }
            create("release") {
                storeFile = storeFileResolved
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        // 与 Kotlin 1.9.25 配套的 Compose 编译器
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/DEPENDENCIES",
                    "/META-INF/LICENSE",
                    "/META-INF/LICENSE.txt",
                    "/META-INF/NOTICE",
                    "/META-INF/NOTICE.txt",
                    "META-INF/*.kotlin_module",
                    "DebugProbesKt.bin",
                    "kotlin-tooling-metadata.json",
                )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = false
        // GradleDependency / AndroidGradlePluginVersion：版本由 M1 统一锁定，不追逐最新。
        // ChromeOsAbiSupport：包体门禁要求仅 arm64，ChromeOS 不在目标机型内。
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "ChromeOsAbiSupport")
        baseline = file("lint-baseline.xml")
    }

    sourceSets {
        // Room schema 供 MigrationTest 使用
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // ---------- Compose（版本由 BOM 统一） ----------
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // ---------- 基础、生命周期、导航 ----------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    // ---------- 依赖注入 ----------
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    // @HiltWorker（DailyStatsWorker / RetryWorker / CacheCleanWorker / ReviewReminderWorker）
    implementation(libs.hilt.work)
    ksp(libs.hilt.ext.compiler)

    // ---------- 本地存储 ----------
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ---------- 密钥（BYOK）与设置 ----------
    implementation(libs.androidx.security.crypto)

    // ---------- 网络与序列化 ----------
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    debugImplementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // ---------- 相机与离线 OCR ----------
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // ML Kit Text Recognition v2 **bundled** 中文变体：模型随 APK 打包，完全离线。
    // 刻意不使用 com.google.android.gms:play-services-mlkit-text-recognition*（thin 变体，
    // 需 GMS 下载模型），保证国行无 GMS 机型可用；全程不做任何图片上传。
    implementation(libs.mlkit.text.recognition.chinese)

    // ---------- 图片加载 ----------
    implementation(libs.coil.compose)

    // ---------- 后台任务与文件访问 ----------
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)

    // ---------- 性能观测 ----------
    implementation(libs.androidx.profileinstaller)

    // ---------- 构建期 ----------
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // ---------- 单元测试 ----------
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.junit5.params)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)

    // ---------- 仪器测试 ----------
    // Room 迁移测试：MigrationTestHelper 仅在 androidTest 源集可用
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)

    // ---------- 调试工具 ----------
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
