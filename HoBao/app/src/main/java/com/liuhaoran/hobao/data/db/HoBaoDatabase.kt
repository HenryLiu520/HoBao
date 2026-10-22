package com.liuhaoran.hobao.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.liuhaoran.hobao.data.db.dao.CategoryDao
import com.liuhaoran.hobao.data.db.dao.CurrencyDao
import com.liuhaoran.hobao.data.db.dao.ExpenseDao
import com.liuhaoran.hobao.data.db.dao.IncomeDao
import com.liuhaoran.hobao.data.db.entity.CategoryEntity
import com.liuhaoran.hobao.data.db.entity.CurrencyEntity
import com.liuhaoran.hobao.data.db.entity.ExpenseEntity
import com.liuhaoran.hobao.data.db.entity.IncomeEntity

/**
 * 数据库本体。
 *
 * 【版本历史】
 * - 1 → 2：汇率基准从港币改成人民币（rateToHkd → ratePerCny）
 * - 2 → 3：加 `kind` 列（支出 / 收入）
 * - 3 → 4：退款子表 adjustments + refundedCnyCents 缓存列
 * - 4 → 5：**拆成两本账**。收入搬到独立表 incomes；
 *           退款改成 expenses 里的**负数行**，删掉 adjustments 和缓存列，
 *           expenses 也不再需要 kind
 *
 * 【4 → 5 为什么把 3、4 两步推翻了】
 * v3/v4 把"收入"和"退款"硬塞进同一张表、再用子表和缓存列去描述它们。
 * 结果是每条查询都要写 `kind = 0`、每一次统计都要减缓存列 ——
 * 漏一处就出现"总额含收入、占比不含"这种不会报错的错数（真的漏过）。
 *
 * 拆开之后：
 *   花销账本 = expenses，退款就是一条负数账 → `SUM` 天然是净额
 *   收入账本 = incomes，独立一张表 → 统计再也不会串
 */
@Database(
    entities = [
        ExpenseEntity::class,
        IncomeEntity::class,
        CategoryEntity::class,
        CurrencyEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class HoBaoDatabase : RoomDatabase() {

    abstract fun expenseDao(): ExpenseDao
    abstract fun incomeDao(): IncomeDao
    abstract fun categoryDao(): CategoryDao
    abstract fun currencyDao(): CurrencyDao

    companion object {
        private const val DB_NAME = "hobao.db"

        /** 退款 / 返现用的分类名，迁移和播种都要用 */
        const val REFUND_CATEGORY = "退款"
        const val CASHBACK_CATEGORY = "返现"

        @Volatile
        private var instance: HoBaoDatabase? = null

        /**
         * 2 → 3：加 `kind` 列，并给老库补上收入分类。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE categories ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
                insertIncomeCategories(db)
            }
        }

        /**
         * 3 → 4：退款子表 + 缓存列。
         *
         * ⚠️ 这一版的结构在 4 → 5 里被拆掉了，但**这条迁移不能删** ——
         * 用户手机里可能还躺着 v3 的库，升级时要 3→4→5 一级一级走。
         * 迁移器只增不改。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `adjustments` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `expenseId` INTEGER NOT NULL,
                      `amountMinor` INTEGER NOT NULL,
                      `reason` TEXT NOT NULL,
                      `createdAt` INTEGER NOT NULL,
                      FOREIGN KEY(`expenseId`) REFERENCES `expenses`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_adjustments_expenseId` " +
                        "ON `adjustments` (`expenseId`)"
                )
                db.execSQL(
                    "ALTER TABLE expenses ADD COLUMN refundedCnyCents INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 4 → 5：拆成两本账。
         *
         * 顺序很重要（写错了要么丢数据，要么外键报错）：
         * 1. 先补两个分类（退款 / 返现）—— 后面插退款行要用它们的 id
         * 2. 建 incomes，把 kind = 1 的行搬过去（**必须在 drop expenses 之前**）
         * 3. 建 expenses_new，把 kind = 0 的行拷过去
         * 4. 把 adjustments 展开成负数行（此时旧 expenses 还在，要取它的币种和汇率）
         * 5. 拆掉旧表，改名，重建三个索引
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()

                // 1) 两个新分类
                db.execSQL(
                    """
                    INSERT INTO categories (name, colorArgb, sortOrder, isArchived, createdAt, kind)
                    SELECT '$REFUND_CATEGORY', 0xFF8A5182, 200, 0, ?, 0
                    WHERE NOT EXISTS (
                      SELECT 1 FROM categories WHERE name = '$REFUND_CATEGORY' AND kind = 0
                    )
                    """.trimIndent(),
                    arrayOf<Any?>(now),
                )
                db.execSQL(
                    """
                    INSERT INTO categories (name, colorArgb, sortOrder, isArchived, createdAt, kind)
                    SELECT '$CASHBACK_CATEGORY', 0xFF2E6C3F, 201, 0, ?, 0
                    WHERE NOT EXISTS (
                      SELECT 1 FROM categories WHERE name = '$CASHBACK_CATEGORY' AND kind = 0
                    )
                    """.trimIndent(),
                    arrayOf<Any?>(now),
                )

                // 2) 收入搬到独立表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `incomes` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `amountMinor` INTEGER NOT NULL,
                      `currencyCode` TEXT NOT NULL,
                      `ratePerCny` REAL NOT NULL,
                      `reason` TEXT NOT NULL,
                      `categoryId` INTEGER NOT NULL,
                      `createdAt` INTEGER NOT NULL,
                      `updatedAt` INTEGER NOT NULL,
                      FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_incomes_categoryId` ON `incomes` (`categoryId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_incomes_createdAt` ON `incomes` (`createdAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_incomes_currencyCode` " +
                        "ON `incomes` (`currencyCode`)"
                )
                db.execSQL(
                    """
                    INSERT INTO incomes
                      (amountMinor, currencyCode, ratePerCny, reason, categoryId, createdAt, updatedAt)
                    SELECT amountMinor, currencyCode, ratePerCny, reason, categoryId, createdAt, updatedAt
                    FROM expenses WHERE kind = 1
                    """.trimIndent()
                )

                // 3) 新的花销表（没有 kind、没有 refundedCnyCents）
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `expenses_new` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `amountMinor` INTEGER NOT NULL,
                      `currencyCode` TEXT NOT NULL,
                      `ratePerCny` REAL NOT NULL,
                      `reason` TEXT NOT NULL,
                      `categoryId` INTEGER NOT NULL,
                      `createdAt` INTEGER NOT NULL,
                      `updatedAt` INTEGER NOT NULL,
                      FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO expenses_new
                      (id, amountMinor, currencyCode, ratePerCny, reason, categoryId, createdAt, updatedAt)
                    SELECT id, amountMinor, currencyCode, ratePerCny, reason, categoryId, createdAt, updatedAt
                    FROM expenses WHERE kind = 0
                    """.trimIndent()
                )

                // 4) 退款展开成负数行。币种和汇率取**原账冻结的那一份**，
                //    这样退回来的钱和当初花出去的钱用的是同一把尺子。
                db.execSQL(
                    """
                    INSERT INTO expenses_new
                      (amountMinor, currencyCode, ratePerCny, reason, categoryId, createdAt, updatedAt)
                    SELECT -a.amountMinor, e.currencyCode, e.ratePerCny, a.reason,
                           (SELECT id FROM categories WHERE name = '$REFUND_CATEGORY' AND kind = 0 LIMIT 1),
                           a.createdAt, a.createdAt
                    FROM adjustments a
                    JOIN expenses e ON e.id = a.expenseId
                    WHERE a.amountMinor > 0
                    """.trimIndent()
                )

                // 5) 拆旧结构、改名、重建索引
                db.execSQL("DROP TABLE IF EXISTS `adjustments`")
                db.execSQL("DROP TABLE `expenses`")
                db.execSQL("ALTER TABLE `expenses_new` RENAME TO `expenses`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_expenses_categoryId` ON `expenses` (`categoryId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_expenses_createdAt` ON `expenses` (`createdAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_expenses_currencyCode` " +
                        "ON `expenses` (`currencyCode`)"
                )
            }
        }

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
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // ⚠️ 不能有 fallbackToDestructiveMigration —— 迁移失败要炸出来，不能删库了事
                .build()
    }
}

/**
 * 首次创建数据库时写入初始数据。
 *
 * 【为什么必须用 RoomDatabase.Callback，而不是"如果表是空的就播种"】
 * 因为用户被允许删光所有分类！"表空就播种"会让用户删掉的分类**在下次启动时复活**。
 *
 * 【注意 execSQL 的第二个参数】
 * 用 ? 占位符 + 绑定参数，永远不要拼字符串进 SQL。
 */
private fun seedDatabase(db: SupportSQLiteDatabase) {
    val now = System.currentTimeMillis()

    // 7 个支出分类（kind = 0）
    db.execSQL(
        """
        INSERT INTO categories (name, colorArgb, sortOrder, isArchived, createdAt, kind) VALUES
          ('餐饮', 0xFFE8760B, 0, 0, ?, 0),
          ('交通', 0xFF00696E, 1, 0, ?, 0),
          ('购物', 0xFFB3261E, 2, 0, ?, 0),
          ('住宿', 0xFF6750A4, 3, 0, ?, 0),
          ('学习', 0xFF2E6C3F, 4, 0, ?, 0),
          ('娱乐', 0xFF8A5182, 5, 0, ?, 0),
          ('其他', 0xFF6B6B6B, 6, 0, ?, 0),
          ('退款', 0xFF8A5182, 200, 0, ?, 0),
          ('返现', 0xFF2E6C3F, 201, 0, ?, 0)
        """.trimIndent(),
        Array<Any?>(9) { now },
    )

    insertIncomeCategories(db)

    // 6 个币种。人民币排第一（它是账本锚点），汇率固定 1.0。
    //
    // ⚠️ 其它币种的汇率一律留 0，表示"用户还没填"。
    // 为什么不填一个"看起来合理"的预设值？因为那是在编造数据 ——
    // 用户会以为那是对的，然后拿一个错的汇率算了一整年账。
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
        arrayOf<Any?>(now, 0L, 0L, 0L, 0L, 0L),
    )
}

/**
 * 收入分类（kind = 1）。
 *
 * onCreate 和 MIGRATION_2_3 都要调 —— 新装用户和老用户升级都必须有这几个。
 * sortOrder 从 100 起，保证它们排在支出分类后面。
 */
private fun insertIncomeCategories(db: SupportSQLiteDatabase) {
    val now = System.currentTimeMillis()
    db.execSQL(
        """
        INSERT INTO categories (name, colorArgb, sortOrder, isArchived, createdAt, kind) VALUES
          ('工资',     0xFF1B6C4A, 100, 0, ?, 1),
          ('奖金',     0xFFC97A00, 101, 0, ?, 1),
          ('红包',     0xFFB3261E, 102, 0, ?, 1),
          ('利息',     0xFF00696E, 103, 0, ?, 1),
          ('存款',     0xFF6750A4, 104, 0, ?, 1),
          ('其他收入', 0xFF6B6B6B, 105, 0, ?, 1)
        """.trimIndent(),
        Array<Any?>(6) { now },
    )
}

private val SeedCallback = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        seedDatabase(db)
    }
}
