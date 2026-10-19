package com.liuhaoran.hobao.model

/**
 * 排序方式。
 *
 * ⚠️ 安全伏笔：M2 接 Room 之后，这个枚举会多一个 `sqlOrderBy` 字段，
 *    值必须是**我们自己写死的字符串**（如 "createdAt DESC"）。
 *
 *    为什么？因为 SQL 的 ORDER BY 后面跟的是"列名"，而列名**不能**像
 *    WHERE 那样用占位符 ? 绑定。如果偷懒写成 ORDER BY $用户输入，
 *    就产生了 SQL 注入漏洞。正确做法：用户选的是这张菜单上的编号，
 *    不是直接往 SQL 里塞的字符串。
 */
enum class SortOrder(val label: String) {
    TIME_DESC("最新优先"),
    TIME_ASC("最早优先"),
    AMOUNT_DESC("金额从高到低"),
    AMOUNT_ASC("金额从低到高"),
    CATEGORY("按分类");

    /**
     * 是否按时间排序。
     * 只有按时间排序时才做"按月分组"—— 因为按金额排序还按月分组的话，
     * 分组就破坏了排序的意义(你会看到 9 月里混着金额小的、10 月里混着金额大的)。
     */
    val groupsByMonth: Boolean get() = this == TIME_DESC || this == TIME_ASC
}
