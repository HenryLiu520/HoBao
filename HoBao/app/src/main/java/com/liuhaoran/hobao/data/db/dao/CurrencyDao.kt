package com.liuhaoran.hobao.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.liuhaoran.hobao.data.db.entity.CurrencyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CurrencyDao {

    /** 记账时可选的币种（用户启用过的） */
    @Query("SELECT * FROM currencies WHERE isEnabled = 1 ORDER BY sortOrder ASC")
    fun observeEnabled(): Flow<List<CurrencyEntity>>

    /** 全部币种 —— 设置页的"货币与汇率"要看到全部 */
    @Query("SELECT * FROM currencies ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<CurrencyEntity>>

    @Query("SELECT * FROM currencies WHERE code = :code")
    suspend fun findByCode(code: String): CurrencyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(currencies: List<CurrencyEntity>)

    @Update
    suspend fun update(currency: CurrencyEntity)

    /**
     * 只更新汇率这一个字段。
     *
     * 【这里的 bind 参数（:rate / :at）就是防 SQL 注入的正解】
     * 用户输入的数值是被"绑定"进去的，永远只是数据，不可能变成 SQL 语句的一部分。
     * 对比一下会被注入的写法： "... SET ratePerCny = " + 用户输入  ← 绝对不行
     */
    @Query("UPDATE currencies SET ratePerCny = :rate, rateUpdatedAt = :at WHERE code = :code")
    suspend fun updateRate(code: String, rate: Double, at: Long)

    @Query("UPDATE currencies SET isEnabled = :enabled WHERE code = :code")
    suspend fun setEnabled(code: String, enabled: Boolean)

    /** 覆盖式导入前清空 */
    @Query("DELETE FROM currencies")
    suspend fun deleteAll()

    @Query("SELECT * FROM currencies ORDER BY sortOrder ASC")
    suspend fun getAll(): List<CurrencyEntity>
}
