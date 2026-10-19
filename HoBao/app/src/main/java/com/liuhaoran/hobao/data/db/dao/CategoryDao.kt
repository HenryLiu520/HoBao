package com.liuhaoran.hobao.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.liuhaoran.hobao.data.db.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    /**
     * 可用于"记账时选分类"的分类列表。
     * ⚠️ 必须带 WHERE isArchived = 0 —— 这是软删除决定的连带影响：
     *    归档掉的分类不该再出现在可选列表里，但旧账依然能显示它。
     */
    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY sortOrder ASC")
    fun observeActive(): Flow<List<CategoryEntity>>

    /** 全部（含归档）—— 分类管理页要看得到归档的 */
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM categories WHERE isArchived = 0")
    suspend fun activeCount(): Int

    @Query("SELECT COUNT(*) FROM categories WHERE isArchived = 0 AND name = :name AND id != :excludeId")
    suspend fun countSameName(name: String, excludeId: Long = -1L): Int

    /** 导入备份用：批量插入，返回新的 id 列表（顺序与传入一致） */
    @Insert
    suspend fun insertAll(categories: List<CategoryEntity>): List<Long>

    /** 覆盖式导入前清空。⚠️ 必须在同一个事务里，且先删 expenses 再删这里（外键） */
    @Query("DELETE FROM categories")
    suspend fun deleteAll()

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    /**
     * 归档（软删除）。
     *
     * 【为什么用一条 UPDATE 查询，而不是"读出来 → 改字段 → 写回"】
     * 因为"读-改-写"在并发下会丢更新：A 读到旧值，B 也读到旧值，
     * A 写回、B 再写回，A 的修改就被覆盖了。直接 UPDATE 只动目标列，没有这个窗口。
     * 而且它只改一个字段，不碰其他列 —— 更安全也更快。
     */
    @Query("UPDATE categories SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("UPDATE categories SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)

    @Query("UPDATE categories SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: Long, order: Int)
}
