package com.liuhaoran.hobao.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.liuhaoran.hobao.data.db.entity.IncomeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IncomeDao {

    @Query("SELECT * FROM incomes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<IncomeEntity>>

    @Query("SELECT * FROM incomes ORDER BY createdAt DESC")
    suspend fun getAll(): List<IncomeEntity>

    @Insert
    suspend fun insert(income: IncomeEntity): Long

    @Insert
    suspend fun insertAll(incomes: List<IncomeEntity>)

    @Delete
    suspend fun delete(income: IncomeEntity)

    @Query("DELETE FROM incomes")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM incomes")
    suspend fun count(): Int
}
