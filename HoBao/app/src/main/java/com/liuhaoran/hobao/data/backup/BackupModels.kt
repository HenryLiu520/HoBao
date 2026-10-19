package com.liuhaoran.hobao.data.backup

/**
 * 备份文件里的数据长什么样 —— **这是"对外"的结构，不等于数据库表结构**。
 *
 * 【为什么要分开定义，而不是直接序列化 Entity】
 * 1. 数据库表会随版本变（加列、改名），但**文件格式一旦发出去就改不回来了** ——
 *    用户手里的旧备份永远存在。把两者绑死，改表就会连带改文件格式。
 * 2. Entity 里有设备相关的东西（自增 id），文件里不能有。
 * 3. 文件里要放"人看得懂、跨设备稳定"的引用：分类用**名字**，币种用 **ISO 代码**。
 */

/** 导出/导入的一份完整备份 */
data class BackupFile(
    val formatVersion: Int,
    val exportedAt: Long,
    val appVersion: String,
    val categories: List<BackupCategory>,
    val currencies: List<BackupCurrency>,
    val expenses: List<BackupExpense>,
)

/**
 * ⚠️ 分类**不存颜色**（用户明确要求：颜色不保存，名字一样就行）。
 * 导入时：同名分类直接复用设备上已有的那个（连颜色一起沿用）；
 *        没有同名才新建，颜色从调色板里轮着一个一个给。
 */
data class BackupCategory(
    val name: String,
    val sortOrder: Int,
    val isArchived: Boolean,
)

data class BackupCurrency(
    val code: String,
    val nameZh: String,
    val symbol: String,
    val minorUnits: Int,
    /** 1 人民币 = 多少该币种。**必须带上**，否则导回去再算一遍，历史总额就变了 */
    val ratePerCny: Double,
    val rateUpdatedAt: Long,
)

data class BackupExpense(
    val amountMinor: Long,
    val currencyCode: String,
    /** 🔒 记账当时的汇率快照 —— 有了它，历史金额在任何设备上都算得一样 */
    val ratePerCny: Double,
    val reason: String,
    /** 用名字关联分类，不用 id（id 换台设备就变了） */
    val categoryName: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 导入方式 */
enum class ImportMode {
    /** 合并：追加，跳过已经存在的（按复合键判重） */
    MERGE,

    /** 覆盖：先清空再写入（整件事在一个事务里，失败就全部回滚） */
    REPLACE,
}

/** 导入结果，用来给用户一句人话交代 */
data class ImportResult(
    val added: Int,
    val skipped: Int,
    val newCategories: Int,
    val newCurrencies: Int,
) {
    fun summary(): String = buildString {
        append("导入了 $added 笔")
        if (skipped > 0) append("，跳过 $skipped 笔重复")
        if (newCategories > 0) append("，新建 $newCategories 个分类")
        if (newCurrencies > 0) append("，新增 $newCurrencies 个币种")
    }
}
