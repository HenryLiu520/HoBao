package com.liuhaoran.hobao.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
)

/**
 * 全 app 的视觉总开关。
 *
 * ── 关于 Material 3 Expressive 的一个坑（值得记住）──
 * Expressive 是 M3 的最新演进（随 Android 16 发布），但它的**组件和动效 API**
 * （MaterialExpressiveTheme / MotionScheme / MaterialShapes）
 * 在当前稳定版 material3 1.4.0 里虽然类存在，却被标成了 `internal`
 * —— 也就是应用代码根本调不到。它们公开的版本是 1.5.0-alpha28，仍在 alpha。
 *
 * 所以这里的取舍是：**留稳定版**。
 * 我们完整拿到 Expressive 的"形状 + 配色 + 字体"语言（这三样 1.4.0 全支持），
 * 只放弃动效那部分（弹簧物理动画）。等 1.5.0 转正，把这里的 MaterialTheme
 * 换成 MaterialExpressiveTheme + expressiveMotionScheme() 即可，一行的事。
 *
 * 判断依据不是"哪个新"，而是"哪个是 stable"：
 * 把 alpha 版设计系统当地基，是新手最容易踩的坑。
 *
 * ── dynamicColor ──
 * 安卓 12+ 会读用户壁纸主色自动生成整套配色（Material You / 动态取色），
 * 这是谷歌官方推荐的默认行为。上面那两套手写配色只是取色不可用时的兜底。
 */
@Composable
fun HoBaoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
