package com.liuhaoran.hobao.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.liuhaoran.hobao.data.db.entity.ExpenseEntity
import com.liuhaoran.hobao.data.db.model.CategorySumRow
import com.liuhaoran.hobao.data.db.model.DailySumRow
import com.liuhaoran.hobao.data.db.model.TotalRow
import kotlinx.coroutines.flow.Flow

/**
 * DAO = Data Access Object（数据访问对象）。
 *
 * 【它是什么】
 * 一个只写**接口**、不写实现的清单："我需要这几种数据库操作"。
 * Room 在编译期读这些注解，**生成真正的实现代码**。你一行 SQL 的解析、游标遍历、
 * 线程切换都不用写。
 *
 * 【为什么读操作用 Flow，写操作用 suspend】
 * - 读 → 返回 Flow：**数据一变，界面自动刷新**。不用手动通知谁去重新查。
 * - 写 → suspend 函数：数据库读写是耗时的 IO，必须离开主线程。suspend 让这件事
 *   由编译器强制（不 suspend 就没法在协程里直接调），而不是靠你记得加线程。
 */
/**
 * 【这段 SQL 是整个"分析"功能的地基，别改坏】
 *
 * 它把一条账折成**人民币分**，语义必须和 `util/Money.kt` 的 `toCnyMinor()` 一模一样：
 *
 *     cnyCents = round(amountMinor × 100 ÷ (10^minorUnits × ratePerCny))
 *
 * 三个坑（都实测过）：
 *
 * 1. **括号不能省**。除法和乘法同级、从左到右结合，
 *    写成 `... / (CASE ...) * rate` 会变成 `(... / X) * rate`，结果差几十倍。
 * 2. **ROUND 必须在 SUM 里面**。项目语义是"每条账各自舍入一次再相加"；
 *    写成 ROUND(SUM(...)) 会差几分到几毛，而且不报错。
 * 3. `CASE minorUnits WHEN 0 THEN 1.0 ELSE 100.0` 是在绕开 SQLite 没有 POWER()。
 *    现在只有 0（日元）和 2（其他）两种情况 —— **将来加三位小数的币种，这里必须一起改**。
 */
private const val CNY_CENTS_PER_ROW =
    "CAST(ROUND(e.amountMinor * 100.0 / " +
        "((CASE c.minorUnits WHEN 0 THEN 1.0 ELSE 100.0 END) * e.ratePerCny)) AS INTEGER)"

@Dao
interface ExpenseDao {

    /** 全部账单，最新在前。返回 Flow = 数据一变界面自动重画 */
    @Query("SELECT * FROM expenses ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ExpenseEntity>>

    /**
     * 插入一条。返回值是新记录的 id（自增主键由数据库生成）。
     * 注意这里没有写 @Insert 之外的任何东西 —— Room 知道该生成什么 SQL。
     */
    @Insert
    suspend fun insert(expense: ExpenseEntity): Long

    @Update
    suspend fun update(expense: ExpenseEntity)

    @Delete
    suspend fun delete(expense: ExpenseEntity)

    /** 导入备份用：批量插入 */
    @Insert
    suspend fun insertAll(expenses: List<ExpenseEntity>)

    /** 覆盖式导入前清空 */
    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM expenses")
    suspend fun count(): Int

    @Query("SELECT * FROM expenses ORDER BY createdAt DESC")
    suspend fun getAll(): List<ExpenseEntity>

    // ══════════════════ 分析用的聚合查询 ══════════════════
    //
    // 这三个方法的共同点：**只把结果运回来，不把原始行运回来**。
    //
    // 【退款是负数行，这让 SQL 变简单了】
    // 重构前退款是子表 + 缓存列，每条查询都要 `SUM(expr - e.refundedCnyCents)`。
    // 现在退款就是一条负数账，`SUM(...)` 天然就是净额 —— 少了一个会漂移的缓存。

    /**
     * 分类占比：**只统计正数行（支出）**。
     *
     * 退款不参与占比：它是"负的支出"，画进饼图会得到负的扇区。
     * 界面上它单独一行显示（「退款 −20.00」）。
     */
    @Query(
        "SELECT e.categoryId AS categoryId, " +
            "SUM(" + CNY_CENTS_PER_ROW + ") AS cnyCents, " +
            "COUNT(*) AS cnt " +
            "FROM expenses e JOIN currencies c ON c.code = e.currencyCode " +
            "WHERE e.createdAt >= :from AND e.createdAt < :to AND e.amountMinor > 0 " +
            "GROUP BY e.categoryId ORDER BY cnyCents DESC"
    )
    suspend fun categorySums(from: Long, to: Long): List<CategorySumRow>

    /**
     * 区间的三个数：支出合计 / 退款合计 / 支出笔数。
     *
     * 【为什么用 CASE 分开算，而不是一次 SUM】
     * 一次 SUM 得到的是净额，可能是负的 —— 而"本月花了 −20 元"是句荒谬的话。
     * 三个数各自都不荒谬，净额由界面相加得出。
     */
    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN e.amountMinor > 0 THEN " + CNY_CENTS_PER_ROW + " ELSE 0 END), 0) " +
            "  AS expenseCnyCents, " +
            "COALESCE(SUM(CASE WHEN e.amountMinor < 0 THEN " + CNY_CENTS_PER_ROW + " ELSE 0 END), 0) " +
            "  AS refundCnyCents, " +
            "COALESCE(SUM(CASE WHEN e.amountMinor > 0 THEN 1 ELSE 0 END), 0) AS cnt " +
            "FROM expenses e JOIN currencies c ON c.code = e.currencyCode " +
            "WHERE e.createdAt >= :from AND e.createdAt < :to"
    )
    suspend fun analysisTotal(from: Long, to: Long): TotalRow

    /**
     * 按天汇总（本地时区）。值是**净额**，可能为负 ——
     * 某天只退款没花钱，那根柱子就该朝下。
     */
    @Query(
        "SELECT strftime('%Y-%m-%d', e.createdAt / 1000, 'unixepoch', 'localtime') AS day, " +
            "SUM(" + CNY_CENTS_PER_ROW + ") AS cnyCents " +
            "FROM expenses e JOIN currencies c ON c.code = e.currencyCode " +
            "WHERE e.createdAt >= :from AND e.createdAt < :to " +
            "GROUP BY day ORDER BY day"
    )
    suspend fun dailySums(from: Long, to: Long): List<DailySumRow>
}
