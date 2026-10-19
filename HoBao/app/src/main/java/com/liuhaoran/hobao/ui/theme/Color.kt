package com.liuhaoran.hobao.ui.theme

import androidx.compose.ui.graphics.Color

// Material 3 配色规则：每个颜色有两个变体
// 浅色模式用较深的色(白底上看得清)，深色模式用较浅的色(黑底上看得到)
//
// 注意：安卓 12 以上默认开启"动态取色(Material You)"，会读用户壁纸的主色
// 自动生成整套配色 —— 下面这套只是取色不可用时的兜底。

val PrimaryLight = Color(0xFF8A5100)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFFFDCBE)
val OnPrimaryContainerLight = Color(0xFF2C1600)

val SecondaryLight = Color(0xFF00696E)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFB6ECEF)
val OnSecondaryContainerLight = Color(0xFF002022)

val PrimaryDark = Color(0xFFFFB870)
val OnPrimaryDark = Color(0xFF4A2800)
val PrimaryContainerDark = Color(0xFF693C00)
val OnPrimaryContainerDark = Color(0xFFFFDCBE)

val SecondaryDark = Color(0xFF80D4DA)
val OnSecondaryDark = Color(0xFF003739)
val SecondaryContainerDark = Color(0xFF004F52)
val OnSecondaryContainerDark = Color(0xFFB6ECEF)
