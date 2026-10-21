// 根构建脚本：仅声明插件版本，不在此处添加依赖。
// 任何依赖版本变更都必须改 gradle/libs.versions.toml，禁止在模块脚本内硬编码版本号。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.android.junit5) apply false
    // 静态检查：ktlint 管格式，detekt 管复杂度与潜在缺陷（方案 9.2 质量门禁要求零 error）
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
