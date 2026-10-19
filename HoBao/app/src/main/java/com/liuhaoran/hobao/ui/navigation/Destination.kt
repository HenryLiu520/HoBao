package com.liuhaoran.hobao.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 三个顶级页面。
 *
 * route 是路由标识(英文，给程序用)，label 是显示名(给用户看)。
 * 用枚举而不是散落的字符串：编译器帮你防拼写错误，IDE 能自动补全。
 */
enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Add("add", "记一笔", Icons.Filled.Add),
    History("history", "回顾", Icons.AutoMirrored.Filled.List),
    Settings("settings", "设置", Icons.Filled.Settings);

    companion object {
        fun fromRoute(route: String?): Destination =
            entries.firstOrNull { it.route == route } ?: Add
    }
}
