package com.liuhaoran.hobao.data.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 备份格式的迁移链。
 *
 * 【为什么这几条必须测】
 * 用户手机里躺着的是**旧版本导出的文件**，那些文件永远不会变。
 * 一旦新版本读不了旧文件，"备份"就从保险变成了摆设 ——
 * 而且用户通常是在换手机那天才发现。
 *
 * 版本历史：
 *   v1：只有花销
 *   v2：加 kind（支出 / 收入混在同一张表）
 *   v3：收入独立成块 incomes；花销里的退款是**负数行**
 */
class BackupFormatMigrationTest {

    private fun v1Json(): String = """
        {
          "format": "hobao-backup",
          "formatVersion": 1,
          "exportedAt": 1700000000000,
          "appVersion": "0.3",
          "data": {
            "categories": [
              { "name": "餐饮", "sortOrder": 0, "isArchived": false }
            ],
            "currencies": [
              { "code": "CNY", "nameZh": "人民币", "symbol": "CN¥",
                "minorUnits": 2, "ratePerCny": 1.0, "rateUpdatedAt": 0 }
            ],
            "expenses": [
              { "amountMinor": 4200, "currencyCode": "CNY", "ratePerCny": 1.0,
                "reason": "午饭", "categoryName": "餐饮",
                "createdAt": 1700000000000, "updatedAt": 1700000000000 }
            ]
          }
        }
    """.trimIndent()

    /** v2 的文件：收入和花销混在 expenses 里，用 kind 区分 */
    private fun v2Json(): String = """
        {
          "format": "hobao-backup",
          "formatVersion": 2,
          "exportedAt": 1700000000000,
          "appVersion": "1.0.0",
          "data": {
            "categories": [
              { "name": "餐饮", "sortOrder": 0, "isArchived": false, "kind": 0 },
              { "name": "工资", "sortOrder": 100, "isArchived": false, "kind": 1 }
            ],
            "currencies": [
              { "code": "CNY", "nameZh": "人民币", "symbol": "CN¥",
                "minorUnits": 2, "ratePerCny": 1.0, "rateUpdatedAt": 0 }
            ],
            "expenses": [
              { "amountMinor": 4200, "currencyCode": "CNY", "ratePerCny": 1.0,
                "reason": "午饭", "categoryName": "餐饮",
                "createdAt": 1700000000000, "updatedAt": 1700000000000, "kind": 0 },
              { "amountMinor": 500000, "currencyCode": "CNY", "ratePerCny": 1.0,
                "reason": "月薪", "categoryName": "工资",
                "createdAt": 1700000001000, "updatedAt": 1700000001000, "kind": 1 }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `v1 文件全部当花销，没有收入`() {
        val file = BackupCodec.decode(v1Json())
        assertEquals(1, file.expenses.size)
        assertEquals(0, file.incomes.size)
        assertEquals(4200L, file.expenses[0].amountMinor)
    }

    @Test
    fun `v2 里的收入会被搬到 incomes 块`() {
        val file = BackupCodec.decode(v2Json())

        // 花销只剩一条
        assertEquals(1, file.expenses.size)
        assertEquals("午饭", file.expenses[0].reason)

        // 收入搬过去了
        assertEquals(1, file.incomes.size)
        assertEquals(500000L, file.incomes[0].amountMinor)
        assertEquals("工资", file.incomes[0].categoryName)
    }

    @Test
    fun `导出的文件是 v3 且带 incomes 块`() {
        val json = BackupCodec.encode(sample())
        val root = JSONObject(json)
        assertEquals(3, root.getInt("formatVersion"))

        val data = root.getJSONObject("data")
        assertTrue(data.has("incomes"))
        assertEquals(1, data.getJSONArray("incomes").length())
        // 花销里不该再有 kind
        val exp = data.getJSONArray("expenses").getJSONObject(0)
        assertTrue(!exp.has("kind"))
    }

    @Test
    fun `退款是负数行，导出再导入符号不丢`() {
        val original = sample(
            expenses = listOf(
                BackupExpense(4200, "CNY", 1.0, "午饭", "餐饮", 1L, 1L),
                BackupExpense(-2000, "CNY", 1.0, "退货", "退款", 2L, 2L),
            )
        )
        val back = BackupCodec.decode(BackupCodec.encode(original))

        assertEquals(2, back.expenses.size)
        assertEquals(-2000L, back.expenses[1].amountMinor)
        assertEquals("退货", back.expenses[1].reason)
    }

    @Test
    fun `导出再导入，收入不丢`() {
        val back = BackupCodec.decode(BackupCodec.encode(sample()))
        assertEquals(1, back.incomes.size)
        assertEquals(500000L, back.incomes[0].amountMinor)
    }

    @Test
    fun `更新版本的备份要明确报错，不能静默导入`() {
        val future = v1Json().replace("\"formatVersion\": 1", "\"formatVersion\": 99")
        try {
            BackupCodec.decode(future)
            fail("应该抛异常")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("更新版本"))
        }
    }

    private fun sample(
        expenses: List<BackupExpense> = listOf(
            BackupExpense(4200, "CNY", 1.0, "午饭", "餐饮", 1L, 1L)
        ),
    ) = BackupFile(
        formatVersion = BackupCodec.CURRENT_FORMAT_VERSION,
        exportedAt = 1L,
        appVersion = "test",
        categories = listOf(
            BackupCategory("餐饮", 0, false, kind = 0),
            BackupCategory("工资", 100, false, kind = 1),
        ),
        currencies = listOf(BackupCurrency("CNY", "人民币", "CN¥", 2, 1.0, 0L)),
        expenses = expenses,
        incomes = listOf(
            BackupIncome(500000, "CNY", 1.0, "月薪", "工资", 3L, 3L)
        ),
    )
}
