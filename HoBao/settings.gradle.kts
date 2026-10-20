// ─────────────────────────────────────────────────────────────
// 这里决定 Gradle 去哪里下载"构建工具"和"第三方库"。
//
// ⚠️ 顺序很关键，别把阿里云挪回前面：
//
//   KSP 的 plugin marker（com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin）
//   在 Maven Central 上是**只有 POM、没有 jar** 的空壳。
//   阿里云镜像会为它返回 **502**（而不是 404），而 502 属于 IO 错误 ——
//   Gradle 会把整个仓库标记为不可用，最后报出来的却是
//   "Could not resolve junit:junit:4.13.2"，完全指向错误的方向，排查成本极高。
//
//   把官方仓库放在前面，Gradle 从 Central 拿到 POM 就知道它没有 jar，不会去要；
//   阿里云退到最后当兜底。
//
// ⚠️ `gradlePluginPortal()` 在 dependencyResolutionManagement 里不能删。
// ─────────────────────────────────────────────────────────────

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // 兜底：某些国内网络直连 Google/Maven 很慢时才会用到
        maven("https://maven.aliyun.com/repository/google")
    }
}

rootProject.name = "HoBao"
include(":app")
