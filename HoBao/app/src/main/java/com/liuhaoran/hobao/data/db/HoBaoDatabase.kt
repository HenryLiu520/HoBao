package com.liuhaoran.hobao.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.liuhaoran.hobao.data.db.dao.CategoryDao
import com.liuhaoran.hobao.data.db.dao.CurrencyDao
import com.liuhaoran.hobao.data.db.dao.ExpenseDao
import com.liuhaoran.hobao.data.db.entity.CategoryEntity
import com.liuhaoran.hobao.data.db.entity.CurrencyEntity
import com.liuhaoran.hobao.data.db.entity.ExpenseEntity

/**
 * 数据库本体。
 *
 * 【@Database 注解在声明什么】
 * - entities：这个数据库里有哪些表
 * - version：**结构版本号。改了表结构就必须 +1**
 * - exportSchema：是否把结构导出成 JSON（做迁移时用来对比新旧结构）
 *
 * 【version 1 → 2 改了什么】
 * 汇率基准从港币改成人民币，字段 rateToHkd → ratePerCny。
 * 改名 Room 是看不出来的（它只对比列的集合），所以必须 +1，
 * 否则老数据库会被当成"版本一样但结构不对"，运行时直接崩。
 *
 * 【为什么用 fallbackToDestructiveMigration】
 * 这是开发期图省事：版本对不上就把整个库删了重建（数据全丢）。
 * ⚠️ 上线前**必须**换成真正的 Migration：写清楚"旧表怎么改成新表"，
 *    否则用户升级一次 app 就丢一年账。现在还只是你自己的测试数据，先图快。
 */
@Database(
    entities = [
        ExpenseEntity::class,
        CategoryEntity::class,
        CurrencyEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class HoBaoDatabase : RoomDatabase() {

    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun currencyDao(): CurrencyDao

    companion object {
        private const val DB_NAME = "hobao.db"

        @Volatile
        private var instance: HoBaoDatabase? = null

        /**
         * 单例。
         *
         * 【为什么必须单例】
         * 一个数据库文件同时被两个 RoomDatabase 实例打开，会造成锁冲突和数据不一致。
         * 整个 app 只能有一个实例。
         */
        fun get(context: Context): HoBaoDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): HoBaoDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                HoBaoDatabase::class.java,
                DB_NAME,
            )
                .addCallback(SeedCallback)
                .fallbackToDestructiveMigration(true)
                .build()
    }
}

/**
 * 首次创建数据库时写入初始数据。
 *
 * 【为什么必须用 RoomDatabase.Callback，而不是"如果表是空的就播种"】
 * 因为用户被允许删光所有分类！"表空就播种"会让用户删掉的分类**在下次启动时复活**。
 * 用 onCreate 回调 = 只有数据库文件被**创建的那一刻**执行一次，之后永不执行。
 *
 * 【为什么这里写裸 SQL，不用我们自己的 DAO】
 * 因为 onCreate 触发时，Room 的实例还在初始化中，DAO 还没准备好。
 *
 * 【注意 execSQL 的第二个参数】
 * 用 ? 占位符 + 绑定参数，而不是把时间戳拼进字符串。
 * **永远不要拼接字符串进 SQL**，哪怕这次的数据是你自己生成的。
 */
private fun seedDatabase(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()

        // 7 个自带分类（名称、颜色、排序、未归档、创建时刻）
        db.execSQL(
            """
            INSERT INTO categories (name, colorArgb, sortOrder, isArchived, createdAt) VALUES
              ('餐饮', 0xFFE8760B, 0, 0, ?),
              ('交通', 0xFF00696E, 1, 0, ?),
              ('购物', 0xFFB3261E, 2, 0, ?),
              ('住宿', 0xFF6750A4, 3, 0, ?),
              ('学习', 0xFF2E6C3F, 4, 0, ?),
              ('娱乐', 0xFF8A5182, 5, 0, ?),
              ('其他', 0xFF6B6B6B, 6, 0, ?)
            """.trimIndent(),
            Array<Any?>(7) { now },
        )

        // 6 个币种。人民币排第一（它是账本锚点），汇率固定 1.0。
        //
        // ⚠️ 其它币种的汇率一律留 0，表示"用户还没填"。
        //
        // 为什么不填一个"看起来合理"的预设值？因为那是在编造数据 ——
        // 用户会以为那是对的，然后拿一个错的汇率算了一整年账。
        // 宁可显示"未设置"，让界面去拦住他、引导他填。
        db.execSQL(
            """
            INSERT INTO currencies (code, nameZh, symbol, minorUnits, ratePerCny, isEnabled, sortOrder, rateUpdatedAt) VALUES
              ('CNY', '人民币', 'CN¥', 2, 1.0, 1, 0, ?),
              ('HKD', '港币',   'HK$', 2, 0.0, 1, 1, ?),
              ('JPY', '日元',   'JP¥', 0, 0.0, 1, 2, ?),
              ('USD', '美元',   'US$', 2, 0.0, 1, 3, ?),
              ('EUR', '欧元',   '€',   2, 0.0, 1, 4, ?),
              ('GBP', '英镑',   '£',   2, 0.0, 1, 5, ?)
            """.trimIndent(),
            // rateUpdatedAt：人民币是锚点，记成播种时刻；其它 0 = 从未设置
            arrayOf<Any?>(now, 0L, 0L, 0L, 0L, 0L),
        )
}

/**
 * ⚠️ 两个回调都要播种，不能只写 onCreate。
 *
 * 【为什么】
 * fallbackToDestructiveMigration 的流程是：删掉所有表 → 按新结构重建 → 继续开库。
 * 它**不会**触发 onCreate（那个只在"数据库文件从无到有"时触发）。
 * 只写 onCreate 的后果：版本一升，表重建了、数据没了、初始数据也没播种 ——
 * 界面上一片空白，看起来像"功能坏了"。
 *
 * 这是 destructive migration 最典型的坑，第一次做迁移十有八九会踩。
 */
private val SeedCallback = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        seedDatabase(db)
    }

    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
        super.onDestructiveMigration(db)
        seedDatabase(db)
    }
}
