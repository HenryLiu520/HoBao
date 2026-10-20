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
    // 5 万条账，回顾页要搬 5 万行；分析页只要几十行。

    /** 分类占比：区间内每个分类合计多少人民币分 */
    @Query(
        "SELECT e.categoryId AS categoryId, " +
            "SUM(" + CNY_CENTS_PER_ROW + ") AS cnyCents, " +
            "COUNT(*) AS cnt " +
            "FROM expenses e JOIN currencies c ON c.code = e.currencyCode " +
            "WHERE e.createdAt >= :from AND e.createdAt < :to " +
            "GROUP BY e.categoryId ORDER BY cnyCents DESC"
    )
    suspend fun categorySums(from: Long, to: Long): List<CategorySumRow>

    /** 区间总额 + 笔数。没有数据时 COALESCE 保证返回 0 而不是 null */
    @Query(
        "SELECT COALESCE(SUM(" + CNY_CENTS_PER_ROW + "), 0) AS cnyCents, " +
            "COUNT(*) AS cnt " +
            "FROM expenses e JOIN currencies c ON c.code = e.currencyCode " +
            "WHERE e.createdAt >= :from AND e.createdAt < :to"
    )
    suspend fun analysisTotal(from: Long, to: Long): TotalRow

    /**
     * 按天汇总（本地时区）。
     *
     * 只下沉到"天"这一层；要按月看时在 Kotlin 里把 30 天折成 1 个月 ——
     * 一年也就 366 行，再往下推给 SQL 不值。
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
