package com.liuhaoran.hobao.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.liuhaoran.hobao.data.db.entity.ExpenseEntity
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
}
