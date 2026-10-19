// 根构建脚本：只声明插件版本，不写具体逻辑
//
// AGP 9.0 起 Kotlin 编译已内置于 com.android.application，
// 所以这里不再需要 org.jetbrains.kotlin.android。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
