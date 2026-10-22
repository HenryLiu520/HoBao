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
        // ⚠️ 每次发版都要 +1：签名相同 + versionCode 更大 = 用户能直接覆盖更新，
        //    否则系统会拒绝安装，只能卸载重装（数据全丢）
        versionCode = 4
        // 版本号规则（三段，见 README「版本号规则」）：
        //   1.x.x  第一位 = 重大功能变化或算法改动  ← 自动联网更新属于这一类
        //   x.1.x  第二位 = 小功能
        //   x.x.1  第三位 = 修 bug
        versionName = "2.0.0"
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

// Room 的结构导出目录：exportSchema = true 时，每个版本的结构会写成
// app/schemas/<版本>.json。写迁移时拿它对比新旧结构，也能直接生成迁移测试。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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

    // ── 图表（Vico，Apache 2.0）──
    implementation(libs.vico.compose.m3)

    // ── 后台任务（每日同步汇率）──
    implementation(libs.androidx.work.runtime.ktx)

    // ── Room 数据库 ──
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // ksp(...) 表示"只在编译期用这个库"：它生成的代码会进 app，
    // 但处理器本身不会被打包进去
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.ui.tooling)

    // ── 单元测试（跑在 JVM 上，不需要模拟器）──
    // org.json：Android 自带的 org.json 在 JVM 单测里是空壳（一调就抛 not mocked），
    //          测试用 Maven 上同 API 的实现顶上
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// 联网冒烟测试默认跳过；`./gradlew test -Dhobao.live=1` 才会真的去打 API
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("hobao.live", System.getProperty("hobao.live", "0"))
}

// ─────────────────────────────────────────────────────────────
// 排除 KSP 的 plugin marker。
//
// `com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin` 是 Gradle 的
// **plugin marker**：Maven Central 上只有 POM、没有 jar（实测目录里只有 pom 和签名）。
//
// 但 KSP 会把它挂到 debugUnitTestCompileClasspath 上，Gradle 于是去找它的 jar：
//   · Maven Central / Plugin Portal → 404（本来就没有）
//   · 阿里云镜像 → 报 **502**（对不存在的文件报网关错误，而不是 404）
// 502 属于 IO 错误，会让 Gradle 把整个仓库标记为不可用，最后报出的却是
// "Could not resolve junit:junit:4.13.2" —— 完全误导。
//
// 这个 marker 在**运行时没有任何用处**（它只声明"这个插件对应哪个实现"),
// 真正需要的 symbol-processing-gradle-plugin 已经在 pluginManagement 里解析好了。
// 所以整体排除，干净利落。
// ─────────────────────────────────────────────────────────────
// （曾经试过 exclude KSP 的 plugin marker，但会连插件自身的解析一起排掉，
//   结果是 "Plugin com.google.devtools.ksp was not found"。真正的解法在 settings.gradle.kts：
//   把官方仓库排到阿里云前面。）
