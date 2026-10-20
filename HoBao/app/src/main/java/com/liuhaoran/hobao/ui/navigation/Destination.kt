package com.liuhaoran.hobao.ui.navigation

import androidx.annotation.DrawableRes
import com.liuhaoran.hobao.R

/**
 * 四个顶级页面。
 *
 * route 是路由标识(英文，给程序用)，label 是显示名(给用户看)，
 * iconRes 指向 `res/drawable/ic_*.xml` —— 全部来自 Material Symbols，
 * 见 `文档/14-图标规范.md`。
 *
 * 【为什么图标改成资源 id 而不是 ImageVector】
 * Compose core 只有约 50 个图标，里面没有「分析」用得上的。
 * 与其一半用 `Icons.Filled.*`、一半用下载的 drawable（两种来源，风格会有细微差异），
 * 不如四个统一走同一条下载管线。
 */
enum class Destination(
    val route: String,
    val label: String,
    @DrawableRes val iconRes: Int,
) {
    Add("add", "记一笔", R.drawable.ic_add),
    History("history", "回顾", R.drawable.ic_list),
    Analysis("analysis", "分析", R.drawable.ic_pie_chart),
    Settings("settings", "设置", R.drawable.ic_settings);

    companion object {
        fun fromRoute(route: String?): Destination =
            entries.firstOrNull { it.route == route } ?: Add
    }
}
