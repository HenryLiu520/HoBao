import java.io.FileInputStream
import java.util.Properties

plugins {
    // AGP 9.0 起 Kotlin 编译已内置于它，但 Compose 编译器还要单独挂一个插件
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // KSP：编译期注解处理器。Room 靠它在编译时**生成**数据库实现代码
    alias(libs.plugins.ksp)
}

android {
    // ── 发布签名 ──
    // 密钥和密码放在 keystore.properties 里，那个文件**不进 git**。
    // 找不到它也能正常构建 debug 包，只是 release 包会是未签名的。
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) FileInputStream(f).use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    namespace = "com.liuhaoran.hobao"

    // 安卓从 API 36 起引入"次版本号"，平台包叫 android-37.2，
    // 所以要写成 release(37) { minorApiLevel = 2 }，不能写单纯的 37
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.liuhaoran.hobao"
        minSdk = 26          // 最低支持 Android 8.0
        targetSdk = 36       // 已适配到 Android 16(你手机的版本)
        versionCode = 1
        versionName = "0.3"
    }

    buildTypes {
        release {
            // 有签名配置就用它；没有就构建未签名包（不会污染 debug 流程）
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Release 构建时的 lint 检查要额外拉一整套 lint 工具（经常卡在镜像上，见下面 502）。
        // 个人项目先关掉，需要时手动跑 `gradle lint`。
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // ViewModel：让数据活得比界面久(旋转屏幕不丢)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // 页面路由(官方导航方案)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)

    // ── Room 数据库 ──
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // ksp(...) 表示"只在编译期用这个库"：它生成的代码会进 app，
    // 但处理器本身不会被打包进去
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.ui.tooling)
}
