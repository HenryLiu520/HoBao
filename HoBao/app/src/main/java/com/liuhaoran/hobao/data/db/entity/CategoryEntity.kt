package com.liuhaoran.hobao.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 表：categories（分类）
 *
 * 【Entity 是什么】
 * 一个 @Entity 数据类 = 数据库里的一张表。类的每个属性 = 表的一列。
 * Room 在编译期读这个类，**自动生成**建表的 SQL 和所有读写代码 —— 你一行 SQL 都不用写。
 *
 * 【为什么分类要独立成表，而不是像以前那样用枚举？】
 * 枚举的含义是"编译期定死、数量有限、不需要存储"。
 * 一旦允许用户自己增删分类，它就必须能存 → 必须升级成一张表。
 *
 * 【注意：这里故意没有给 name 加唯一约束】
 * 因为「软删除」和「唯一约束」会打架：
 *   把「餐饮」归档后再新建一个「餐饮」，数据库会因名字重复拒绝插入。
 * 而 SQLite 的"部分唯一索引"(只对未归档的行唯一) Room 不直接支持。
 * 所以重复检查放到界面层做（保存时查有没有同名且未归档的分类）。
 */
@Entity(
    tableName = "categories",
    indices = [Index("sortOrder")],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /**
     * 0 = 支出类，1 = 收入类。
     *
     * 【为什么分类也要分家】
     * 「工资」「红包」塞进「餐饮」里毫无意义。支出和收入是两套分类体系，
     * 记账页按当前是支出还是收入，只显示对应的那几个。
     */
    @ColumnInfo(defaultValue = "0")
    val kind: Int = 0,

    /** 显示名，如「餐饮」 */
    val name: String,

    /** 颜色，存 ARGB 整数，如 0xFFE8760B */
    val colorArgb: Long,

    /** 显示顺序。用户可拖动排序；"按分类排序"时用它，而不是用 id */
    val sortOrder: Int,

    /**
     * 软删除标记。
     * true = 已归档：旧账照样能显示它，但新增记账时不再出现在可选列表里。
     * 为什么不用硬删除？见 02-数据模型.md —— 硬删会让旧账的分类指向不存在的 id。
     */
    val isArchived: Boolean = false,

    val createdAt: Long,
) {
    companion object {
        /** 花销账本用的分类（支出、退款、返现共用一套） */
        const val KIND_SPENDING = 0
        /** 收入账本用的分类（工资、奖金、存款…） */
        const val KIND_INCOME = 1
    }
}
