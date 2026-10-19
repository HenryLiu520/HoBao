package com.liuhaoran.hobao.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive 的形状体系 —— 比老版 Material 3 的圆角明显更大。
 *
 * 为什么圆角这么重要？它是 Expressive 视觉语言的主干：
 * 大圆角 + 弹簧动画 + 色调化配色，三者合起来才叫"表达力"。
 * 只换颜色不改形状，看起来还是老一套。
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
