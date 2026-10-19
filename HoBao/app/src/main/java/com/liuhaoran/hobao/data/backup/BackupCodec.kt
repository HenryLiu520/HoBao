package com.liuhaoran.hobao.data.backup

import org.json.JSONArray
import org.json.JSONObject

/** 备份文件相关的错误。单独一个类型，界面才能把它和"文件读不出来"分开提示 */
class BackupException(message: String) : Exception(message)

/**
 * 备份文件的「编码 / 解码」。
 *
 * 【格式版本化（format versioning）是他问的那个机制】
 *
 * 文件里有一个整数 `formatVersion`。导入时：
 *   1. 读出来
 *   2. 比当前版本**新** → 拒绝（"请先升级 app"）
 *   3. 比当前版本**旧** → 逐级升级（v1→v2→v3），升到当前版本再入库
 *   4. 等于当前版本 → 直接用
 *
 * 这样"改了数据结构之后，用户手里的旧备份还能导入"。
 *
 * 【两条铁律】
 * A. **迁移器只增不改**：v1→v2 的代码写完之后永远不许动。
 *    因为一年后还有人的手机里躺着 v1 的文件。
 * B. **只做向后兼容，不做向前兼容**：
 *    新 app 能读旧文件（简单）；旧 app 读新文件做不到 —— 那就必须**明确报错**，
 *    绝不能"跳过不认识的字段然后静默导入一半"。
 */
object BackupCodec {

    /** 文件里的标记，用来挡住"用户随手选了一个别的 json" */
    const val FORMAT_NAME = "hobao-backup"

    /**
     * ⚠️ 当前格式版本。**改文件结构就必须 +1，并写一个迁移器。**
     *    改数据库表结构不用动它 —— 文件格式和表结构是两件事。
     */
    const val CURRENT_FORMAT_VERSION = 1

    const val APP_VERSION = "0.3"

    /** 账本基准币种，导入时如果文件里没有就补一个 */
    const val BASE_CURRENCY = "CNY"

    // ────────────────────────── 编码 ──────────────────────────

    fun encode(file: BackupFile): String {
        val data = JSONObject()
        data.put("categories", JSONArray().apply {
            file.categories.forEach { c ->
                put(JSONObject().apply {
                    put("name", c.name)
                    put("sortOrder", c.sortOrder)
                    put("isArchived", c.isArchived)
                })
            }
        })
        data.put("currencies", JSONArray().apply {
            file.currencies.forEach { c ->
                put(JSONObject().apply {
                    put("code", c.code)
                    put("nameZh", c.nameZh)
                    put("symbol", c.symbol)
                    put("minorUnits", c.minorUnits)
                    put("ratePerCny", c.ratePerCny)
                    put("rateUpdatedAt", c.rateUpdatedAt)
                })
            }
        })
        data.put("expenses", JSONArray().apply {
            file.expenses.forEach { e ->
                put(JSONObject().apply {
                    put("amountMinor", e.amountMinor)
                    put("currencyCode", e.currencyCode)
                    put("ratePerCny", e.ratePerCny)
                    put("reason", e.reason)
                    put("categoryName", e.categoryName)
                    put("createdAt", e.createdAt)
                    put("updatedAt", e.updatedAt)
                })
            }
        })

        return JSONObject().apply {
            put("format", FORMAT_NAME)
            put("formatVersion", CURRENT_FORMAT_VERSION)
            put("exportedAt", file.exportedAt)
            put("appVersion", file.appVersion)
            put("data", data)
        }.toString(2)   // 缩进 2 空格：备份文件人也要能看懂
    }

    // ────────────────────────── 解码 ──────────────────────────

    fun decode(text: String): BackupFile {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw BackupException("这个文件不是有效的 JSON")
        }

        if (root.optString("format") != FORMAT_NAME) {
            throw BackupException("这不是荷包导出的备份文件")
        }

        val version = root.optInt("formatVersion", -1)
        if (version < 1) {
            throw BackupException("备份文件没有格式版本号，可能已损坏")
        }
        if (version > CURRENT_FORMAT_VERSION) {
            throw BackupException(
                "这个备份来自更新版本的荷包（v$version），请先升级 app 再导入"
            )
        }

        // 旧版本 → 逐级升到当前版本
        val migrated = BackupMigrator.migrate(root, version)

        val data = migrated.optJSONObject("data")
            ?: throw BackupException("备份文件里没有 data 字段")

        val categories = data.optJSONArray("categories")?.mapObjects { o ->
            BackupCategory(
                name = o.getString("name").trim(),
                sortOrder = o.optInt("sortOrder", 0),
                isArchived = o.optBoolean("isArchived", false),
            )
        } ?: emptyList()

        val currencies = data.optJSONArray("currencies")?.mapObjects { o ->
            BackupCurrency(
                code = o.getString("code").uppercase(),
                nameZh = o.optString("nameZh", o.getString("code")),
                symbol = o.optString("symbol", ""),
                minorUnits = o.optInt("minorUnits", 2),
                ratePerCny = o.optDouble("ratePerCny", 0.0),
                rateUpdatedAt = o.optLong("rateUpdatedAt", 0L),
            )
        } ?: emptyList()

        val expenses = data.optJSONArray("expenses")?.mapObjects { o ->
            BackupExpense(
                amountMinor = o.getLong("amountMinor"),
                currencyCode = o.getString("currencyCode").uppercase(),
                ratePerCny = o.optDouble("ratePerCny", 0.0),
                reason = o.optString("reason", ""),
                categoryName = o.optString("categoryName", "").trim(),
                createdAt = o.getLong("createdAt"),
                updatedAt = o.optLong("updatedAt", o.getLong("createdAt")),
            )
        } ?: emptyList()

        return BackupFile(
            formatVersion = version,
            exportedAt = migrated.optLong("exportedAt", 0L),
            appVersion = migrated.optString("appVersion", "?"),
            categories = categories,
            currencies = currencies,
            expenses = expenses,
        )
    }

    private fun <T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> =
        (0 until length()).map { block(getJSONObject(it)) }
}

/**
 * 迁移链（migration chain）。
 *
 * 现在的骨架：只有 v1，所以循环体永远不会执行。
 * **加了 v2 之后这里长这样**（示意）：
 *
 * ```
 * while (v < CURRENT_FORMAT_VERSION) {
 *     obj = when (v) {
 *         1 -> migrateV1ToV2(obj)      // 比如：新增了 "tags" 字段，给旧账补空数组
 *         2 -> migrateV2ToV3(obj)      // 比如：reason 从 string 拆成 { text, note }
 *         else -> throw BackupException("不认识的格式版本 v$v")
 *     }
 *     v++
 * }
 * ```
 *
 * ⚠️ 迁移器必须**只增不改**：v1→v2 写完之后永远不许动。
 *    因为两年后还有人的手机里躺着 v1 的备份。
 */
object BackupMigrator {

    fun migrate(root: JSONObject, fromVersion: Int): JSONObject {
        var v = fromVersion
        var obj = root
        while (v < BackupCodec.CURRENT_FORMAT_VERSION) {
            obj = when (v) {
                // 发布 v2 时在这里加分支
                else -> throw BackupException("不认识的备份格式版本 v$v，请升级 app")
            }
            v++
        }
        return obj
    }
}
