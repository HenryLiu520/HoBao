// ─────────────────────────────────────────────────────────────
// 这里决定 Gradle 去哪里下载"构建工具"和"第三方库"。
//
// 阿里云镜像是国内/香港访问最快的官方同步源，放在最前面；
// google() / mavenCentral() 作为兜底，镜像挂了也不会构建失败。
//
// 以后你在别的地方（比如内地以外）开发发现镜像慢了，
// 把 maven("https://maven.aliyun.com/...") 这三行删掉即可。
// ─────────────────────────────────────────────────────────────

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "HoBao"
include(":app")
